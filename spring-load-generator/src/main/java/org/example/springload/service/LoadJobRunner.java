package org.example.springload.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.example.protobuf.cdc.oracle.v1.CustomerState;
import org.example.protobuf.cdc.oracle.v1.OracleCdcEnvelope;
import org.example.protobuf.cdc.oracle.v1.OracleSourceMetadata;
import org.example.springload.model.LoadJobConfig;
import org.example.springload.model.OracleSourceConfig;

public class LoadJobRunner implements Runnable {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final int BASE_MESSAGE_OVERHEAD = 320;

    private final LoadJobConfig config;
    private final LoadJobStatus status;
    private final List<String> operationCycle;

    public LoadJobRunner(LoadJobConfig config, LoadJobStatus status) {
        this.config = config;
        this.status = status;
        this.operationCycle = List.copyOf(config.operationCycle());
    }

    public void requestStop() {
        status.requestStop();
    }

    @Override
    public void run() {
        status.markRunning();
        long startNanos = System.nanoTime();
        long endNanos = config.durationSeconds() > 0
                ? startNanos + TimeUnit.SECONDS.toNanos(config.durationSeconds())
                : Long.MAX_VALUE;

        AtomicLong sequence = new AtomicLong(0);
        ThreadFactory workerFactory = Thread.ofPlatform().name("load-gen-worker-", 0).factory();
        ExecutorService workerPool = Executors.newFixedThreadPool(config.producerThreads(), workerFactory);

        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProperties())) {
            List<Future<?>> workers = new ArrayList<>();
            for (int i = 0; i < config.producerThreads(); i++) {
                workers.add(workerPool.submit(() -> produceLoop(producer, sequence, startNanos, endNanos)));
            }
            for (Future<?> future : workers) {
                future.get();
            }
            producer.flush();
            if (status.isStopRequested()) {
                status.markStopped();
            } else {
                status.markCompleted();
            }
        } catch (ExecutionException e) {
            status.markFailed(rootMessage(e));
        } catch (Exception e) {
            status.markFailed(rootMessage(e));
        } finally {
            workerPool.shutdownNow();
        }
    }

    private void produceLoop(
            KafkaProducer<String, byte[]> producer,
            AtomicLong sequence,
            long startNanos,
            long endNanos
    ) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (!status.isStopRequested() && !Thread.currentThread().isInterrupted()) {
            long now = System.nanoTime();
            if (now >= endNanos) {
                return;
            }

            long seq = sequence.getAndIncrement();
            pace(seq, startNanos, config.messagesPerSecond());
            if (status.isStopRequested() || Thread.currentThread().isInterrupted()) {
                return;
            }

            String key = config.keyPrefix() + seq;
            String op = operationCycle.get((int) (seq % operationCycle.size()));
            OracleCdcEnvelope envelope = buildEnvelope(seq, op, random);
            ProducerRecord<String, byte[]> record =
                    new ProducerRecord<>(config.topic(), key, envelope.toByteArray());

            try {
                producer.send(record, (metadata, exception) -> {
                    if (exception == null) {
                        status.incrementProduced();
                    } else {
                        status.incrementFailed(exception.getMessage());
                    }
                });
            } catch (Exception e) {
                status.incrementFailed(e.getMessage());
            }
        }
    }

    private void pace(long seq, long startNanos, int messagesPerSecond) {
        long targetNanos = startNanos + (seq * NANOS_PER_SECOND) / messagesPerSecond;
        while (!status.isStopRequested()) {
            long remaining = targetNanos - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            LockSupport.parkNanos(Math.min(remaining, 2_000_000L));
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }

    private OracleCdcEnvelope buildEnvelope(long seq, String op, ThreadLocalRandom random) {
        long nowMillis = Instant.now().toEpochMilli();
        int paddingLength = Math.max(config.messageSizeBytes() - BASE_MESSAGE_OVERHEAD, 16);
        String city = cityBySequence(seq);

        CustomerState before = buildState(seq, "inactive", city, "", random);
        CustomerState after = buildState(seq, "active", city, buildPadding(seq, paddingLength), random);

        OracleCdcEnvelope.Builder builder = OracleCdcEnvelope.newBuilder()
                .setSource(buildSource(seq))
                .setOp(op)
                .setTsMs(nowMillis)
                .setTransactionId(transactionId(seq));

        if ("c".equals(op)) {
            builder.setAfter(after);
        } else if ("u".equals(op)) {
            builder.setBefore(before);
            builder.setAfter(after);
        } else if ("d".equals(op)) {
            builder.setBefore(before);
        }
        return builder.build();
    }

    private CustomerState buildState(
            long seq,
            String statusValue,
            String city,
            String payloadPadding,
            ThreadLocalRandom random
    ) {
        return CustomerState.newBuilder()
                .setId(Long.toString(seq))
                .setFirstName("Name" + seq)
                .setLastName("User" + (seq % 1000))
                .setEmail("customer" + seq + "@example.com")
                .setStatus(statusValue)
                .setCreditLimit(random.nextDouble(500.0, 20_000.0))
                .setCity(city)
                .setCountry("US")
                .setSourceSystem("oracle")
                .setPayloadPadding(payloadPadding)
                .build();
    }

    private OracleSourceMetadata buildSource(long seq) {
        OracleSourceConfig source = config.source();
        long commitScn = seq + 1_000_000;
        return OracleSourceMetadata.newBuilder()
                .setVersion(source.getVersion())
                .setConnector(source.getConnector())
                .setName(source.getName())
                .setTsMs(source.getSourceTimestampMs() > 0 ? source.getSourceTimestampMs() : Instant.now().toEpochMilli())
                .setSnapshot(source.getSnapshot())
                .setDb(source.getDb())
                .setSchema(source.getSchemaName())
                .setTable(source.getTable())
                .setTxId(transactionId(seq))
                .setScn(source.getScnPrefix() + "-" + commitScn)
                .setCommitScn(commitScn)
                .build();
    }

    private String transactionId(long seq) {
        return config.source().getTransactionPrefix() + "-" + seq;
    }

    private String cityBySequence(long seq) {
        String[] cities = {"Austin", "Boston", "Seattle", "Chicago", "Denver"};
        return cities[(int) (seq % cities.length)];
    }

    private String buildPadding(long seq, int targetSize) {
        String seed = "payload-" + seq + "-";
        if (seed.length() >= targetSize) {
            return seed.substring(0, targetSize);
        }
        StringBuilder sb = new StringBuilder(targetSize);
        while (sb.length() < targetSize) {
            sb.append(seed);
        }
        return sb.substring(0, targetSize);
    }

    private Properties producerProperties() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "spring-load-generator-" + status.getJobId());
        props.put(ProducerConfig.ACKS_CONFIG, config.acks());
        props.put(ProducerConfig.LINGER_MS_CONFIG, config.lingerMs());
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, config.batchSizeBytes());
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, config.compressionType());
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "false");
        return props;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }
}

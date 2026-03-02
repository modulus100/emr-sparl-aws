package org.example.springload.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.example.protobuf.cdc.oracle.v1.CustomerState;
import org.example.protobuf.cdc.oracle.v1.OracleCdcEnvelope;
import org.example.protobuf.cdc.oracle.v1.OracleSourceMetadata;
import org.example.springload.model.LoadJobConfig;
import org.example.springload.model.OracleSourceConfig;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

public class LoadJobRunner implements Runnable {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final LoadJobConfig config;
    private final LoadJobStatus status;

    public LoadJobRunner(LoadJobConfig config, LoadJobStatus status) {
        this.config = config;
        this.status = status;
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

        DefaultKafkaProducerFactory<String, byte[]> producerFactory =
                new DefaultKafkaProducerFactory<>(producerProperties());
        KafkaTemplate<String, byte[]> kafkaTemplate = new KafkaTemplate<>(producerFactory);

        try {
            produceLoop(kafkaTemplate, startNanos, endNanos);
            kafkaTemplate.flush();
            if (status.isStopRequested()) {
                status.markStopped();
            } else {
                status.markCompleted();
            }
        } catch (Exception e) {
            status.markFailed(rootMessage(e));
        } finally {
            producerFactory.destroy();
        }
    }

    private void produceLoop(
            KafkaTemplate<String, byte[]> kafkaTemplate,
            long startNanos,
            long endNanos
    ) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        long seq = 0L;
        while (!status.isStopRequested() && !Thread.currentThread().isInterrupted()) {
            long now = System.nanoTime();
            if (now >= endNanos) {
                return;
            }

            pace(seq, startNanos, config.messagesPerSecond());
            if (status.isStopRequested() || Thread.currentThread().isInterrupted()) {
                return;
            }

            String key = config.keyPrefix() + seq;
            OracleCdcEnvelope envelope = buildEnvelope(seq, random);

            try {
                kafkaTemplate.send(config.topic(), key, envelope.toByteArray())
                        .whenComplete((ignored, exception) -> {
                            if (exception != null) {
                                status.setLastError(exception.getMessage());
                            }
                        });
            } catch (Exception e) {
                status.setLastError(e.getMessage());
            }
            seq++;
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

    private OracleCdcEnvelope buildEnvelope(long seq, ThreadLocalRandom random) {
        long nowMillis = Instant.now().toEpochMilli();
        String city = "Austin";

        CustomerState before = buildState(seq, "inactive", city, random);
        CustomerState after = buildState(seq, "active", city, random);

        return OracleCdcEnvelope.newBuilder()
                .setBefore(before)
                .setAfter(after)
                .setSource(buildSource(seq))
                .setOp("u")
                .setTsMs(nowMillis)
                .setTransactionId(transactionId(seq))
                .build();
    }

    private CustomerState buildState(
            long seq,
            String statusValue,
            String city,
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
                .setPayloadPadding("")
                .build();
    }

    private OracleSourceMetadata buildSource(long seq) {
        OracleSourceConfig source = config.source();
        long commitScn = seq + 1_000_000;
        return OracleSourceMetadata.newBuilder()
                .setVersion(source.version())
                .setConnector(source.connector())
                .setName(source.name())
                .setTsMs(source.sourceTimestampMs() > 0 ? source.sourceTimestampMs() : Instant.now().toEpochMilli())
                .setSnapshot(source.snapshot())
                .setDb(source.db())
                .setSchema(source.schemaName())
                .setTable(source.table())
                .setTxId(transactionId(seq))
                .setScn(source.scnPrefix() + "-" + commitScn)
                .setCommitScn(commitScn)
                .build();
    }

    private String transactionId(long seq) {
        return config.source().transactionPrefix() + "-" + seq;
    }

    private Map<String, Object> producerProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
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

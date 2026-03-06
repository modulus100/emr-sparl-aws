package org.example.springload.service;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import java.time.Instant;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.example.protobuf.cdc.oracle.v1.CustomerState;
import org.example.protobuf.cdc.oracle.v1.OracleCdcEnvelope;
import org.example.protobuf.cdc.oracle.v1.OracleSourceMetadata;
import org.example.springload.model.LoadJobConfig;
import org.example.springload.model.LoadWorkerConfig;
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
        ThreadFactory workerFactory = Thread.ofPlatform().name("load-gen-worker-", 0).factory();
        ExecutorService workerPool = Executors.newFixedThreadPool(config.workers().size(), workerFactory);

        DefaultKafkaProducerFactory<String, OracleCdcEnvelope> producerFactory =
                new DefaultKafkaProducerFactory<>(producerProperties());
        KafkaTemplate<String, OracleCdcEnvelope> kafkaTemplate = new KafkaTemplate<>(producerFactory);

        try {
            List<Future<?>> workerFutures = new ArrayList<>();
            for (LoadWorkerConfig worker : config.workers()) {
                workerFutures.add(workerPool.submit(() -> produceLoop(worker, kafkaTemplate, startNanos, endNanos)));
            }
            waitForWorkers(workerFutures);
            kafkaTemplate.flush();
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
            producerFactory.destroy();
        }
    }

    private void produceLoop(
            LoadWorkerConfig worker,
            KafkaTemplate<String, OracleCdcEnvelope> kafkaTemplate,
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

            pace(seq, startNanos, worker.messagesPerSecond());
            if (status.isStopRequested() || Thread.currentThread().isInterrupted()) {
                return;
            }

            String key = worker.keyPrefix() + seq;
            OracleCdcEnvelope envelope = buildEnvelope(seq, random);

            try {
                kafkaTemplate.send(worker.topic(), key, envelope)
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

    private void waitForWorkers(List<Future<?>> workerFutures) throws Exception {
        for (Future<?> future : workerFutures) {
            future.get();
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
        long nowMillis = Instant.now().toEpochMilli();
        return CustomerState.newBuilder()
                .setId(Long.toString(seq))
                .setFirstName("Name" + seq)
                .setMiddleName("M" + (seq % 97))
                .setLastName("User" + (seq % 1000))
                .setEmail("customer" + seq + "@example.com")
                .setStatus(statusValue)
                .setCreditLimit(random.nextDouble(500.0, 20_000.0))
                .setCity(city)
                .setCountry("US")
                .setSourceSystem("oracle")
                .setPayloadPadding("")
                .setPhoneNumber(String.format("+1-555-%04d", seq % 10000))
                .setDateOfBirth(String.format("1985-01-%02d", (seq % 28) + 1))
                .setLoyaltyTier(seq % 10 == 0 ? "gold" : "silver")
                .setAccountNumber("ACC-" + seq)
                .setPostalCode(String.format("%05d", (seq % 90000) + 10000))
                .setState("TX")
                .setStreetAddress((100 + (seq % 900)) + " Market Street")
                .setCompany("Example Corp")
                .setDepartment(seq % 2 == 0 ? "sales" : "support")
                .setJobTitle(seq % 3 == 0 ? "manager" : "specialist")
                .setPreferredLanguage(seq % 2 == 0 ? "en" : "es")
                .setMarketingOptIn(seq % 2 == 0)
                .setRiskScore((int) (seq % 100))
                .setLastLoginTsMs(nowMillis - TimeUnit.MINUTES.toMillis(seq % 120))
                .setUpdatedBy("spring-load-generator")
                .setSegment(seq % 5 == 0 ? "enterprise" : "retail")
                .setTaxId("TIN-" + seq)
                .setExternalReference("EXT-" + seq)
                .setNotes("generated record " + seq)
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
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaProtobufSerializer.class.getName());
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, config.schemaRegistryUrl());
        props.put(AbstractKafkaSchemaSerDeConfig.AUTO_REGISTER_SCHEMAS, true);
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

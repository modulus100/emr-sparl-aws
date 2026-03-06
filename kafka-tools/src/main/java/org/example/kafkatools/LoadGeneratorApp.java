package org.example.kafkatools;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.example.protobuf.cdc.oracle.v1.CustomerState;
import org.example.protobuf.cdc.oracle.v1.OracleCdcEnvelope;
import org.example.protobuf.cdc.oracle.v1.OracleSourceMetadata;

public final class LoadGeneratorApp {

    private LoadGeneratorApp() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnv();

        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers);
        producerProps.put(ProducerConfig.ACKS_CONFIG, config.acks);
        producerProps.put(ProducerConfig.LINGER_MS_CONFIG, config.lingerMs);
        producerProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, config.compressionType);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());

        AtomicLong sent = new AtomicLong();
        AtomicLong failed = new AtomicLong();

        long startedAt = System.nanoTime();
        long runUntil = startedAt + TimeUnit.SECONDS.toNanos(config.durationSeconds);

        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps)) {
            ExecutorService pool = Executors.newFixedThreadPool(config.threads);
            for (int worker = 0; worker < config.threads; worker++) {
                final int workerId = worker;
                pool.submit(() -> publishLoop(workerId, config, producer, sent, failed, runUntil));
            }

            pool.shutdown();
            if (!pool.awaitTermination(config.durationSeconds + 30L, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
            producer.flush();
        }

        double seconds = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) / 1000.0;
        double throughput = seconds == 0 ? 0 : sent.get() / seconds;

        System.out.printf(Locale.ROOT,
                "Load generation completed. sent=%d failed=%d duration=%.2fs throughput=%.2f msg/s%n",
                sent.get(), failed.get(), seconds, throughput);
    }

    private static void publishLoop(
            int workerId,
            Config config,
            KafkaProducer<String, byte[]> producer,
            AtomicLong sent,
            AtomicLong failed,
            long runUntilNanos) {

        Random random = new Random(workerId * 1009L + 17L);
        long pauseNanos = config.messagesPerSecondPerThread > 0
                ? TimeUnit.SECONDS.toNanos(1) / config.messagesPerSecondPerThread
                : 0;
        long nextSendAt = System.nanoTime();

        while (System.nanoTime() < runUntilNanos) {
            long eventId = sent.incrementAndGet();
            OracleCdcEnvelope envelope = buildEnvelope(workerId, eventId, config, random);
            String key = "customer-" + eventId;

            ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                    config.topic,
                    key,
                    envelope.toByteArray());
            record.headers().add("content-type", "application/x-protobuf".getBytes(StandardCharsets.UTF_8));

            Callback callback = (RecordMetadata metadata, Exception exception) -> {
                if (exception != null) {
                    failed.incrementAndGet();
                }
            };

            producer.send(record, callback);

            if (pauseNanos > 0) {
                nextSendAt += pauseNanos;
                long sleepNanos = nextSendAt - System.nanoTime();
                if (sleepNanos > 0) {
                    try {
                        TimeUnit.NANOSECONDS.sleep(sleepNanos);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private static OracleCdcEnvelope buildEnvelope(int workerId, long eventId, Config config, Random random) {
        long now = Instant.now().toEpochMilli();
        String customerId = String.format(Locale.ROOT, "%05d", eventId % 100000);

        CustomerState before = CustomerState.newBuilder()
                .setId(customerId)
                .setFirstName("Prev" + workerId)
                .setMiddleName("M" + (eventId % 97))
                .setLastName("Customer")
                .setEmail("prev." + customerId + "@example.com")
                .setStatus("ACTIVE")
                .setCreditLimit(5000.0)
                .setCity("Austin")
                .setCountry("US")
                .setSourceSystem("oracle")
                .setPhoneNumber(String.format(Locale.ROOT, "+1-555-%04d", eventId % 10000))
                .setDateOfBirth(String.format(Locale.ROOT, "1985-01-%02d", (eventId % 28) + 1))
                .setLoyaltyTier("silver")
                .setAccountNumber("ACC-" + customerId)
                .setPostalCode(String.format(Locale.ROOT, "%05d", (eventId % 90000) + 10000))
                .setState("TX")
                .setStreetAddress((100 + (eventId % 900)) + " Market Street")
                .setCompany("Example Corp")
                .setDepartment("sales")
                .setJobTitle("specialist")
                .setPreferredLanguage("en")
                .setMarketingOptIn(true)
                .setRiskScore((int) (eventId % 100))
                .setLastLoginTsMs(now - TimeUnit.MINUTES.toMillis(eventId % 120))
                .setUpdatedBy("kafka-tools")
                .setSegment("retail")
                .setTaxId("TIN-" + customerId)
                .setExternalReference("EXT-" + customerId)
                .setNotes("previous generated record " + eventId)
                .build();

        CustomerState after = CustomerState.newBuilder()
                .setId(customerId)
                .setFirstName("Current" + workerId)
                .setMiddleName("M" + (eventId % 97))
                .setLastName("Customer")
                .setEmail("current." + customerId + "@example.com")
                .setStatus(eventId % 17 == 0 ? "SUSPENDED" : "ACTIVE")
                .setCreditLimit(5000.0 + random.nextInt(500))
                .setCity(eventId % 3 == 0 ? "Seattle" : "Austin")
                .setCountry("US")
                .setSourceSystem("oracle")
                .setPhoneNumber(String.format(Locale.ROOT, "+1-555-%04d", eventId % 10000))
                .setDateOfBirth(String.format(Locale.ROOT, "1985-01-%02d", (eventId % 28) + 1))
                .setLoyaltyTier(eventId % 10 == 0 ? "gold" : "silver")
                .setAccountNumber("ACC-" + customerId)
                .setPostalCode(String.format(Locale.ROOT, "%05d", (eventId % 90000) + 10000))
                .setState("TX")
                .setStreetAddress((100 + (eventId % 900)) + " Market Street")
                .setCompany("Example Corp")
                .setDepartment(eventId % 2 == 0 ? "sales" : "support")
                .setJobTitle(eventId % 3 == 0 ? "manager" : "specialist")
                .setPreferredLanguage(eventId % 2 == 0 ? "en" : "es")
                .setMarketingOptIn(eventId % 2 == 0)
                .setRiskScore((int) (eventId % 100))
                .setLastLoginTsMs(now - TimeUnit.MINUTES.toMillis(eventId % 120))
                .setUpdatedBy("kafka-tools")
                .setSegment(eventId % 5 == 0 ? "enterprise" : "retail")
                .setTaxId("TIN-" + customerId)
                .setExternalReference("EXT-" + customerId)
                .setNotes("current generated record " + eventId)
                .build();

        OracleSourceMetadata source = OracleSourceMetadata.newBuilder()
                .setVersion("2.7.0.Final")
                .setConnector("oracle")
                .setName(config.connectorName)
                .setTsMs(now)
                .setSnapshot("false")
                .setDb(config.oracleDb)
                .setSchema(config.oracleSchema)
                .setTable(config.oracleTable)
                .setTxId(UUID.randomUUID().toString())
                .setScn(Long.toString(10_000_000L + eventId))
                .setCommitScn(20_000_000L + eventId)
                .build();

        OracleCdcEnvelope draft = OracleCdcEnvelope.newBuilder()
                .setBefore(before)
                .setAfter(after)
                .setSource(source)
                .setOp("u")
                .setTsMs(now)
                .setTransactionId(UUID.randomUUID().toString())
                .build();

        int currentSize = draft.getSerializedSize();
        int bytesMissing = Math.max(0, config.targetMessageSizeBytes - currentSize);
        if (bytesMissing == 0) {
            return draft;
        }

        String padding = randomAscii(bytesMissing);
        CustomerState paddedAfter = after.toBuilder().setPayloadPadding(padding).build();

        return draft.toBuilder().setAfter(paddedAfter).build();
    }

    private static String randomAscii(int size) {
        StringBuilder sb = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        return sb.toString();
    }

    private static final class Config {
        private final String bootstrapServers;
        private final String topic;
        private final int threads;
        private final int messagesPerSecondPerThread;
        private final int durationSeconds;
        private final int targetMessageSizeBytes;
        private final String acks;
        private final String compressionType;
        private final int lingerMs;
        private final String connectorName;
        private final String oracleDb;
        private final String oracleSchema;
        private final String oracleTable;

        private Config(
                String bootstrapServers,
                String topic,
                int threads,
                int messagesPerSecondPerThread,
                int durationSeconds,
                int targetMessageSizeBytes,
                String acks,
                String compressionType,
                int lingerMs,
                String connectorName,
                String oracleDb,
                String oracleSchema,
                String oracleTable) {
            this.bootstrapServers = bootstrapServers;
            this.topic = topic;
            this.threads = threads;
            this.messagesPerSecondPerThread = messagesPerSecondPerThread;
            this.durationSeconds = durationSeconds;
            this.targetMessageSizeBytes = targetMessageSizeBytes;
            this.acks = acks;
            this.compressionType = compressionType;
            this.lingerMs = lingerMs;
            this.connectorName = connectorName;
            this.oracleDb = oracleDb;
            this.oracleSchema = oracleSchema;
            this.oracleTable = oracleTable;
        }

        private static Config fromEnv() {
            return new Config(
                    getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
                    getenv("KAFKA_TOPIC", "oracle-cdc-events"),
                    getenvInt("GENERATOR_THREADS", 4),
                    getenvInt("MESSAGES_PER_SECOND_PER_THREAD", 100),
                    getenvInt("DURATION_SECONDS", 300),
                    getenvInt("TARGET_MESSAGE_SIZE_BYTES", 1024),
                    getenv("KAFKA_ACKS", "all"),
                    getenv("KAFKA_COMPRESSION", "lz4"),
                    getenvInt("KAFKA_LINGER_MS", 20),
                    getenv("CONNECTOR_NAME", "orcl-cdc-connector"),
                    getenv("ORACLE_DB", "ORCLCDB"),
                    getenv("ORACLE_SCHEMA", "DEBEZIUM"),
                    getenv("ORACLE_TABLE", "CUSTOMERS"));
        }

        private static String getenv(String key, String defaultValue) {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? defaultValue : value;
        }

        private static int getenvInt(String key, int defaultValue) {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return Integer.parseInt(value);
        }
    }
}

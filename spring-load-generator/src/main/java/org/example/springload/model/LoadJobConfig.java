package org.example.springload.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

public record LoadJobConfig(
        String jobName,
        String bootstrapServers,
        String topic,
        int producerThreads,
        int messagesPerSecond,
        long durationSeconds,
        int messageSizeBytes,
        String keyPrefix,
        String acks,
        int lingerMs,
        int batchSizeBytes,
        String compressionType,
        List<String> operationCycle,
        OracleSourceConfig source
) {
    @JsonCreator
    public static LoadJobConfig fromYaml(
            @JsonProperty("job_name") String jobName,
            @JsonProperty("bootstrap_servers") String bootstrapServers,
            @JsonProperty("topic") String topic,
            @JsonProperty("producer_threads") Integer producerThreads,
            @JsonProperty("messages_per_second") Integer messagesPerSecond,
            @JsonProperty("duration_seconds") Long durationSeconds,
            @JsonProperty("message_size_bytes") Integer messageSizeBytes,
            @JsonProperty("key_prefix") String keyPrefix,
            @JsonProperty("acks") String acks,
            @JsonProperty("linger_ms") Integer lingerMs,
            @JsonProperty("batch_size_bytes") Integer batchSizeBytes,
            @JsonProperty("compression_type") String compressionType,
            @JsonProperty("operation_cycle") List<String> operationCycle,
            @JsonProperty("source") OracleSourceConfig source
    ) {
        return new LoadJobConfig(
                defaultString(jobName, "oracle-cdc-load"),
                bootstrapServers,
                defaultString(topic, "oracle-cdc-events"),
                producerThreads == null ? 1 : producerThreads,
                messagesPerSecond == null ? 5 : messagesPerSecond,
                durationSeconds == null ? 0L : durationSeconds,
                messageSizeBytes == null ? 1024 : messageSizeBytes,
                defaultString(keyPrefix, "cust-"),
                defaultString(acks, "1"),
                lingerMs == null ? 20 : lingerMs,
                batchSizeBytes == null ? 65536 : batchSizeBytes,
                defaultString(compressionType, "lz4"),
                operationCycle,
                source
        );
    }

    public LoadJobConfig {
        operationCycle = operationCycle == null || operationCycle.isEmpty()
                ? List.of("c", "u", "d")
                : List.copyOf(operationCycle);
        source = source == null ? new OracleSourceConfig() : source;
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public void validate() {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("bootstrap_servers is required");
        }
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic is required");
        }
        if (producerThreads < 1) {
            throw new IllegalArgumentException("producer_threads must be >= 1");
        }
        if (messagesPerSecond < 1) {
            throw new IllegalArgumentException("messages_per_second must be >= 1");
        }
        if (durationSeconds < 0) {
            throw new IllegalArgumentException("duration_seconds must be >= 0");
        }
        if (messageSizeBytes < 256) {
            throw new IllegalArgumentException("message_size_bytes must be >= 256");
        }
        if (operationCycle == null || operationCycle.isEmpty()) {
            throw new IllegalArgumentException("operation_cycle must contain at least one operation");
        }
        for (String op : operationCycle) {
            if (!Objects.equals(op, "c") && !Objects.equals(op, "u") && !Objects.equals(op, "d")) {
                throw new IllegalArgumentException("operation_cycle supports only c, u, d");
            }
        }
        if (source == null) {
            throw new IllegalArgumentException("source section is required");
        }
    }
}

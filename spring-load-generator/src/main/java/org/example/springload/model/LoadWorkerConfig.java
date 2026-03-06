package org.example.springload.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record LoadWorkerConfig(
        String name,
        String topic,
        int messagesPerSecond,
        String keyPrefix
) {
    @JsonCreator
    public static LoadWorkerConfig fromYaml(
            @JsonProperty("name") String name,
            @JsonProperty("topic") String topic,
            @JsonProperty("messages_per_second") Integer messagesPerSecond,
            @JsonProperty("key_prefix") String keyPrefix
    ) {
        return new LoadWorkerConfig(
                defaultString(name, "worker"),
                topic,
                messagesPerSecond == null ? 1 : messagesPerSecond,
                defaultString(keyPrefix, "cust-")
        );
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public void validate() {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("worker topic is required");
        }
        if (messagesPerSecond < 1) {
            throw new IllegalArgumentException("worker messages_per_second must be >= 1");
        }
    }
}

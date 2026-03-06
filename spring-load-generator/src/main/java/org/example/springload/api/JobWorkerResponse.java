package org.example.springload.api;

public record JobWorkerResponse(
        String name,
        String topic,
        int messagesPerSecond,
        String keyPrefix
) {
}

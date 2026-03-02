package org.example.springload.api;

import java.time.Instant;
import org.example.springload.service.JobState;

public record JobStatusResponse(
        String jobId,
        String jobName,
        JobState state,
        Instant submittedAt,
        Instant startedAt,
        Instant finishedAt,
        boolean stopRequested,
        int producerThreads,
        int messagesPerSecond,
        long durationSeconds,
        String topic,
        String lastError
) {
}

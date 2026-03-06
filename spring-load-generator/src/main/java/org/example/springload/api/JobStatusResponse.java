package org.example.springload.api;

import java.time.Instant;
import java.util.List;
import org.example.springload.service.JobState;

public record JobStatusResponse(
        String jobId,
        String jobName,
        JobState state,
        Instant submittedAt,
        Instant startedAt,
        Instant finishedAt,
        boolean stopRequested,
        int workerCount,
        long durationSeconds,
        List<JobWorkerResponse> workers,
        String lastError
) {
}

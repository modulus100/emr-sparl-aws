package org.example.springload.service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public record LoadJobStatus(
        String jobId,
        String jobName,
        AtomicBoolean stopRequested,
        Instant submittedAt,
        AtomicReference<Instant> startedAt,
        AtomicReference<Instant> finishedAt,
        AtomicReference<JobState> state,
        AtomicReference<String> lastError
) {
    public LoadJobStatus(String jobId, String jobName) {
        this(
                jobId,
                jobName,
                new AtomicBoolean(false),
                Instant.now(),
                new AtomicReference<>(),
                new AtomicReference<>(),
                new AtomicReference<>(JobState.SUBMITTED),
                new AtomicReference<>()
        );
    }

    public void markRunning() {
        startedAt.set(Instant.now());
        state.set(JobState.RUNNING);
    }

    public void markCompleted() {
        finishedAt.set(Instant.now());
        state.set(JobState.COMPLETED);
    }

    public void markStopped() {
        finishedAt.set(Instant.now());
        state.set(JobState.STOPPED);
    }

    public void markFailed(String errorMessage) {
        lastError.set(errorMessage);
        finishedAt.set(Instant.now());
        state.set(JobState.FAILED);
    }

    public void setLastError(String errorMessage) {
        lastError.set(errorMessage);
    }

    public void requestStop() {
        stopRequested.set(true);
    }

    public String getJobId() {
        return jobId;
    }

    public String getJobName() {
        return jobName;
    }

    public boolean isStopRequested() {
        return stopRequested().get();
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getStartedAt() {
        return startedAt.get();
    }

    public Instant getFinishedAt() {
        return finishedAt.get();
    }

    public JobState getState() {
        return state.get();
    }

    public String getLastError() {
        return lastError.get();
    }
}

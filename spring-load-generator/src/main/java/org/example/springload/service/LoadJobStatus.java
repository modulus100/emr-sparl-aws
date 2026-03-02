package org.example.springload.service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class LoadJobStatus {
    private final String jobId;
    private final String jobName;
    private final AtomicLong producedMessages = new AtomicLong(0);
    private final AtomicLong failedMessages = new AtomicLong(0);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final Instant submittedAt = Instant.now();

    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile JobState state = JobState.SUBMITTED;
    private volatile String lastError;

    public LoadJobStatus(String jobId, String jobName) {
        this.jobId = jobId;
        this.jobName = jobName;
    }

    public void markRunning() {
        startedAt = Instant.now();
        state = JobState.RUNNING;
    }

    public void markCompleted() {
        finishedAt = Instant.now();
        state = JobState.COMPLETED;
    }

    public void markStopped() {
        finishedAt = Instant.now();
        state = JobState.STOPPED;
    }

    public void markFailed(String errorMessage) {
        lastError = errorMessage;
        finishedAt = Instant.now();
        state = JobState.FAILED;
    }

    public void incrementProduced() {
        producedMessages.incrementAndGet();
    }

    public void incrementFailed(String errorMessage) {
        failedMessages.incrementAndGet();
        lastError = errorMessage;
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

    public long getProducedMessages() {
        return producedMessages.get();
    }

    public long getFailedMessages() {
        return failedMessages.get();
    }

    public boolean isStopRequested() {
        return stopRequested.get();
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public JobState getState() {
        return state;
    }

    public String getLastError() {
        return lastError;
    }
}

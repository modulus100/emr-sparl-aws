package org.example.springload.api;

import org.example.springload.service.JobState;

public record SubmitJobResponse(String jobId, JobState state, String message) {
}

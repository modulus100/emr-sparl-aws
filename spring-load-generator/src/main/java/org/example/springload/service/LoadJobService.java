package org.example.springload.service;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.core.JacksonException;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.example.springload.api.JobWorkerResponse;
import org.example.springload.api.JobStatusResponse;
import org.example.springload.api.SubmitJobResponse;
import org.example.springload.model.LoadJobConfig;
import org.example.springload.model.LoadWorkerConfig;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LoadJobService {
    private final Object lifecycleLock = new Object();
    private final ObjectMapper yamlMapper;
    private final ExecutorService runnerExecutor = java.util.concurrent.Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("load-job-runner-", 0).factory()
    );
    private volatile LoadJobHandle currentJob;

    public LoadJobService() {
        var mapperBuilder = new ObjectMapper(new YAMLFactory()).rebuild();
        mapperBuilder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapperBuilder.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.yamlMapper = mapperBuilder.build();
    }

    public SubmitJobResponse submitYaml(String yamlPayload) {
        LoadJobConfig config = parseConfig(yamlPayload);
        config.validate();

        synchronized (lifecycleLock) {
            if (isActive(currentJob)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Load generator is already running. Use /api/v1/load-generator/update to reconfigure."
                );
            }
            currentJob = startJob(config);
            return new SubmitJobResponse(
                    currentJob.status().getJobId(),
                    currentJob.status().getState(),
                    "Load generator started"
            );
        }
    }

    public SubmitJobResponse updateYaml(String yamlPayload) {
        LoadJobConfig config = parseConfig(yamlPayload);
        config.validate();

        synchronized (lifecycleLock) {
            if (currentJob == null) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No load generator has been started yet. Use /api/v1/load-generator/submit first."
                );
            }
            stopInternal(currentJob);
            currentJob = startJob(config);
            return new SubmitJobResponse(
                    currentJob.status().getJobId(),
                    currentJob.status().getState(),
                    "Load generator updated and restarted"
            );
        }
    }

    public JobStatusResponse stop() {
        synchronized (lifecycleLock) {
            if (currentJob == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No load generator is registered");
            }
            stopInternal(currentJob);
            return toResponse(currentJob);
        }
    }

    public JobStatusResponse status() {
        LoadJobHandle handle = currentJob;
        if (handle == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No load generator is registered");
        }
        return toResponse(handle);
    }

    private LoadJobConfig parseConfig(String yamlPayload) {
        try {
            return yamlMapper.readValue(yamlPayload, LoadJobConfig.class);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid YAML: " + e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        synchronized (lifecycleLock) {
            if (currentJob != null) {
                stopInternal(currentJob);
            }
        }
        runnerExecutor.shutdownNow();
    }

    private LoadJobHandle startJob(LoadJobConfig config) {
        String jobId = UUID.randomUUID().toString();
        LoadJobStatus status = new LoadJobStatus(jobId, config.jobName());
        LoadJobRunner runner = new LoadJobRunner(config, status);
        Future<?> future = runnerExecutor.submit(runner);
        return new LoadJobHandle(config, status, runner, future);
    }

    private void stopInternal(LoadJobHandle handle) {
        handle.runner().requestStop();
        try {
            handle.future().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            handle.future().cancel(true);
        }
    }

    private boolean isActive(LoadJobHandle handle) {
        if (handle == null) {
            return false;
        }
        JobState state = handle.status().getState();
        return state == JobState.SUBMITTED || state == JobState.RUNNING;
    }

    private JobStatusResponse toResponse(LoadJobHandle handle) {
        LoadJobStatus status = handle.status();
        LoadJobConfig config = handle.config();
        return new JobStatusResponse(
                status.getJobId(),
                status.getJobName(),
                status.getState(),
                status.getSubmittedAt(),
                status.getStartedAt(),
                status.getFinishedAt(),
                status.isStopRequested(),
                config.workers().size(),
                config.durationSeconds(),
                config.workers().stream().map(this::toWorkerResponse).toList(),
                status.getLastError()
        );
    }

    private JobWorkerResponse toWorkerResponse(LoadWorkerConfig worker) {
        return new JobWorkerResponse(
                worker.name(),
                worker.topic(),
                worker.messagesPerSecond(),
                worker.keyPrefix()
        );
    }
}

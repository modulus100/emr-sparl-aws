package org.example.springload.service;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.core.JacksonException;
import jakarta.annotation.PreDestroy;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.example.springload.api.JobStatusResponse;
import org.example.springload.api.SubmitJobResponse;
import org.example.springload.model.LoadJobConfig;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LoadJobService {
    private final Map<String, LoadJobHandle> jobs = new ConcurrentHashMap<>();
    private final ObjectMapper yamlMapper;
    private final ExecutorService runnerExecutor = java.util.concurrent.Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("load-job-runner-", 0).factory()
    );

    public LoadJobService() {
        var mapperBuilder = new ObjectMapper(new YAMLFactory()).rebuild();
        mapperBuilder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapperBuilder.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.yamlMapper = mapperBuilder.build();
    }

    public SubmitJobResponse submitYaml(String yamlPayload) {
        LoadJobConfig config = parseConfig(yamlPayload);
        config.validate();

        String jobId = UUID.randomUUID().toString();
        LoadJobStatus status = new LoadJobStatus(jobId, config.jobName());
        LoadJobRunner runner = new LoadJobRunner(config, status);
        Future<?> future = runnerExecutor.submit(runner);

        jobs.put(jobId, new LoadJobHandle(config, status, runner, future));
        return new SubmitJobResponse(jobId, status.getState(), "Job submitted");
    }

    public JobStatusResponse stop(String jobId) {
        LoadJobHandle handle = getHandle(jobId);
        handle.runner().requestStop();
        return toResponse(handle);
    }

    public JobStatusResponse status(String jobId) {
        return toResponse(getHandle(jobId));
    }

    public List<JobStatusResponse> list() {
        return jobs.values()
                .stream()
                .map(this::toResponse)
                .sorted(Comparator.comparing(JobStatusResponse::submittedAt).reversed())
                .toList();
    }

    @PreDestroy
    public void shutdown() {
        jobs.values().forEach(handle -> handle.runner().requestStop());
        runnerExecutor.shutdownNow();
    }

    private LoadJobConfig parseConfig(String yamlPayload) {
        try {
            return yamlMapper.readValue(yamlPayload, LoadJobConfig.class);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid YAML: " + e.getMessage(), e);
        }
    }

    private LoadJobHandle getHandle(String jobId) {
        LoadJobHandle handle = jobs.get(jobId);
        if (handle == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + jobId);
        }
        return handle;
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
                status.getProducedMessages(),
                status.getFailedMessages(),
                status.isStopRequested(),
                config.producerThreads(),
                config.messagesPerSecond(),
                config.durationSeconds(),
                config.topic(),
                status.getLastError()
        );
    }
}

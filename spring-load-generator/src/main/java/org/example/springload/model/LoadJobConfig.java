package org.example.springload.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record LoadJobConfig(
        String jobName,
        String bootstrapServers,
        long durationSeconds,
        OracleSourceConfig source,
        List<LoadWorkerConfig> workers
) {
    @JsonCreator
    public static LoadJobConfig fromYaml(
            @JsonProperty("job_name") String jobName,
            @JsonProperty("bootstrap_servers") String bootstrapServers,
            @JsonProperty("duration_seconds") Long durationSeconds,
            @JsonProperty("source") OracleSourceConfig source,
            @JsonProperty("workers") List<LoadWorkerConfig> workers
    ) {
        return new LoadJobConfig(
                defaultString(jobName, "oracle-cdc-load"),
                bootstrapServers,
                durationSeconds == null ? 0L : durationSeconds,
                source,
                workers
        );
    }

    public LoadJobConfig {
        source = source == null ? new OracleSourceConfig() : source;
        workers = workers == null ? List.of() : List.copyOf(workers);
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public void validate() {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("bootstrap_servers is required");
        }
        if (durationSeconds < 0) {
            throw new IllegalArgumentException("duration_seconds must be >= 0");
        }
        if (source == null) {
            throw new IllegalArgumentException("source section is required");
        }
        if (workers == null || workers.isEmpty()) {
            throw new IllegalArgumentException("workers must contain at least one worker");
        }
        for (LoadWorkerConfig worker : workers) {
            if (worker == null) {
                throw new IllegalArgumentException("workers must not contain null entries");
            }
            worker.validate();
        }
    }
}

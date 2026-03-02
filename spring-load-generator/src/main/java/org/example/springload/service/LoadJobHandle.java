package org.example.springload.service;

import java.util.concurrent.Future;
import org.example.springload.model.LoadJobConfig;

public record LoadJobHandle(
        LoadJobConfig config,
        LoadJobStatus status,
        LoadJobRunner runner,
        Future<?> future
) {
}

package org.example.springload.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.example.springload.service.LoadJobService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api/v1/load-jobs")
public class LoadJobController {
    private final LoadJobService loadJobService;

    public LoadJobController(LoadJobService loadJobService) {
        this.loadJobService = loadJobService;
    }

    @PostMapping(
            path = "/submit",
            consumes = {"application/x-yaml", "application/yaml", "text/yaml", "text/plain"},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public SubmitJobResponse submitYaml(@RequestBody String yamlPayload) {
        return loadJobService.submitYaml(yamlPayload);
    }

    @PostMapping(
            path = "/submit-file",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public SubmitJobResponse submitFile(@RequestPart("file") MultipartFile file) {
        try {
            return loadJobService.submitYaml(new String(file.getBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Cannot read uploaded file: " + e.getMessage(), e);
        }
    }

    @GetMapping(path = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public JobStatusResponse status(@PathVariable String jobId) {
        return loadJobService.status(jobId);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<JobStatusResponse> list() {
        return loadJobService.list();
    }

    @PostMapping(path = "/{jobId}/stop", produces = MediaType.APPLICATION_JSON_VALUE)
    public JobStatusResponse stop(@PathVariable String jobId) {
        return loadJobService.stop(jobId);
    }
}

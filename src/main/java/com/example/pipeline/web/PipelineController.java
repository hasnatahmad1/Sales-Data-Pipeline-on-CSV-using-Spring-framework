package com.example.pipeline.web;

import com.example.pipeline.etl.PipelineService;
import com.example.pipeline.etl.PipelineService.PipelineRun;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/pipeline")
public class PipelineController {

    private final PipelineService pipeline;

    public PipelineController(PipelineService pipeline) {
        this.pipeline = pipeline;
    }

    /** Runs the pipeline now and returns the run summary. */
    @PostMapping("/run")
    public PipelineRun run() {
        return pipeline.run();
    }

    @GetMapping("/runs")
    public List<PipelineRun> runs() {
        return pipeline.findRuns();
    }
}

package com.jevsuite.fit.api;

import com.jevsuite.fit.domain.FitReport;
import com.jevsuite.fit.service.FitService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** REST 层（demo-only，默认绑 127.0.0.1；简历数据敏感，生产需前置鉴权 + 本地部署）。 */
@RestController
@RequestMapping("/v1")
public class FitController {

    private final FitService service;
    private final Map<String, FitReport> reports = new ConcurrentHashMap<>();

    public FitController(FitService service) { this.service = service; }

    public record AnalyzeRequest(String resumeText, String jdText) {}

    @PostMapping("/analyze")
    public FitReport analyze(@RequestBody AnalyzeRequest req) {
        FitReport report = service.analyze(req.resumeText(), req.jdText());
        reports.put(report.runId(), report);
        return report;
    }

    @GetMapping("/report/{runId}")
    public FitReport report(@PathVariable String runId) {
        return reports.get(runId);
    }

    @GetMapping("/mock")
    public Map<String, Object> mock() {
        return Map.of("mockMode", service.isMockMode());
    }
}

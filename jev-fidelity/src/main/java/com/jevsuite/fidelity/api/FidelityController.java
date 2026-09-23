package com.jevsuite.fidelity.api;

import com.jevsuite.fidelity.domain.FidelityReport;
import com.jevsuite.fidelity.service.FidelityService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** REST 层（demo-only，默认绑 127.0.0.1；生产需前置鉴权）。 */
@RestController
@RequestMapping("/v1")
public class FidelityController {

    private final FidelityService service;
    private final Map<String, FidelityReport> reports = new ConcurrentHashMap<>();

    public FidelityController(FidelityService service) { this.service = service; }

    public record CheckRequest(String originalText, String editedText) {}

    @PostMapping("/check")
    public FidelityReport check(@RequestBody CheckRequest req) {
        FidelityReport report = service.check(req.originalText(), req.editedText());
        reports.put(report.runId(), report);
        return report;
    }

    @GetMapping("/report/{runId}")
    public FidelityReport report(@PathVariable String runId) {
        return reports.get(runId);
    }

    @GetMapping("/mock")
    public Map<String, Object> mock() {
        return Map.of("mockMode", service.isMockMode());
    }
}

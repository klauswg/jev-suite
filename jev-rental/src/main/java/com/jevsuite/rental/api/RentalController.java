package com.jevsuite.rental.api;

import com.jevsuite.rental.domain.ChecklistReport;
import com.jevsuite.rental.service.RentalService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** REST 层（demo-only，默认绑 127.0.0.1；生产需前置鉴权）。 */
@RestController
@RequestMapping("/v1")
public class RentalController {

    private final RentalService service;
    private final Map<String, ChecklistReport> reports = new ConcurrentHashMap<>();

    public RentalController(RentalService service) { this.service = service; }

    public record ChecklistRequest(String text, String city, Integer rent) {}

    @PostMapping("/checklist")
    public ChecklistReport checklist(@RequestBody ChecklistRequest req) {
        ChecklistReport report = service.analyze(req.text(), req.city(), req.rent());
        reports.put(report.runId(), report);
        return report;
    }

    @GetMapping("/report/{runId}")
    public ChecklistReport report(@PathVariable String runId) {
        return reports.get(runId);
    }

    @GetMapping("/mock")
    public Map<String, Object> mock() {
        return Map.of("mockMode", service.isMockMode());
    }
}

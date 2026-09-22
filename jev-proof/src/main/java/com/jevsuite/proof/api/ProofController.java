package com.jevsuite.proof.api;

import com.jevsuite.proof.domain.AcceptanceResult;
import com.jevsuite.proof.domain.Brief;
import com.jevsuite.proof.service.ProofService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** REST 层（demo-only，默认绑 127.0.0.1；生产需前置鉴权）。 */
@RestController
@RequestMapping("/v1")
public class ProofController {

    private final ProofService service;
    private final Map<String, AcceptanceResult> reports = new ConcurrentHashMap<>();

    public ProofController(ProofService service) { this.service = service; }

    public record AcceptRequest(String brandName, String productName,
                                List<Point> points, String disclosureRequirement,
                                String videoUrl) {}
    public record Point(String id, String text, boolean mustHave) {}

    @PostMapping("/accept")
    public AcceptanceResult accept(@RequestBody AcceptRequest req) {
        Brief brief = new Brief(req.brandName(), req.productName(),
                req.points().stream().map(p -> new Brief.Point(p.id(), p.text(), p.mustHave())).toList(),
                req.disclosureRequirement());
        AcceptanceResult result = service.accept(brief, req.videoUrl());
        reports.put(result.runId(), result);
        return result;
    }

    @GetMapping("/report/{runId}")
    public AcceptanceResult report(@PathVariable String runId) {
        return reports.get(runId);
    }

    @GetMapping("/mock")
    public Map<String, Object> mock() {
        return Map.of("mockMode", service.isMockMode());
    }
}

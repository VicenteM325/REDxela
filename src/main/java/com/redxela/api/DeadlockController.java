package com.redxela.api;

import com.redxela.deadlock.DeadlockDetector;
import com.redxela.deadlock.DeadlockReport;
import com.redxela.simulation.ScenarioService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

/** Visualización y resolución manual del conflicto de recursos, y escenarios para provocarlo. */
@RestController
@RequestMapping("/api")
public class DeadlockController {

    private final DeadlockDetector detector;
    private final ScenarioService scenarios;

    public DeadlockController(DeadlockDetector detector, ScenarioService scenarios) {
        this.detector = detector;
        this.scenarios = scenarios;
    }

    /** Estado del conflicto: {@code report} es {@code null} cuando no hay deadlock. */
    @GetMapping("/deadlock")
    public DeadlockStatus status() {
        DeadlockReport report = detector.current().orElse(null);
        return new DeadlockStatus(report != null, report);
    }

    /** El pedido indicado conserva los recursos en disputa; los demás vuelven a la lista de espera. */
    @PostMapping("/deadlock/resolve")
    public DeadlockStatus resolve(@Valid @RequestBody ResolveRequest req) {
        try {
            detector.resolve(req.keepOrderId());
        } catch (IllegalStateException e) {
            throw new ConflictException(e.getMessage());
        }
        return status();
    }

    @PostMapping("/scenarios/deadlock")
    public ScenarioService.ScenarioResult provokeDeadlock() throws InterruptedException {
        return scenarios.provokeDeadlock();
    }

    @PostMapping("/scenarios/contention")
    public ScenarioService.ScenarioResult provokeContention() throws InterruptedException {
        return scenarios.provokeContention();
    }

    public record ResolveRequest(@Min(1) long keepOrderId) {
    }

    public record DeadlockStatus(boolean active, DeadlockReport report) {
    }
}

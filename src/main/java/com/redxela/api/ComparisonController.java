package com.redxela.api;

import com.redxela.simulation.PolicyComparator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Comparación de políticas de atención con una misma carga reproducible. */
@RestController
@RequestMapping("/api/compare")
public class ComparisonController {

    private final PolicyComparator comparator;

    public ComparisonController(PolicyComparator comparator) {
        this.comparator = comparator;
    }

    /** Inicia la comparación en segundo plano; los parámetros omitidos toman su valor por defecto. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PolicyComparator.Status start(@Valid @RequestBody(required = false) CompareRequest req) {
        PolicyComparator.Params d = PolicyComparator.Params.defaults();
        PolicyComparator.Params p = req == null ? d : new PolicyComparator.Params(
                req.horizonMinutes() != null ? req.horizonMinutes() : d.horizonMinutes(),
                req.ratePerMinute() != null ? req.ratePerMinute() : d.ratePerMinute(),
                req.seed() != null ? req.seed() : d.seed(),
                req.msPerSimMinute() != null ? req.msPerSimMinute() : d.msPerSimMinute());
        if (!comparator.start(p)) {
            throw new ConflictException("Ya hay una comparación en curso");
        }
        return comparator.status();
    }

    @GetMapping
    public PolicyComparator.Status status() {
        return comparator.status();
    }

    public record CompareRequest(Double horizonMinutes, Double ratePerMinute, Long seed, Double msPerSimMinute) {
    }
}

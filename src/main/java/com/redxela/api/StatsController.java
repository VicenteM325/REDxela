package com.redxela.api;

import com.redxela.stats.StatsCollector;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Estadísticas generales del sistema. */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsCollector stats;

    public StatsController(StatsCollector stats) {
        this.stats = stats;
    }

    @GetMapping
    public StatsCollector.Snapshot stats() {
        return stats.snapshot();
    }

    /** Pone las estadísticas en cero para medir desde un punto limpio. */
    @PostMapping("/reset")
    public StatsCollector.Snapshot reset() {
        stats.reset();
        return stats.snapshot();
    }
}

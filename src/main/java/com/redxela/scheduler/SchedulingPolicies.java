package com.redxela.scheduler;

import java.util.List;
import java.util.Optional;

/** Catálogo de las políticas disponibles. */
public final class SchedulingPolicies {

    private SchedulingPolicies() {
    }

    /** Instancias nuevas de todas las políticas; la primera es la que se usa por defecto. */
    public static List<SchedulingPolicy> all() {
        return List.of(new AgingPolicy(), new StrictPriorityPolicy(), new ShortestOrderFirstPolicy(), new FcfsPolicy());
    }

    public static Optional<SchedulingPolicy> byId(String id) {
        return all().stream().filter(p -> p.id().equalsIgnoreCase(id)).findFirst();
    }
}

package com.redxela.scheduler;

import com.redxela.domain.Order;

import java.util.Comparator;

/**
 * Prioridad estricta por nivel de servicio (1 = Exprés primero). Dentro de un mismo nivel, orden
 * de llegada. Cumple los objetivos de los niveles altos pero puede dejar sin atención
 * indefinidamente (inanición) a los niveles bajos si los altos llegan sin parar.
 */
public class StrictPriorityPolicy implements SchedulingPolicy {

    @Override
    public String id() {
        return "STRICT_PRIORITY";
    }

    @Override
    public String name() {
        return "Prioridad estricta";
    }

    @Override
    public String description() {
        return "Primero el nivel de servicio más alto (1 Exprés … 5 Económico); a igual nivel, el que llegó antes.";
    }

    @Override
    public Comparator<Order> comparator(double now) {
        return Comparator.<Order>comparingInt(o -> o.serviceLevel().level())
                .thenComparing(SchedulingPolicy.byArrival());
    }
}

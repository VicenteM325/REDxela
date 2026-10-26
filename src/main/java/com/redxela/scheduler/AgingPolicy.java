package com.redxela.scheduler;

import com.redxela.domain.Order;

import java.util.Comparator;

/**
 * Prioridad con envejecimiento (aging): la prioridad efectiva de un pedido mejora con el tiempo
 * que lleva esperando, así que ningún pedido espera para siempre. Un pedido de nivel 5 que lleva
 * 40 minutos esperando compite como uno de nivel 1.
 *
 * <p>prioridad efectiva = nivel − minutos_en_espera / minutosPorNivel (menor = antes).
 */
public class AgingPolicy implements SchedulingPolicy {

    private final double minutesPerLevel;

    /** @param minutesPerLevel minutos simulados de espera que equivalen a subir un nivel de prioridad */
    public AgingPolicy(double minutesPerLevel) {
        if (minutesPerLevel <= 0) {
            throw new IllegalArgumentException("Los minutos por nivel deben ser positivos");
        }
        this.minutesPerLevel = minutesPerLevel;
    }

    public AgingPolicy() {
        this(10);
    }

    @Override
    public String id() {
        return "AGING";
    }

    @Override
    public String name() {
        return "Prioridad con envejecimiento";
    }

    @Override
    public String description() {
        return "Prioridad por nivel, pero cada " + (int) minutesPerLevel
                + " min de espera el pedido sube un nivel; evita la inanición de los niveles bajos.";
    }

    /** Prioridad efectiva en el instante {@code now}; menor significa más urgente. */
    double effectivePriority(Order o, double now) {
        return o.serviceLevel().level() - Math.max(0, o.ageMinutes(now)) / minutesPerLevel;
    }

    @Override
    public Comparator<Order> comparator(double now) {
        return Comparator.<Order>comparingDouble(o -> effectivePriority(o, now))
                .thenComparing(SchedulingPolicy.byArrival());
    }
}

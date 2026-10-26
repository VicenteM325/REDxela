package com.redxela.scheduler;

import com.redxela.domain.Order;

import java.util.Comparator;

/** Orden de llegada puro (FCFS). Sirve como línea base para comparar las demás políticas. */
public class FcfsPolicy implements SchedulingPolicy {

    @Override
    public String id() {
        return "FCFS";
    }

    @Override
    public String name() {
        return "Orden de llegada (FCFS)";
    }

    @Override
    public String description() {
        return "Primero el que llegó antes, sin mirar nivel de servicio ni tamaño. Línea base de comparación.";
    }

    @Override
    public Comparator<Order> comparator(double now) {
        return SchedulingPolicy.byArrival();
    }
}

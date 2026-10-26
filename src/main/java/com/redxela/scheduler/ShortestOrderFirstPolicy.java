package com.redxela.scheduler;

import com.redxela.domain.Order;

import java.util.Comparator;

/**
 * Menor cantidad de artículos primero (análogo a SJF). Los pedidos pequeños terminan rápido y
 * liberan estaciones antes, lo que reduce el tiempo de espera promedio, a costa de que los pedidos
 * grandes puedan esperar mucho y de ignorar el nivel de servicio.
 */
public class ShortestOrderFirstPolicy implements SchedulingPolicy {

    @Override
    public String id() {
        return "SHORTEST_FIRST";
    }

    @Override
    public String name() {
        return "Menor cantidad primero";
    }

    @Override
    public String description() {
        return "Primero el pedido con menos artículos (libera estaciones más rápido); a igual tamaño, el que llegó antes.";
    }

    @Override
    public Comparator<Order> comparator(double now) {
        return Comparator.<Order>comparingInt(Order::totalUnits)
                .thenComparing(SchedulingPolicy.byArrival());
    }
}

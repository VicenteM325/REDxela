package com.redxela.scheduler;

import com.redxela.domain.Order;

import java.util.Comparator;

/**
 * Política de atención: decide en qué orden se consideran los pedidos de la lista de espera.
 * Es intercambiable en caliente para poder comparar su desempeño (patrón Strategy).
 */
public interface SchedulingPolicy {

    /** Identificador estable (se usa en la API). */
    String id();

    /** Nombre legible para la interfaz. */
    String name();

    /** Criterio de orden en lenguaje llano; la interfaz lo muestra junto a la lista de espera. */
    String description();

    /**
     * Comparador que ordena los pedidos del que se atiende primero al último. Recibe el instante
     * simulado actual porque algunas políticas dependen del tiempo (envejecimiento).
     */
    Comparator<Order> comparator(double now);

    /** Desempate común: llegada más antigua primero y, si empatan, menor id. */
    static Comparator<Order> byArrival() {
        return Comparator.comparingDouble(Order::createdAt).thenComparingLong(Order::id);
    }
}

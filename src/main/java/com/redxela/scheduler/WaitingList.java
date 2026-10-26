package com.redxela.scheduler;

import com.redxela.domain.Order;

import java.util.ArrayList;
import java.util.List;

/**
 * Lista de espera de pedidos que aún no obtienen sus recursos. Es compartida por la API, el
 * generador, el despachador y los workers (reencolado), así que todas las operaciones están
 * sincronizadas. El orden de atención no se guarda: se calcula bajo demanda con la política
 * activa, lo que permite cambiarla en caliente y reordena la lista al instante.
 */
public class WaitingList {

    private final List<Order> orders = new ArrayList<>();

    public synchronized void add(Order order) {
        orders.add(order);
    }

    /** @return {@code true} si el pedido estaba en la lista y fue retirado */
    public synchronized boolean remove(Order order) {
        return orders.remove(order);
    }

    public synchronized int size() {
        return orders.size();
    }

    public synchronized boolean contains(Order order) {
        return orders.contains(order);
    }

    /** Hay en la lista un pedido con ese id. */
    public synchronized boolean containsId(long id) {
        for (Order o : orders) {
            if (o.id() == id) {
                return true;
            }
        }
        return false;
    }

    /** Copia de la lista ordenada según la política, del primero al último en ser atendido. */
    public List<Order> ordered(SchedulingPolicy policy, double now) {
        List<Order> copy;
        synchronized (this) {
            copy = new ArrayList<>(orders);
        }
        copy.sort(policy.comparator(now));
        return copy;
    }
}

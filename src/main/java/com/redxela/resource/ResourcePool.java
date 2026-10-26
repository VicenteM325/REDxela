package com.redxela.resource;

import com.redxela.domain.Order;
import com.redxela.domain.ResourceType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * Conjunto de unidades idénticas de un recurso (p. ej. los 3 montacargas), controlado con un
 * {@link Semaphore} contador justo (FIFO) para evitar inanición entre los que esperan.
 *
 * <p>El semáforo solo cuenta permisos; no sabe <em>quién</em> los tiene. Por eso el pool lleva
 * además el registro de titulares y de pedidos en espera, que la interfaz muestra ("en uso, y por
 * quién") y que el detector de deadlock usa para armar el grafo de espera. Ese registro se protege
 * con un monitor propio, que <strong>nunca</strong> se mantiene mientras se bloquea en el
 * semáforo (si no, nadie podría liberar ni consultar el estado).
 *
 * <p>Cada pedido usa como máximo una unidad de cada recurso.
 */
public class ResourcePool {

    private final ResourceType type;
    private final Semaphore semaphore;
    private final Object registryLock = new Object();
    private final Set<Long> holders = new LinkedHashSet<>();
    private final Set<Long> waiters = new LinkedHashSet<>();

    public ResourcePool(ResourceType type) {
        this.type = type;
        this.semaphore = new Semaphore(type.capacity(), true);
    }

    /**
     * Toma una unidad, bloqueando hasta que haya una disponible.
     *
     * @throws InterruptedException si el hilo es interrumpido mientras espera (p. ej. al resolver
     *                              un deadlock); en ese caso no queda ningún permiso tomado
     */
    public void acquire(Order order) throws InterruptedException {
        synchronized (registryLock) {
            if (holders.contains(order.id())) {
                throw new IllegalStateException("El pedido " + order.id() + " ya tiene " + type);
            }
            waiters.add(order.id());
        }
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            synchronized (registryLock) {
                waiters.remove(order.id());
            }
            throw e;
        }
        synchronized (registryLock) {
            waiters.remove(order.id());
            holders.add(order.id());
        }
    }

    /** Intenta tomar una unidad sin bloquear. @return {@code true} si la obtuvo */
    public boolean tryAcquire(Order order) {
        synchronized (registryLock) {
            if (holders.contains(order.id())) {
                throw new IllegalStateException("El pedido " + order.id() + " ya tiene " + type);
            }
        }
        if (!semaphore.tryAcquire()) {
            return false;
        }
        synchronized (registryLock) {
            holders.add(order.id());
        }
        return true;
    }

    /**
     * Libera la unidad del pedido. Es idempotente: si el pedido no la tiene no hace nada, lo que
     * evita inflar el semáforo cuando se libera dos veces (p. ej. rollback + bloque finally).
     */
    public void release(Order order) {
        boolean wasHolder;
        synchronized (registryLock) {
            wasHolder = holders.remove(order.id());
        }
        if (wasHolder) {
            semaphore.release();
        }
    }

    public boolean isHeldBy(Order order) {
        synchronized (registryLock) {
            return holders.contains(order.id());
        }
    }

    public ResourceType type() {
        return type;
    }

    public ResourceSnapshot snapshot() {
        synchronized (registryLock) {
            return new ResourceSnapshot(type, type.capacity(), semaphore.availablePermits(),
                    new ArrayList<>(holders), new ArrayList<>(waiters));
        }
    }
}

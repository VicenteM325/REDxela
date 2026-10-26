package com.redxela.resource;

import com.redxela.domain.Order;
import com.redxela.domain.ResourceType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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
 * <p>Cada dueño (un pedido, o un lote en recepción) usa como máximo una unidad de cada recurso.
 * Los pedidos se identifican por su id (positivo); los lotes de recepción usan ids negativos para
 * que la interfaz pueda distinguirlos.
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
        acquire(order.id());
    }

    /** Igual que {@link #acquire(Order)} para un dueño identificado solo por su id. */
    public void acquire(long ownerId) throws InterruptedException {
        registerWaiter(ownerId);
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            unregisterWaiter(ownerId);
            throw e;
        }
        promoteToHolder(ownerId);
    }

    /**
     * Toma una unidad esperando como máximo {@code timeoutMs} milisegundos reales.
     *
     * @return {@code true} si la obtuvo; {@code false} si venció el plazo (no queda nada tomado)
     */
    public boolean tryAcquire(long ownerId, long timeoutMs) throws InterruptedException {
        registerWaiter(ownerId);
        boolean got;
        try {
            got = semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            unregisterWaiter(ownerId);
            throw e;
        }
        if (!got) {
            unregisterWaiter(ownerId);
            return false;
        }
        promoteToHolder(ownerId);
        return true;
    }

    /** Intenta tomar una unidad sin bloquear. @return {@code true} si la obtuvo */
    public boolean tryAcquire(Order order) {
        return tryAcquire(order.id());
    }

    public boolean tryAcquire(long ownerId) {
        synchronized (registryLock) {
            if (holders.contains(ownerId)) {
                throw new IllegalStateException("El dueño " + ownerId + " ya tiene " + type);
            }
        }
        // Se usa la variante con plazo (0 ms) porque, a diferencia de tryAcquire() sin plazo, respeta
        // la cola FIFO del semáforo justo: así quien intenta sin bloquear (el despachador) no se
        // cuela delante de quien ya espera (p. ej. un lote de Recepción) y lo deja sin atender nunca.
        boolean got;
        try {
            got = semaphore.tryAcquire(0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!got) {
            return false;
        }
        synchronized (registryLock) {
            holders.add(ownerId);
        }
        return true;
    }

    /**
     * Libera la unidad del pedido. Es idempotente: si el pedido no la tiene no hace nada, lo que
     * evita inflar el semáforo cuando se libera dos veces (p. ej. rollback + bloque finally).
     */
    public void release(Order order) {
        release(order.id());
    }

    public void release(long ownerId) {
        boolean wasHolder;
        synchronized (registryLock) {
            wasHolder = holders.remove(ownerId);
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

    private void registerWaiter(long ownerId) {
        synchronized (registryLock) {
            if (holders.contains(ownerId)) {
                throw new IllegalStateException("El dueño " + ownerId + " ya tiene " + type);
            }
            waiters.add(ownerId);
        }
    }

    private void unregisterWaiter(long ownerId) {
        synchronized (registryLock) {
            waiters.remove(ownerId);
        }
    }

    private void promoteToHolder(long ownerId) {
        synchronized (registryLock) {
            waiters.remove(ownerId);
            holders.add(ownerId);
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

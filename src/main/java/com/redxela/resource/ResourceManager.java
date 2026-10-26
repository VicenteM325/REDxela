package com.redxela.resource;

import com.redxela.domain.Order;
import com.redxela.domain.ResourceType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Administra todos los recursos compartidos del centro y define cómo se adquieren varios a la vez.
 */
public class ResourceManager {

    private final Map<ResourceType, ResourcePool> pools = new EnumMap<>(ResourceType.class);

    /**
     * Serializa los intentos de adquisición atómica. Sin esto, dos pedidos podrían tomar cada uno
     * una parte de lo que necesitan y deshacer su intento a la vez (livelock). Los workers que
     * adquieren de forma incremental no usan este cerrojo: no lo necesitan porque el rollback de
     * {@link #tryAcquireAll} solo libera permisos que él mismo tomó.
     */
    private final ReentrantLock atomicLock = new ReentrantLock();

    public ResourceManager() {
        for (ResourceType t : ResourceType.values()) {
            pools.put(t, new ResourcePool(t));
        }
    }

    public ResourcePool pool(ResourceType type) {
        return pools.get(type);
    }

    /**
     * Toma todos los recursos del pedido o ninguno, sin bloquear nunca. Si falta alguno devuelve
     * los que ya había tomado y responde {@code false}.
     */
    public boolean tryAcquireAll(Order order) {
        atomicLock.lock();
        try {
            List<ResourceType> taken = new ArrayList<>();
            for (ResourceType type : order.requiredResources()) {
                if (pools.get(type).tryAcquire(order)) {
                    taken.add(type);
                } else {
                    for (ResourceType t : taken) {
                        pools.get(t).release(order);
                    }
                    return false;
                }
            }
            return true;
        } finally {
            atomicLock.unlock();
        }
    }

    /** Libera todo lo que el pedido tenga, en cualquier pool. Seguro de llamar varias veces. */
    public void releaseAll(Order order) {
        for (ResourcePool pool : pools.values()) {
            pool.release(order);
        }
    }

    /**
     * Orden en que un pedido debe pedir sus recursos de forma incremental.
     * {@link AcquisitionMode#NATURAL}: el del tipo de mercancía. {@link AcquisitionMode#ORDERED}:
     * el orden global fijo del enum, igual para todos los pedidos.
     */
    public List<ResourceType> sequenceFor(Order order, AcquisitionMode mode) {
        List<ResourceType> seq = new ArrayList<>(order.requiredResources());
        if (mode == AcquisitionMode.ORDERED) {
            seq.sort(Comparator.naturalOrder());
        }
        return seq;
    }

    /** Estado de todos los recursos, en el orden del enum. */
    public List<ResourceSnapshot> snapshot() {
        return pools.values().stream().map(ResourcePool::snapshot).toList();
    }
}

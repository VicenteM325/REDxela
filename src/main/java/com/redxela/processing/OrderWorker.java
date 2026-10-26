package com.redxela.processing;

import com.redxela.domain.Order;
import com.redxela.domain.OrderStatus;
import com.redxela.domain.ResourceType;
import com.redxela.resource.AcquisitionMode;
import com.redxela.resource.ResourceManager;
import com.redxela.simulation.EventLog;
import com.redxela.simulation.SimClock;

import java.util.List;

/**
 * Hilo que procesa un pedido: obtiene sus recursos (si el despachador no se los dio ya), los usa
 * durante el tiempo que marcan sus unidades y los libera. Si lo interrumpen por una preempción
 * (resolución manual de un deadlock) el pedido vuelve a la lista de espera.
 */
final class OrderWorker implements Runnable {

    /** Tamaño máximo de cada pausa de trabajo, para refrescar el progreso con fluidez. */
    private static final long PROGRESS_STEP_MS = 40;

    private final Dispatcher dispatcher;
    private final Order order;
    private final boolean holdsAll;
    private final ResourceManager resources;
    private final SimClock clock;
    private final EventLog log;
    private final AcquisitionMode mode;
    private final long acquisitionPauseMs;

    private Thread runner;
    private boolean preemptRequested;
    private boolean done;

    /**
     * @param holdsAll {@code true} si el despachador ya tomó todos los recursos (modo ATOMIC)
     * @param mode     modo con el que el despachador admitió el pedido (no cambia a mitad de camino)
     */
    OrderWorker(Dispatcher dispatcher, Order order, boolean holdsAll, ResourceManager resources,
                SimClock clock, EventLog log, AcquisitionMode mode, long acquisitionPauseMs) {
        this.dispatcher = dispatcher;
        this.order = order;
        this.holdsAll = holdsAll;
        this.resources = resources;
        this.clock = clock;
        this.log = log;
        this.mode = mode;
        this.acquisitionPauseMs = acquisitionPauseMs;
    }

    /**
     * Pide interrumpir este worker. Se sincroniza con el fin de {@link #run()} para no
     * interrumpir por error al hilo del pool cuando ya pasó a ejecutar otra tarea.
     *
     * @return {@code false} si el worker ya había terminado
     */
    synchronized boolean requestPreemption() {
        if (done) {
            return false;
        }
        preemptRequested = true;
        if (runner != null) {
            runner.interrupt();
        }
        return true;
    }

    @Override
    public void run() {
        boolean completed = false;
        boolean requeue = false;
        synchronized (this) {
            runner = Thread.currentThread();
        }
        try {
            checkPreemption();
            order.markStarted(clock.now());
            order.status(OrderStatus.EN_PROCESO);
            if (!holdsAll) {
                acquireIncrementally();
            }
            order.waitingFor(null);
            log.add("INICIO", "Pedido #" + order.id() + " (" + order.serviceLevel().displayName() + ", "
                    + order.totalUnits() + " u.) usa " + order.requiredResources());
            doWork();
            // Primero salen las unidades del almacén y solo después el pedido pasa a COMPLETADO, para
            // que nadie observe un pedido completado cuyas existencias aún no se descontaron
            dispatcher.commitStock(order);
            order.progress(1.0);
            order.finishedAt(clock.now());
            order.status(OrderStatus.COMPLETADO);
            completed = true;
            log.add("COMPLETADO", "Pedido #" + order.id() + " completado");
        } catch (InterruptedException e) {
            if (dispatcherPreempted()) {
                requeue = true;
                log.add("PREEMPCION", "Pedido #" + order.id() + " liberó sus recursos y vuelve a la lista de espera");
            } else {
                // Apagado del sistema: se conserva la interrupción y se termina sin reencolar
                log.add("INTERRUMPIDO", "Pedido #" + order.id() + " interrumpido por el cierre del sistema");
            }
        } catch (RuntimeException e) {
            // Un fallo inesperado no debe dejar recursos tomados ni perder el pedido
            log.add("ERROR", "Pedido #" + order.id() + " falló (" + e + "); se reencola");
            requeue = true;
        } finally {
            resources.releaseAll(order);
            synchronized (this) {
                done = true;
                runner = null;
            }
            // Si hubo preempción, limpia una interrupción que no llegó a consumirse (p. ej. el
            // pedido terminó justo antes) para no contaminar al hilo reutilizado del pool
            if (dispatcherPreempted()) {
                Thread.interrupted();
            }
            dispatcher.workerFinished(order, requeue, completed);
        }
    }

    private synchronized boolean dispatcherPreempted() {
        return preemptRequested;
    }

    private void checkPreemption() throws InterruptedException {
        if (dispatcherPreempted()) {
            throw new InterruptedException("preempción solicitada");
        }
    }

    /**
     * Adquisición incremental (modos ORDERED y NATURAL): pide un recurso a la vez, reteniendo los
     * ya obtenidos mientras espera el siguiente ("retener y esperar"). La pausa entre recursos
     * representa el trabajo previo y hace reproducible el entrelazado que provoca el deadlock.
     */
    private void acquireIncrementally() throws InterruptedException {
        List<ResourceType> sequence = resources.sequenceFor(order, mode);
        for (ResourceType type : sequence) {
            order.waitingFor(type);
            resources.pool(type).acquire(order);
            order.waitingFor(null);
            if (acquisitionPauseMs > 0) {
                Thread.sleep(acquisitionPauseMs);
            }
        }
    }

    /** Mantiene los recursos ocupados el tiempo proporcional a las unidades, publicando el progreso. */
    private void doWork() throws InterruptedException {
        double start = clock.now();
        double total = order.workMinutes();
        while (true) {
            double done = Math.min(1.0, (clock.now() - start) / total);
            order.progress(done);
            if (done >= 1.0) {
                return;
            }
            long remainingMs = clock.toRealMillis((1.0 - done) * total);
            Thread.sleep(Math.max(1, Math.min(PROGRESS_STEP_MS, remainingMs)));
        }
    }
}

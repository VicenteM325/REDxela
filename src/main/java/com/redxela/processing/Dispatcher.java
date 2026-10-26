package com.redxela.processing;

import com.redxela.domain.Order;
import com.redxela.domain.OrderStatus;
import com.redxela.domain.ResourceType;
import com.redxela.resource.AcquisitionMode;
import com.redxela.resource.ResourceManager;
import com.redxela.scheduler.SchedulingPolicy;
import com.redxela.scheduler.WaitingList;
import com.redxela.simulation.EventLog;
import com.redxela.simulation.SimClock;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Planificador del centro: un hilo despertable que recorre la lista de espera en el orden de la
 * política activa y lanza un {@link OrderWorker} por cada pedido al que le alcanzan los recursos.
 *
 * <p>En modo {@link AcquisitionMode#ATOMIC} la política manda de verdad: se intenta tomar todos
 * los recursos del pedido en orden de política y, si no alcanzan, el pedido sigue en la lista de
 * espera. Para que un pedido menos prioritario no se cuele delante de uno más prioritario que
 * está esperando, los recursos de los pedidos bloqueados quedan <em>reservados</em>: solo pasan
 * los que no los necesitan (aprovecha recursos libres sin causar inanición del pedido de adelante).
 *
 * <p>En los modos incrementales los pedidos se admiten en orden de política y compiten por cada
 * recurso dentro de su semáforo (FIFO); sirven para demostrar la prevención por orden global y
 * para provocar el deadlock.
 */
public class Dispatcher {

    private static final long IDLE_WAKEUP_MS = 100;

    private final WaitingList waiting;
    private final ResourceManager resources;
    private final SimClock clock;
    private final EventLog log;
    private final int maxInFlight;
    private final long acquisitionPauseMs;
    private final StockGate stock;

    private final ExecutorService workers;
    private final Map<Long, OrderWorker> running = new ConcurrentHashMap<>();
    private final Map<Long, Order> runningOrders = new ConcurrentHashMap<>();
    private final List<Consumer<Order>> completionListeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger workerSeq = new AtomicInteger();

    private final ReentrantLock wakeLock = new ReentrantLock();
    private final Condition wake = wakeLock.newCondition();
    /** Hay una señal sin atender; evita perder despertares que ocurren mientras se despacha. */
    private boolean wakePending = true;

    private volatile SchedulingPolicy policy;
    private volatile AcquisitionMode mode = AcquisitionMode.ATOMIC;
    private volatile boolean active;
    private Thread thread;

    /**
     * @param maxInFlight        tope de pedidos en proceso a la vez (acota los hilos creados)
     * @param acquisitionPauseMs pausa real entre recursos en los modos de adquisición incremental
     * @param stock              puerta al inventario: reserva, confirma o devuelve existencias
     */
    public Dispatcher(WaitingList waiting, ResourceManager resources, SimClock clock, EventLog log,
                      SchedulingPolicy policy, int maxInFlight, long acquisitionPauseMs, StockGate stock) {
        this.waiting = waiting;
        this.resources = resources;
        this.clock = clock;
        this.log = log;
        this.policy = policy;
        this.maxInFlight = maxInFlight;
        this.acquisitionPauseMs = acquisitionPauseMs;
        this.stock = stock;
        this.workers = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "redxela-worker-" + workerSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Despachador sin control de existencias (todo pedido tiene stock); útil para pruebas aisladas. */
    public Dispatcher(WaitingList waiting, ResourceManager resources, SimClock clock, EventLog log,
                      SchedulingPolicy policy, int maxInFlight, long acquisitionPauseMs) {
        this(waiting, resources, clock, log, policy, maxInFlight, acquisitionPauseMs, StockGate.UNLIMITED);
    }

    /** Arranca el hilo despachador (idempotente). */
    public synchronized void start() {
        if (active) {
            return;
        }
        active = true;
        thread = new Thread(this::loop, "redxela-dispatcher");
        thread.setDaemon(true);
        thread.start();
    }

    /** Detiene el despachador e interrumpe a los workers (los pedidos en curso no se reencolan). */
    public synchronized void stop() {
        if (!active) {
            return;
        }
        active = false;
        signal();
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        workers.shutdownNow();
    }

    /** Pone un pedido nuevo en la lista de espera y despierta al despachador. */
    public void submit(Order order) {
        order.status(OrderStatus.EN_ESPERA);
        waiting.add(order);
        log.add("LLEGADA", "Pedido #" + order.id() + " (" + order.serviceLevel().displayName() + ", "
                + order.totalUnits() + " u., " + order.merchandiseTypeCodes() + ") en lista de espera");
        signal();
    }

    /** Despierta al despachador (nuevo pedido, recursos liberados, cambio de política…). */
    public void signal() {
        wakeLock.lock();
        try {
            wakePending = true;
            wake.signalAll();
        } finally {
            wakeLock.unlock();
        }
    }

    /**
     * Interrumpe un pedido en proceso: libera todo lo que tenga y lo devuelve a la lista de espera.
     * Es la acción con la que se resuelve un deadlock.
     *
     * @return {@code false} si el pedido no está en proceso
     */
    public boolean preempt(long orderId) {
        OrderWorker worker = running.get(orderId);
        return worker != null && worker.requestPreemption();
    }

    /** Se invoca cuando un pedido termina de procesarse (exitosamente), desde el hilo del worker. */
    public void addCompletionListener(Consumer<Order> listener) {
        completionListeners.add(listener);
    }

    public SchedulingPolicy policy() {
        return policy;
    }

    public void setPolicy(SchedulingPolicy policy) {
        this.policy = policy;
        log.add("POLITICA", "Política de atención: " + policy.name());
        signal();
    }

    public AcquisitionMode mode() {
        return mode;
    }

    public void setMode(AcquisitionMode mode) {
        this.mode = mode;
        log.add("MODO", "Modo de adquisición de recursos: " + mode);
        signal();
    }

    /** Pedidos que hoy tienen un worker (en proceso o esperando un recurso). */
    public List<Order> runningOrders() {
        return List.copyOf(runningOrders.values());
    }

    public WaitingList waitingList() {
        return waiting;
    }

    // ---------------------------------------------------------------- interno

    private void loop() {
        while (active) {
            // Espera una señal (o el tiempo de reposo, para reevaluar el envejecimiento). Si llegó
            // una señal mientras despachaba, wakePending ya está activa y no se duerme.
            wakeLock.lock();
            try {
                if (!wakePending) {
                    wake.await(IDLE_WAKEUP_MS, TimeUnit.MILLISECONDS);
                }
                wakePending = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                wakeLock.unlock();
            }
            try {
                dispatchRound();
            } catch (RuntimeException e) {
                // El despachador no debe morir por un error aislado
                log.add("ERROR", "Despachador: " + e);
            }
        }
    }

    private void dispatchRound() {
        AcquisitionMode currentMode = mode;
        Set<ResourceType> reserved = EnumSet.noneOf(ResourceType.class);
        for (Order order : waiting.ordered(policy, clock.now())) {
            if (running.size() >= maxInFlight) {
                return;
            }
            // Sin existencias el pedido espera al inventario; no bloquea ni reserva recursos de otros
            if (!stock.hasStock(order)) {
                markWaitingForStock(order);
                continue;
            }
            markReadyForResources(order);

            if (currentMode == AcquisitionMode.ATOMIC) {
                if (!Collections.disjoint(order.requiredResources(), reserved)) {
                    // Rebasaría a un pedido más prioritario que espera esos recursos: se deja pasar
                    // sin reservar nada (reservar también por estos bloquearía de forma transitiva
                    // a pedidos que no tienen conflicto real con el pedido bloqueado)
                    continue;
                }
                if (!resources.tryAcquireAll(order)) {
                    // Bloqueado por falta de recursos: los reserva para que nadie menos prioritario lo rebase
                    reserved.addAll(order.requiredResources());
                    continue;
                }
                if (!stock.tryReserve(order)) {
                    resources.releaseAll(order);          // otro pedido se llevó las existencias
                    markWaitingForStock(order);
                    continue;
                }
                if (!waiting.remove(order)) {
                    stock.release(order);                  // lo retiraron mientras tanto
                    resources.releaseAll(order);
                    continue;
                }
                launch(order, true, currentMode);
            } else {
                if (!stock.tryReserve(order)) {
                    markWaitingForStock(order);
                    continue;
                }
                if (!waiting.remove(order)) {
                    stock.release(order);
                    continue;
                }
                launch(order, false, currentMode);
            }
        }
    }

    private void markWaitingForStock(Order order) {
        if (order.status() != OrderStatus.ESPERANDO_INVENTARIO) {
            order.status(OrderStatus.ESPERANDO_INVENTARIO);
            log.add("SIN_STOCK", "Pedido #" + order.id() + " espera existencias en inventario");
        }
    }

    private void markReadyForResources(Order order) {
        if (order.status() == OrderStatus.ESPERANDO_INVENTARIO) {
            order.status(OrderStatus.EN_ESPERA);
            log.add("CON_STOCK", "Pedido #" + order.id() + " ya tiene existencias y vuelve a competir por recursos");
        }
    }

    private void launch(Order order, boolean holdsAll, AcquisitionMode launchMode) {
        OrderWorker worker = new OrderWorker(this, order, holdsAll, resources, clock, log, launchMode,
                acquisitionPauseMs);
        running.put(order.id(), worker);
        runningOrders.put(order.id(), order);
        order.status(OrderStatus.EN_PROCESO);
        try {
            workers.execute(worker);
        } catch (RejectedExecutionException e) {
            // Apagado en curso: devolver todo para no dejar recursos tomados
            running.remove(order.id());
            runningOrders.remove(order.id());
            resources.releaseAll(order);
            stock.release(order);
            waiting.add(order);
            order.status(OrderStatus.EN_ESPERA);
        }
    }

    /** El worker terminó de procesar: las unidades reservadas salen definitivamente del almacén. */
    void commitStock(Order order) {
        stock.commit(order);
    }

    /** Callback del worker al terminar (siempre, aun si falló o fue interrumpido). */
    void workerFinished(Order order, boolean requeue, boolean completed) {
        running.remove(order.id());
        runningOrders.remove(order.id());
        if (!completed) {
            stock.release(order);                 // preempción, fallo o cierre: las unidades vuelven a estar disponibles
        }
        if (requeue) {
            order.resetForRequeue();
            waiting.add(order);
        }
        if (completed) {
            for (Consumer<Order> listener : completionListeners) {
                try {
                    listener.accept(order);
                } catch (RuntimeException e) {
                    log.add("ERROR", "Listener de pedido completado: " + e);
                }
            }
        }
        signal();
    }
}

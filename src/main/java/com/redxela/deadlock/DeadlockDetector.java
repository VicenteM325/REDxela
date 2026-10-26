package com.redxela.deadlock;

import com.redxela.domain.Order;
import com.redxela.domain.OrderStatus;
import com.redxela.domain.ResourceType;
import com.redxela.processing.Dispatcher;
import com.redxela.resource.ResourceManager;
import com.redxela.resource.ResourceSnapshot;
import com.redxela.simulation.EventLog;
import com.redxela.simulation.SimClock;

import java.util.*;

/**
 * Detecta deadlocks y permite resolverlos manualmente (estrategia de "detección y recuperación").
 *
 * <p>Un hilo revisa periódicamente el estado de los recursos. Como cada recurso tiene varias
 * unidades, un ciclo en el grafo de espera no basta para afirmar que hay deadlock (otro titular
 * podría liberar una unidad); se usa el algoritmo de detección para recursos de múltiples
 * instancias: se asume que los pedidos que no esperan terminarán y liberarán lo que tienen, y se
 * van "terminando" los que esperan un recurso que ya hay disponible; los que no pueden terminar
 * nunca son los bloqueados entre sí.
 *
 * <p>Como las fotos de los recursos no se toman de forma atómica, un deadlock solo se confirma si
 * aparece con el mismo conjunto de pedidos en dos revisiones consecutivas (un deadlock real
 * persiste; una lectura inconsistente pasajera, no).
 */
public class DeadlockDetector {

    private final ResourceManager resources;
    private final Dispatcher dispatcher;
    private final SimClock clock;
    private final EventLog log;
    private final long periodMs;

    private final Object lock = new Object();
    private DeadlockReport confirmed;
    private Set<Long> lastCandidate = Set.of();

    private Thread thread;
    private volatile boolean running;

    public DeadlockDetector(ResourceManager resources, Dispatcher dispatcher, SimClock clock, EventLog log,
                            long periodMs) {
        this.resources = resources;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.log = log;
        this.periodMs = periodMs;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "redxela-deadlock-detector");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        thread.interrupt();
    }

    /** El deadlock confirmado en este momento, si lo hay. */
    public Optional<DeadlockReport> current() {
        synchronized (lock) {
            return Optional.ofNullable(confirmed);
        }
    }

    /**
     * Resuelve el deadlock: el pedido elegido conserva los recursos en disputa y continúa; los demás
     * pedidos bloqueados liberan lo que tienen y regresan a la lista de espera, sin volver a
     * competir hasta que el elegido termine.
     *
     * @throws IllegalStateException    si no hay un deadlock activo
     * @throws IllegalArgumentException si el pedido elegido no está entre los bloqueados
     */
    public void resolve(long keepOrderId) {
        DeadlockReport report;
        synchronized (lock) {
            report = confirmed;
        }
        if (report == null) {
            throw new IllegalStateException("No hay ningún deadlock activo");
        }
        if (!report.involves(keepOrderId)) {
            throw new IllegalArgumentException("El pedido " + keepOrderId + " no está entre los bloqueados");
        }
        Map<Long, Order> running = new HashMap<>();
        dispatcher.runningOrders().forEach(o -> running.put(o.id(), o));
        List<Long> victims = new ArrayList<>();
        for (DeadlockReport.BlockedOrder b : report.orders()) {
            if (b.orderId() == keepOrderId) {
                continue;
            }
            Order victim = running.get(b.orderId());
            if (victim != null) {
                dispatcher.deferUntilDone(victim, keepOrderId);
                dispatcher.preempt(victim.id());
                victims.add(victim.id());
            }
        }
        // Se descarta el reporte: si el conflicto persiste, el detector lo vuelve a confirmar
        synchronized (lock) {
            confirmed = null;
            lastCandidate = Set.of();
        }
        Order keep = running.get(keepOrderId);
        if (keep != null && keep.status() == OrderStatus.BLOQUEADO) {
            keep.status(OrderStatus.EN_PROCESO);
        }
        log.add("DEADLOCK_RESUELTO", "Deadlock resuelto: el pedido #" + keepOrderId + " continúa; los pedidos "
                + victims.stream().map(id -> "#" + id).toList() + " liberaron sus recursos y vuelven a la lista de espera");
    }

    // ------------------------------------------------------------------ revisión

    private void loop() {
        while (running) {
            try {
                check();
                Thread.sleep(periodMs);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log.add("ERROR", "Detector de deadlock: " + e);
            }
        }
    }

    /** Una revisión; visible para pruebas. */
    void check() {
        List<ResourceSnapshot> snapshots = resources.snapshot();
        Set<Long> candidate = findDeadlocked(snapshots);
        synchronized (lock) {
            if (candidate.isEmpty()) {
                lastCandidate = Set.of();
                if (confirmed != null) {
                    confirmed = null;
                }
                restoreBlockedStatus(Set.of());
                return;
            }
            if (candidate.equals(lastCandidate) && confirmed == null) {
                confirmed = buildReport(candidate, snapshots);
                markBlocked(candidate);
                log.add("DEADLOCK", confirmed.explanation());
            }
            lastCandidate = candidate;
            if (confirmed != null) {
                restoreBlockedStatus(candidate);
            }
        }
    }

    /**
     * Algoritmo de detección con recursos de múltiples instancias.
     *
     * @return ids de los pedidos que nunca podrán terminar (vacío si no hay deadlock)
     */
    static Set<Long> findDeadlocked(List<ResourceSnapshot> snapshots) {
        Map<ResourceType, Integer> work = new EnumMap<>(ResourceType.class);
        Map<Long, List<ResourceType>> held = new HashMap<>();
        Map<Long, ResourceType> waitingFor = new HashMap<>();
        Set<Long> all = new HashSet<>();
        for (ResourceSnapshot s : snapshots) {
            work.put(s.type(), s.available());
            for (long id : s.holders()) {
                held.computeIfAbsent(id, k -> new ArrayList<>()).add(s.type());
                all.add(id);
            }
            for (long id : s.waiters()) {
                waitingFor.putIfAbsent(id, s.type());
                all.add(id);
            }
        }
        // Los que no esperan nada avanzan y terminarán: devolverán lo que tienen
        for (long id : all) {
            if (!waitingFor.containsKey(id)) {
                for (ResourceType t : held.getOrDefault(id, List.of())) {
                    work.merge(t, 1, Integer::sum);
                }
            }
        }
        Set<Long> unfinished = new HashSet<>(waitingFor.keySet());
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Iterator<Long> it = unfinished.iterator(); it.hasNext(); ) {
                long id = it.next();
                if (work.getOrDefault(waitingFor.get(id), 0) >= 1) {
                    // Puede obtener lo que espera, terminar y devolver todo lo que tiene
                    for (ResourceType t : held.getOrDefault(id, List.of())) {
                        work.merge(t, 1, Integer::sum);
                    }
                    it.remove();
                    progress = true;
                }
            }
        }
        // Un pedido que espera sin retener nada no participa en un ciclo: solo espera
        unfinished.removeIf(id -> held.getOrDefault(id, List.of()).isEmpty());
        return unfinished;
    }

    private DeadlockReport buildReport(Set<Long> ids, List<ResourceSnapshot> snapshots) {
        Map<Long, Order> byId = new HashMap<>();
        dispatcher.runningOrders().forEach(o -> byId.put(o.id(), o));
        Map<ResourceType, ResourceSnapshot> byType = new EnumMap<>(ResourceType.class);
        snapshots.forEach(s -> byType.put(s.type(), s));

        List<DeadlockReport.BlockedOrder> blocked = new ArrayList<>();
        Set<String> disputed = new LinkedHashSet<>();
        StringBuilder text = new StringBuilder("Deadlock: espera circular entre los pedidos ");
        List<Long> sorted = ids.stream().sorted().toList();
        text.append(sorted.stream().map(id -> "#" + id).toList()).append(". ");
        for (long id : sorted) {
            List<String> holds = new ArrayList<>();
            ResourceType waits = null;
            for (ResourceSnapshot s : snapshots) {
                if (s.holders().contains(id)) {
                    holds.add(s.type().name());
                }
                if (s.waiters().contains(id) && waits == null) {
                    waits = s.type();
                }
            }
            List<Long> blockers = waits == null ? List.of() : byType.get(waits).holders().stream()
                    .filter(ids::contains).sorted().toList();
            Order o = byId.get(id);
            blocked.add(new DeadlockReport.BlockedOrder(id,
                    o == null ? "?" : o.serviceLevel().displayName(), o == null ? 0 : o.totalUnits(),
                    holds, waits == null ? null : waits.name(), blockers));
            if (waits != null) {
                disputed.add(waits.name());
                text.append("El pedido #").append(id).append(" retiene ").append(holds).append(" y espera ")
                        .append(waits.name()).append(", que tienen los pedidos ")
                        .append(blockers.stream().map(b -> "#" + b).toList()).append(". ");
            }
        }
        text.append("Ninguno puede avanzar hasta que alguno ceda sus recursos.");
        return new DeadlockReport(clock.now(), blocked, List.copyOf(disputed), text.toString());
    }

    private void markBlocked(Set<Long> ids) {
        for (Order o : dispatcher.runningOrders()) {
            if (ids.contains(o.id())) {
                o.status(OrderStatus.BLOQUEADO);
            }
        }
    }

    /** Los pedidos marcados BLOQUEADO que ya no lo están vuelven a EN_PROCESO. */
    private void restoreBlockedStatus(Set<Long> stillBlocked) {
        for (Order o : dispatcher.runningOrders()) {
            if (o.status() == OrderStatus.BLOQUEADO && !stillBlocked.contains(o.id())) {
                o.status(OrderStatus.EN_PROCESO);
            }
        }
    }
}

package com.redxela.deadlock;

import com.redxela.domain.*;
import com.redxela.processing.Dispatcher;
import com.redxela.resource.AcquisitionMode;
import com.redxela.resource.ResourceManager;
import com.redxela.resource.ResourceSnapshot;
import com.redxela.scheduler.AgingPolicy;
import com.redxela.scheduler.WaitingList;
import com.redxela.simulation.EventLog;
import com.redxela.simulation.SimClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static com.redxela.domain.ResourceType.*;
import static com.redxela.testsupport.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class DeadlockDetectorTest {

    // ------------------------------------------------ algoritmo puro (sin hilos)

    /** Fotos de los seis recursos con lo indicado; el resto, libres. */
    private static List<ResourceSnapshot> snaps(Object[]... specs) {
        Map<ResourceType, ResourceSnapshot> map = new EnumMap<>(ResourceType.class);
        for (ResourceType t : ResourceType.values()) {
            map.put(t, new ResourceSnapshot(t, t.capacity(), t.capacity(), List.of(), List.of()));
        }
        for (Object[] spec : specs) {
            ResourceType t = (ResourceType) spec[0];
            @SuppressWarnings("unchecked") List<Long> holders = (List<Long>) spec[1];
            @SuppressWarnings("unchecked") List<Long> waiters = (List<Long>) spec[2];
            map.put(t, new ResourceSnapshot(t, t.capacity(), t.capacity() - holders.size(), holders, waiters));
        }
        return new ArrayList<>(map.values());
    }

    @Test
    void detectaElCicloClasicoDeDosPedidos() {
        // 1 tiene ESCANER y espera AREA_CARGA; 2 y 3 tienen las dos AREA_CARGA... pero el escáner solo lo tiene 1
        // Ciclo mínimo con las capacidades reales: 1 y 2 sobre ESCANER(1) y CONTROL_CALIDAD llenos
        var s = snaps(
                new Object[]{ESCANER, List.of(1L), List.of(2L)},
                new Object[]{CONTROL_CALIDAD, List.of(2L, 3L, 4L, 5L), List.of(1L)});
        // 1 retiene ESCANER y espera CC (lleno: 2,3,4,5); 2 retiene CC y espera ESCANER.
        // 3,4,5 retienen CC y no esperan nada: terminan y liberan CC -> 1 puede avanzar -> libera ESCANER -> 2 avanza
        assertTrue(DeadlockDetector.findDeadlocked(s).isEmpty(), "3, 4 y 5 terminarán y destraban el ciclo");

        // Si todos los titulares de CC también esperan, sí hay deadlock
        var s2 = snaps(
                new Object[]{ESCANER, List.of(1L), List.of(2L, 3L, 4L, 5L)},
                new Object[]{CONTROL_CALIDAD, List.of(2L, 3L, 4L, 5L), List.of(1L)});
        assertEquals(Set.of(1L, 2L, 3L, 4L, 5L), DeadlockDetector.findDeadlocked(s2));
    }

    @Test
    void detectaElEscenarioPesadosVsVoluminosos() {
        var s = snaps(
                new Object[]{MONTACARGAS, List.of(1L, 2L, 3L), List.of(4L, 5L)},
                new Object[]{AREA_CARGA, List.of(4L, 5L), List.of(1L, 2L, 3L)},
                new Object[]{ENCARGADO, List.of(1L, 2L, 3L), List.of()},
                new Object[]{EMPAQUE, List.of(4L, 5L), List.of()});
        assertEquals(Set.of(1L, 2L, 3L, 4L, 5L), DeadlockDetector.findDeadlocked(s));
    }

    @Test
    void unCicloAparenteConUnidadesQueSeLiberanNoEsDeadlock() {
        // 1 retiene MONTACARGAS y espera AREA_CARGA; 4 retiene AREA_CARGA y espera MONTACARGAS,
        // pero 9 (que NO espera nada) también retiene un montacargas: al terminar lo libera
        var s = snaps(
                new Object[]{MONTACARGAS, List.of(1L, 9L), List.of(4L)},
                new Object[]{AREA_CARGA, List.of(4L, 8L), List.of(1L)});
        // MONTACARGAS tiene 1 libre (cap 3, 2 titulares): 4 ya podría avanzar
        assertTrue(DeadlockDetector.findDeadlocked(s).isEmpty());

        var s2 = snaps(
                new Object[]{MONTACARGAS, List.of(1L, 2L, 9L), List.of(4L)},
                new Object[]{AREA_CARGA, List.of(4L, 5L), List.of(1L, 2L)});
        // 9 no espera: termina y libera 1 montacargas -> 4 avanza -> libera AREA -> 1 y 2 avanzan
        assertTrue(DeadlockDetector.findDeadlocked(s2).isEmpty());
    }

    @Test
    void unPedidoQueSoloEsperaSinRetenerNadaNoEstaEnDeadlock() {
        var s = snaps(new Object[]{ESCANER, List.of(1L), List.of(2L)});
        assertTrue(DeadlockDetector.findDeadlocked(s).isEmpty());
        assertTrue(DeadlockDetector.findDeadlocked(snaps()).isEmpty());
    }

    @Test
    void losLotesDeRecepcionNuncaCuentanComoDeadlock() {
        // Un lote (id negativo) espera el escáner que tiene un pedido en marcha
        var s = snaps(new Object[]{ESCANER, List.of(7L), List.of(-3L)});
        assertTrue(DeadlockDetector.findDeadlocked(s).isEmpty());
    }

    // ---------------------------------------- integración con hilos reales

    private final SimClock clock = new SimClock(20);
    private final EventLog log = new EventLog(clock, 500);
    private final ResourceManager rm = new ResourceManager();
    private final WaitingList waiting = new WaitingList();
    private Dispatcher dispatcher;
    private DeadlockDetector detector;

    private void startSystem(long detectorPeriodMs) {
        dispatcher = new Dispatcher(waiting, rm, clock, log, new AgingPolicy(), 50, 100);
        detector = new DeadlockDetector(rm, dispatcher, clock, log, detectorPeriodMs);
        dispatcher.start();
        detector.start();
    }

    @AfterEach
    void tearDown() {
        if (detector != null) detector.stop();
        if (dispatcher != null) dispatcher.stop();
    }

    private static void await(BooleanSupplier c, long ms, String what) throws InterruptedException {
        long limit = System.currentTimeMillis() + ms;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > limit) fail("Tiempo agotado: " + what);
            Thread.sleep(5);
        }
    }

    private List<Order> armarDeadlock() {
        dispatcher.setMode(AcquisitionMode.NATURAL);
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 3; i++) orders.add(order(PESADO, ServiceLevel.EXPRES, 4));
        for (int i = 0; i < 2; i++) orders.add(order(VOLUMINOSO, ServiceLevel.EXPRES, 4));
        orders.forEach(dispatcher::submit);
        return orders;
    }

    @Test
    void confirmaElDeadlockLoDescribeYLoResuelveManualmente() throws Exception {
        startSystem(25);
        List<Order> orders = armarDeadlock();

        await(() -> detector.current().isPresent(), 8000, "deadlock confirmado");
        DeadlockReport report = detector.current().orElseThrow();
        assertEquals(5, report.orders().size());
        assertTrue(report.resources().containsAll(List.of("MONTACARGAS", "AREA_CARGA")), report.resources().toString());
        assertTrue(report.explanation().contains("espera circular"));
        for (DeadlockReport.BlockedOrder b : report.orders()) {
            assertFalse(b.holds().isEmpty(), "cada pedido bloqueado retiene algo");
            assertNotNull(b.waitsFor());
            assertFalse(b.blockedBy().isEmpty(), "y espera algo que tiene otro bloqueado");
        }
        await(() -> orders.stream().allMatch(o -> o.status() == OrderStatus.BLOQUEADO), 2000, "pedidos marcados BLOQUEADO");

        // Resolución manual: el primer pesado continúa, los otros cuatro regresan a la lista de espera
        Order keep = orders.get(0);
        detector.resolve(keep.id());
        assertTrue(detector.current().isEmpty());

        await(() -> orders.stream().allMatch(o -> o.status() == OrderStatus.COMPLETADO), 30_000, "los cinco completan");
        for (Order o : orders) {
            if (o != keep) {
                assertEquals(1, o.requeues(), "los cedentes se reencolaron una vez");
                assertTrue(o.startedAt() >= keep.finishedAt() - 1.0,
                        "un pedido que cedió no vuelve a competir hasta que el elegido termina");
            }
        }
        assertEquals(0, keep.requeues());
        assertTrue(log.recent(100).stream().anyMatch(e -> e.type().equals("DEADLOCK")));
        assertTrue(log.recent(100).stream().anyMatch(e -> e.type().equals("DEADLOCK_RESUELTO")));
        assertTrue(rm.snapshot().stream().allMatch(s -> s.available() == s.capacity()));
    }

    @Test
    void resolverSinDeadlockOConUnPedidoAjenoSeRechaza() throws Exception {
        startSystem(25);
        assertThrows(IllegalStateException.class, () -> detector.resolve(1));

        List<Order> orders = armarDeadlock();
        await(() -> detector.current().isPresent(), 8000, "deadlock confirmado");
        assertThrows(IllegalArgumentException.class, () -> detector.resolve(987_654));
        assertTrue(detector.current().isPresent(), "un intento inválido no cambia el estado");
        detector.resolve(orders.get(3).id());           // también se puede elegir a un voluminoso
        await(() -> orders.stream().allMatch(o -> o.status() == OrderStatus.COMPLETADO), 30_000, "completan");
    }

    /** Con adquisición atómica u ordenada el deadlock es imposible: el detector jamás debe reportar uno. */
    @Test
    void sinFalsosPositivosConPrevencionBajoEstres() throws Exception {
        startSystem(5);
        for (AcquisitionMode mode : List.of(AcquisitionMode.ATOMIC, AcquisitionMode.ORDERED)) {
            dispatcher.setMode(mode);
            AtomicBoolean reportado = new AtomicBoolean();
            Thread monitor = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    if (detector.current().isPresent()) reportado.set(true);
                }
            });
            monitor.start();
            List<Order> orders = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                orders.add(order(i % 2 == 0 ? PESADO : VOLUMINOSO, ServiceLevel.ESTANDAR, 2 + i % 4));
            }
            orders.forEach(dispatcher::submit);
            await(() -> orders.stream().allMatch(o -> o.status() == OrderStatus.COMPLETADO), 60_000, "completan en " + mode);
            monitor.interrupt();
            assertFalse(reportado.get(), "falso positivo en modo " + mode);
        }
    }
}

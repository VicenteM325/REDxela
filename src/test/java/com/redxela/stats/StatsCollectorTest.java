package com.redxela.stats;

import com.redxela.domain.Order;
import com.redxela.domain.ResourceType;
import com.redxela.domain.ServiceLevel;
import com.redxela.resource.ResourceManager;
import com.redxela.simulation.SimClock;
import org.junit.jupiter.api.Test;

import static com.redxela.testsupport.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class StatsCollectorTest {

    private final SimClock clock = new SimClock(10);
    private final ResourceManager rm = new ResourceManager();
    private final StatsCollector stats = new StatsCollector(clock, rm, 5);

    /** Pedido ya completado: llegó en {@code created}, inició en {@code start} y terminó en {@code end}. */
    private static Order done(ServiceLevel level, double created, double start, double end) {
        Order o = order(FRAGIL, level, 4, created);
        o.markStarted(start);
        o.finishedAt(end);
        return o;
    }

    @Test
    void sinPedidosTodoEstaEnCero() {
        var s = stats.snapshot();
        assertEquals(0, s.completed());
        assertEquals(0, s.averageWaitMinutes());
        assertEquals(0, s.slaCompliancePct());
        assertEquals(5, s.levels().size());
        assertEquals(6, s.resources().size());
    }

    @Test
    void calculaPromediosPorNivelYGeneral() {
        stats.onCompleted(done(ServiceLevel.ESTANDAR, 0, 10, 14));     // espera 10, proceso 4  (objetivo 30: cumple)
        stats.onCompleted(done(ServiceLevel.ESTANDAR, 5, 45, 51));     // espera 40, proceso 6  (no cumple)
        stats.onCompleted(done(ServiceLevel.EXPRES, 0, 0.5, 2.5));     // espera 0.5, proceso 2 (tolerancia 1 min: cumple)

        var s = stats.snapshot();
        assertEquals(3, s.completed());
        assertEquals((10 + 40 + 0.5) / 3, s.averageWaitMinutes(), 1e-9);
        assertEquals((4 + 6 + 2) / 3.0, s.averageProcessingMinutes(), 1e-9);
        assertEquals(100.0 * 2 / 3, s.slaCompliancePct(), 1e-9);

        var estandar = s.levels().get(2);
        assertEquals(2, estandar.completed());
        assertEquals(25, estandar.averageWaitMinutes(), 1e-9);
        assertEquals(5, estandar.averageProcessingMinutes(), 1e-9);
        assertEquals(50, estandar.slaCompliancePct(), 1e-9);
        assertEquals(30, estandar.targetMinutes());
        assertEquals(1.0, s.levels().get(0).targetMinutes(), "el Exprés usa la tolerancia mínima");
    }

    @Test
    void elMuestreoPromediaLaOcupacionDeLosRecursosEnElTiempo() throws Exception {
        Order a = order(DOCUMENTOS, ServiceLevel.ESTANDAR, 1);
        // Escáner (capacidad 1) ocupado la mitad del tiempo muestreado
        stats.start();
        Thread.sleep(150);
        assertTrue(rm.pool(ResourceType.ESCANER).tryAcquire(a));
        Thread.sleep(150);
        var busy = stats.snapshot();
        rm.pool(ResourceType.ESCANER).release(a);
        stats.stop();

        var scanner = busy.resources().stream().filter(r -> r.type() == ResourceType.ESCANER).findFirst().orElseThrow();
        assertEquals(100.0, scanner.currentPct(), 1e-9);
        assertTrue(scanner.averagePct() > 15 && scanner.averagePct() < 85,
                "ocupado ~la mitad del tiempo: " + scanner.averagePct());
        var empaque = busy.resources().stream().filter(r -> r.type() == ResourceType.EMPAQUE).findFirst().orElseThrow();
        assertEquals(0, empaque.averagePct(), 1e-9);
    }

    @Test
    void resetPoneTodoEnCero() {
        stats.onCompleted(done(ServiceLevel.ECONOMICO, 0, 5, 9));
        assertEquals(1, stats.snapshot().completed());
        stats.reset();
        assertEquals(0, stats.snapshot().completed());
    }

    @Test
    void esSeguroConHilosConcurrentes() throws Exception {
        Thread[] ts = new Thread[8];
        for (int t = 0; t < ts.length; t++) {
            ts[t] = new Thread(() -> {
                for (int i = 0; i < 500; i++) {
                    stats.onCompleted(done(ServiceLevel.ESTANDAR, 0, 5, 9));
                    stats.snapshot();
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        assertEquals(4000, stats.snapshot().completed());
        assertEquals(5, stats.snapshot().averageWaitMinutes(), 1e-9);
    }
}

package com.redxela.simulation;

import com.redxela.persistence.CatalogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:comparator;DB_CLOSE_DELAY=-1")
class PolicyComparatorTest {

    @Autowired
    CatalogRepository catalog;
    @Autowired
    WorkEstimator estimator;

    private PolicyComparator.Status run(PolicyComparator.Params params) throws Exception {
        PolicyComparator comparator = new PolicyComparator(catalog, estimator);
        assertTrue(comparator.start(params));
        assertFalse(comparator.start(params), "no se permiten dos comparaciones a la vez");
        long limite = System.currentTimeMillis() + 60_000;
        while ("RUNNING".equals(comparator.status().state()) && System.currentTimeMillis() < limite) Thread.sleep(50);
        return comparator.status();
    }

    private static PolicyComparator.PolicyResult of(PolicyComparator.Status s, String id) {
        return s.results().stream().filter(r -> r.policyId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void sometePoliticasALaMismaCargaYReportaMetricasCoherentes() throws Exception {
        var s = run(new PolicyComparator.Params(60, 5.0, 11, 10));
        assertEquals("DONE", s.state(), s.error());
        assertEquals(4, s.results().size());
        assertEquals(4, s.policiesDone());

        int llegadas = s.results().get(0).arrived();
        assertTrue(llegadas > 100);
        for (var r : s.results()) {
            assertEquals(llegadas, r.arrived(), "todas las políticas reciben exactamente la misma carga: " + r.policyId());
            assertTrue(r.completed() > 0 && r.completed() <= r.started() && r.started() <= r.arrived());
            assertTrue(r.slaCompliancePct() >= 0 && r.slaCompliancePct() <= 100);
            assertTrue(r.averageWaitMinutes() >= 0 && r.maxWaitMinutes() >= r.averageWaitMinutes());
            assertEquals(5, r.levels().size());
            assertEquals(llegadas, r.levels().stream().mapToInt(PolicyComparator.LevelResult::arrived).sum());
        }
        assertNotNull(s.best());
    }

    /** Bajo sobrecarga la prioridad estricta debe atender al Exprés mucho antes que el orden de llegada. */
    @Test
    void laPrioridadEstrictaFavoreceAlNivelExpresFrenteAFcfs() throws Exception {
        var s = run(new PolicyComparator.Params(60, 6.0, 3, 10));
        assertEquals("DONE", s.state(), s.error());
        double estricta = of(s, "STRICT_PRIORITY").levels().get(0).averageWaitMinutes();
        double fcfs = of(s, "FCFS").levels().get(0).averageWaitMinutes();
        assertTrue(estricta < fcfs, "espera del Exprés: estricta=" + estricta + " fcfs=" + fcfs);
    }

    @Test
    void laCargaEsReproducibleConLaMismaSemilla() throws Exception {
        var a = run(new PolicyComparator.Params(30, 4.0, 99, 5));
        var b = run(new PolicyComparator.Params(30, 4.0, 99, 5));
        assertEquals(a.results().get(0).arrived(), b.results().get(0).arrived());
        var c = run(new PolicyComparator.Params(30, 4.0, 100, 5));
        assertNotEquals(a.results().get(0).arrived() * 1000 + a.results().get(0).levels().get(0).arrived(),
                c.results().get(0).arrived() * 1000 + c.results().get(0).levels().get(0).arrived());
    }

    @Test
    void validaLosParametros() {
        assertThrows(IllegalArgumentException.class, () -> new PolicyComparator.Params(5, 4, 1, 20));
        assertThrows(IllegalArgumentException.class, () -> new PolicyComparator.Params(60, 0, 1, 20));
        assertThrows(IllegalArgumentException.class, () -> new PolicyComparator.Params(60, 4, 1, 1));
    }
}

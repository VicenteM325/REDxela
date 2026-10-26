package com.redxela.scheduler;

import com.redxela.domain.Order;
import com.redxela.domain.ServiceLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static com.redxela.testsupport.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class SchedulingPolicyTest {

    private static List<Long> ids(List<Order> orders) {
        return orders.stream().map(Order::id).toList();
    }

    private static List<Long> sorted(SchedulingPolicy p, double now, Order... orders) {
        WaitingList w = new WaitingList();
        for (Order o : orders) w.add(o);
        return ids(w.ordered(p, now));
    }

    @Test
    void prioridadEstrictaOrdenaPorNivelYLuegoPorLlegada() {
        Order eco = order(FRAGIL, ServiceLevel.ECONOMICO, 1, 0);
        Order std1 = order(FRAGIL, ServiceLevel.ESTANDAR, 5, 1);
        Order std2 = order(FRAGIL, ServiceLevel.ESTANDAR, 5, 2);
        Order exp = order(FRAGIL, ServiceLevel.EXPRES, 50, 3);

        assertEquals(List.of(exp.id(), std1.id(), std2.id(), eco.id()),
                sorted(new StrictPriorityPolicy(), 10, eco, std2, std1, exp));
    }

    @Test
    void menorCantidadPrimeroIgnoraElNivel() {
        Order grande = order(FRAGIL, ServiceLevel.EXPRES, 30, 0);
        Order chico = order(FRAGIL, ServiceLevel.ECONOMICO, 1, 1);
        Order mediano = order(FRAGIL, ServiceLevel.ESTANDAR, 10, 2);

        assertEquals(List.of(chico.id(), mediano.id(), grande.id()),
                sorted(new ShortestOrderFirstPolicy(), 5, grande, chico, mediano));
    }

    @Test
    void fcfsRespetaSoloLaLlegada() {
        Order a = order(FRAGIL, ServiceLevel.ECONOMICO, 30, 0);
        Order b = order(FRAGIL, ServiceLevel.EXPRES, 1, 1);
        assertEquals(List.of(a.id(), b.id()), sorted(new FcfsPolicy(), 5, b, a));
    }

    @Test
    void elEnvejecimientoPromueveAlPedidoQueLlevaMuchoEsperando() {
        AgingPolicy aging = new AgingPolicy(10);
        Order viejoEco = order(FRAGIL, ServiceLevel.ECONOMICO, 5, 0);   // nivel 5, llegó en el minuto 0

        // Si el Exprés llega poco después, todavía gana: eco vale 5 - 0.5 = 4.5 > 1
        Order expTemprano = order(FRAGIL, ServiceLevel.EXPRES, 5, 5);
        assertEquals(expTemprano.id(), sorted(aging, 5, viejoEco, expTemprano).get(0));

        // Un Exprés recién llegado en el minuto 100: el eco ya espera 100 min y vale 5 - 10 = -5 < 1
        Order expTarde = order(FRAGIL, ServiceLevel.EXPRES, 5, 100);
        assertEquals(List.of(viejoEco.id(), expTarde.id()), sorted(aging, 100.5, viejoEco, expTarde));

        // La prioridad estricta, en cambio, nunca lo promueve (inanición)
        assertEquals(expTarde.id(), sorted(new StrictPriorityPolicy(), 100.5, viejoEco, expTarde).get(0));
    }

    @Test
    void elDesempateEsDeterminista() {
        Order a = order(FRAGIL, ServiceLevel.ESTANDAR, 5, 1);
        Order b = order(FRAGIL, ServiceLevel.ESTANDAR, 5, 1);
        for (SchedulingPolicy p : SchedulingPolicies.all()) {
            assertEquals(List.of(a.id(), b.id()), sorted(p, 10, b, a), p.id());
        }
    }

    @Test
    void elCatalogoExponeLasPoliticasPorId() {
        assertTrue(SchedulingPolicies.all().size() >= 3);
        assertTrue(SchedulingPolicies.byId("aging").isPresent());
        assertTrue(SchedulingPolicies.byId("no-existe").isEmpty());
        for (SchedulingPolicy p : SchedulingPolicies.all()) {
            assertFalse(p.description().isBlank());
        }
    }

    @Test
    void listaDeEsperaEsSeguraConHilosConcurrentes() throws Exception {
        WaitingList w = new WaitingList();
        ExecutorService ex = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            fs.add(ex.submit(() -> {
                for (int i = 0; i < 200; i++) {
                    Order o = order(FRAGIL, ServiceLevel.ESTANDAR, 1);
                    w.add(o);
                    w.ordered(new AgingPolicy(), 1); // lectura concurrente con escrituras
                    if (i % 2 == 0) assertTrue(w.remove(o));
                }
                return null;
            }));
        }
        for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        ex.shutdown();
        assertEquals(8 * 100, w.size());
    }
}

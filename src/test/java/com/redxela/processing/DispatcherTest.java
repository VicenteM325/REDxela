package com.redxela.processing;

import com.redxela.domain.Order;
import com.redxela.domain.OrderStatus;
import com.redxela.domain.ResourceType;
import com.redxela.domain.ServiceLevel;
import com.redxela.resource.AcquisitionMode;
import com.redxela.resource.ResourceManager;
import com.redxela.resource.ResourceSnapshot;
import com.redxela.scheduler.*;
import com.redxela.simulation.EventLog;
import com.redxela.simulation.SimClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static com.redxela.testsupport.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class DispatcherTest {

    /** Reloj rápido: 1 minuto simulado = 20 ms reales; un pedido de 4 u. dura 1 min = 20 ms. */
    private final SimClock clock = new SimClock(20);
    private final EventLog log = new EventLog(clock, 500);
    private final ResourceManager rm = new ResourceManager();
    private final WaitingList waiting = new WaitingList();
    private final List<Order> completed = new CopyOnWriteArrayList<>();
    private Dispatcher dispatcher;

    private Dispatcher create(SchedulingPolicy policy, long pauseMs) {
        dispatcher = new Dispatcher(waiting, rm, clock, log, policy, 50, pauseMs);
        dispatcher.addCompletionListener(completed::add);
        dispatcher.start();
        return dispatcher;
    }

    @AfterEach
    void tearDown() {
        if (dispatcher != null) dispatcher.stop();
    }

    private static void await(BooleanSupplier cond, long timeoutMs, String what) throws InterruptedException {
        long limit = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > limit) fail("Tiempo agotado esperando: " + what);
            Thread.sleep(5);
        }
    }

    private void assertAllResourcesFree() {
        for (ResourceSnapshot s : rm.snapshot()) {
            assertEquals(s.capacity(), s.available(), "recurso no liberado: " + s.type());
            assertTrue(s.holders().isEmpty(), s.type() + " con titulares " + s.holders());
        }
    }

    @Test
    void completaTodosLosPedidosSinExcederNuncaLaCapacidad() throws Exception {
        create(new AgingPolicy(), 0);
        AtomicBoolean excedido = new AtomicBoolean();
        Thread monitor = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                for (ResourceSnapshot s : rm.snapshot()) {
                    if (s.holders().size() > s.capacity()) excedido.set(true);
                }
            }
        });
        monitor.start();

        var tipos = List.of(FRAGIL, PESADO, VOLUMINOSO, DOCUMENTOS, ELECTRONICO);
        List<Order> pedidos = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            Order o = order(tipos.get(i % tipos.size()), ServiceLevel.values()[i % 5], 1 + i % 8);
            pedidos.add(o);
            dispatcher.submit(o);
        }
        await(() -> completed.size() == 60, 30_000, "60 pedidos completados");
        monitor.interrupt();

        assertFalse(excedido.get(), "se excedió la capacidad de algún recurso");
        assertTrue(pedidos.stream().allMatch(o -> o.status() == OrderStatus.COMPLETADO));
        assertEquals(0, waiting.size());
        await(() -> dispatcher.runningOrders().isEmpty(), 5000, "workers terminados");
        assertAllResourcesFree();
    }

    @Test
    void laPrioridadEstrictaAtiendePrimeroAlNivelMasAlto() throws Exception {
        create(new StrictPriorityPolicy(), 0);
        Order ocupa = order(DOCUMENTOS, ServiceLevel.ESTANDAR, 60);      // ocupa el escáner ~15 min = 300 ms
        dispatcher.submit(ocupa);
        await(() -> ocupa.status() == OrderStatus.EN_PROCESO, 2000, "pedido que ocupa el escáner");

        Order eco = order(DOCUMENTOS, ServiceLevel.ECONOMICO, 4, clock.now());
        dispatcher.submit(eco);
        Order exp = order(DOCUMENTOS, ServiceLevel.EXPRES, 4, clock.now());
        dispatcher.submit(exp);

        await(() -> completed.size() == 3, 10_000, "3 pedidos");
        assertTrue(exp.firstStartedAt() < eco.firstStartedAt(), "el Exprés debe iniciar antes que el Económico");
    }

    @Test
    void unPedidoBloqueadoReservaSusRecursosPeroNoFrenaALosQueNoLosNecesitan() throws Exception {
        create(new StrictPriorityPolicy(), 0);
        Order ocupa = order(DOCUMENTOS, ServiceLevel.ESTANDAR, 80);      // retiene ESCANER + ENCARGADO
        dispatcher.submit(ocupa);
        await(() -> ocupa.status() == OrderStatus.EN_PROCESO, 2000, "escáner ocupado");

        // B (Exprés) necesita ESCANER: queda bloqueado y reserva ESCANER, CONTROL_CALIDAD y EMPAQUE
        Order b = order(ELECTRONICO, ServiceLevel.EXPRES, 4, clock.now());
        // C (Económico) comparte CC y EMPAQUE con B: no debe colarse delante aunque hoy estén libres
        Order c = order(FRAGIL, ServiceLevel.ECONOMICO, 4, clock.now());
        // D (Económico) no comparte nada con B: puede aprovechar los recursos libres de inmediato
        Order d = order(PESADO, ServiceLevel.ECONOMICO, 4, clock.now());
        dispatcher.submit(b);
        dispatcher.submit(c);
        dispatcher.submit(d);

        await(() -> d.status() == OrderStatus.EN_PROCESO || d.status() == OrderStatus.COMPLETADO, 2000, "D avanza");
        assertEquals(OrderStatus.EN_ESPERA, b.status());
        assertEquals(OrderStatus.EN_ESPERA, c.status(), "C no puede rebasar al Exprés bloqueado");

        await(() -> completed.size() == 4, 20_000, "todos completados");
        // B y C pueden arrancar en la misma ronda (cada uno marca su inicio desde su propio hilo, de modo
        // que difieren en microsegundos). Sin la reserva, C habría iniciado ~20 min simulados ANTES que B.
        assertTrue(c.firstStartedAt() >= b.firstStartedAt() - 1.0,
                "C no debe iniciar antes que B: B=" + b.firstStartedAt() + " C=" + c.firstStartedAt());
    }

    @Test
    void cambiarDePoliticaReordenaLaListaDeEspera() throws Exception {
        create(new FcfsPolicy(), 0);
        Order ocupa = order(DOCUMENTOS, ServiceLevel.ESTANDAR, 40);
        dispatcher.submit(ocupa);
        await(() -> ocupa.status() == OrderStatus.EN_PROCESO, 2000, "escáner ocupado");
        Order primero = order(DOCUMENTOS, ServiceLevel.ECONOMICO, 4, 1);
        Order segundo = order(DOCUMENTOS, ServiceLevel.EXPRES, 4, 2);
        dispatcher.submit(primero);
        dispatcher.submit(segundo);

        assertEquals(primero.id(), waiting.ordered(dispatcher.policy(), clock.now()).get(0).id());
        dispatcher.setPolicy(new StrictPriorityPolicy());
        assertEquals(segundo.id(), waiting.ordered(dispatcher.policy(), clock.now()).get(0).id());
    }

    @Test
    void preemptarLiberaLosRecursosYReencolaElPedido() throws Exception {
        create(new AgingPolicy(), 0);
        Order largo = order(FRAGIL, ServiceLevel.ESTANDAR, 120);   // ~30 min = 600 ms
        dispatcher.submit(largo);
        await(() -> largo.status() == OrderStatus.EN_PROCESO && largo.progress() > 0.05, 2000, "en proceso");

        assertTrue(dispatcher.preempt(largo.id()));
        await(() -> largo.requeues() == 1, 2000, "reencolado");
        // Tras reencolarse vuelve a ejecutarse desde cero y termina
        await(() -> largo.status() == OrderStatus.COMPLETADO, 10_000, "completado tras reintento");
        assertEquals(1, largo.requeues());
        assertFalse(dispatcher.preempt(largo.id()), "ya no está en proceso");
        await(() -> dispatcher.runningOrders().isEmpty(), 2000, "sin workers");
        assertAllResourcesFree();
    }

    @Test
    void elOrdenGlobalDeRecursosNoSeBloqueaConTiposEnConflicto() throws Exception {
        create(new AgingPolicy(), 5);
        dispatcher.setMode(AcquisitionMode.ORDERED);
        int n = 40;
        for (int i = 0; i < n; i++) {
            dispatcher.submit(order(i % 2 == 0 ? PESADO : VOLUMINOSO, ServiceLevel.ESTANDAR, 2 + i % 6));
        }
        await(() -> completed.size() == n, 30_000, "todos completados con orden global");
        await(() -> dispatcher.runningOrders().isEmpty(), 2000, "sin workers");
        assertAllResourcesFree();
    }

    /**
     * Demostración deliberada del deadlock (modo NATURAL): 3 pedidos pesados retienen los 3
     * montacargas y piden área de carga; 2 voluminosos retienen las 2 áreas y piden montacargas.
     * Espera circular: nadie avanza. Preemptar a un pesado rompe el ciclo y el sistema continúa.
     */
    @Test
    void modoNaturalProvocaDeadlockYLaPreempcionLoResuelve() throws Exception {
        create(new AgingPolicy(), 100);
        dispatcher.setMode(AcquisitionMode.NATURAL);
        List<Order> pesados = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Order p = order(PESADO, ServiceLevel.ESTANDAR, 4);
            pesados.add(p);
            dispatcher.submit(p);
        }
        for (int i = 0; i < 2; i++) {
            dispatcher.submit(order(VOLUMINOSO, ServiceLevel.ESTANDAR, 4));
        }

        // Se forma el ciclo: todos los montacargas y áreas ocupados y los 5 pedidos con un recurso pendiente
        await(() -> rm.pool(ResourceType.MONTACARGAS).snapshot().available() == 0
                        && rm.pool(ResourceType.AREA_CARGA).snapshot().available() == 0
                        && rm.pool(ResourceType.AREA_CARGA).snapshot().waiters().size() == 3
                        && rm.pool(ResourceType.MONTACARGAS).snapshot().waiters().size() == 2,
                5000, "ciclo de espera formado");
        Thread.sleep(300);
        assertTrue(completed.isEmpty(), "con deadlock nadie puede completar");

        // Resolución manual: el pesado elegido como víctima libera sus recursos y vuelve a la lista de espera
        assertTrue(dispatcher.preempt(pesados.get(0).id()));
        await(() -> !completed.isEmpty(), 10_000, "el sistema reanuda tras resolver el conflicto");
    }

    @Test
    void alDetenerseNoQuedanRecursosTomados() throws Exception {
        create(new AgingPolicy(), 0);
        for (int i = 0; i < 10; i++) {
            dispatcher.submit(order(FRAGIL, ServiceLevel.ESTANDAR, 100));
        }
        await(() -> !dispatcher.runningOrders().isEmpty(), 2000, "hay pedidos en proceso");
        dispatcher.stop();
        await(() -> rm.snapshot().stream().allMatch(s -> s.available() == s.capacity()), 3000, "recursos liberados");
        dispatcher = null;
    }
}

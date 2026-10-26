package com.redxela.processing;

import com.redxela.domain.*;
import com.redxela.inventory.InventoryArea;
import com.redxela.inventory.Warehouse;
import com.redxela.reception.ReceptionArea;
import com.redxela.reception.ReceptionMessage;
import com.redxela.resource.ResourceManager;
import com.redxela.scheduler.AgingPolicy;
import com.redxela.scheduler.WaitingList;
import com.redxela.simulation.EventLog;
import com.redxela.simulation.SimClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.BooleanSupplier;

import static com.redxela.testsupport.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Integración despachador + inventario + recepción: pedidos que esperan existencias y se reevalúan al llegar mercadería. */
class DispatcherStockTest {

    private final SimClock clock = new SimClock(20);
    private final EventLog log = new EventLog(clock, 500);
    private final ResourceManager rm = new ResourceManager();
    private final WaitingList waiting = new WaitingList();
    private final Product laptop = new Product(1, "ELE-1", "Laptop", ELECTRONICO);
    private final Product libro = new Product(2, "DOC-1", "Libro", DOCUMENTOS);
    private InventoryArea inventory;
    private ReceptionArea reception;
    private Dispatcher dispatcher;

    @AfterEach
    void tearDown() {
        if (dispatcher != null) dispatcher.stop();
        if (inventory != null) inventory.stop();
        if (reception != null) reception.stop();
    }

    private void setUp(int initialLaptops) {
        var channel = new ArrayBlockingQueue<ReceptionMessage>(10);
        Map<Long, Product> catalog = Map.of(1L, laptop, 2L, libro);
        inventory = new InventoryArea(new Warehouse(5, 4, 4, 100), channel, catalog, clock, log, 0.1, 0);
        if (initialLaptops > 0) inventory.seed(1, initialLaptops);
        reception = new ReceptionArea(channel, rm, catalog, clock, log, 0.05, 2000, new Random(1));
        dispatcher = new Dispatcher(waiting, rm, clock, log, new AgingPolicy(), 50, 0, inventory);
        inventory.addStockListener(dispatcher::signal);
        inventory.start();
        dispatcher.start();
    }

    private Order orderOf(Product p, int units) {
        return new Order(System.nanoTime(), CUSTOMER, ServiceLevel.ESTANDAR,
                List.of(new OrderItem(p, units)), clock.now(), units * 0.25);
    }

    private static void await(BooleanSupplier c, long ms, String what) throws InterruptedException {
        long limit = System.currentTimeMillis() + ms;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > limit) fail("Tiempo agotado: " + what);
            Thread.sleep(5);
        }
    }

    @Test
    void unPedidoSinExistenciasEsperaYSeReevaluaCuandoLlegaMercaderia() throws Exception {
        setUp(0);
        Order o = orderOf(laptop, 8);
        dispatcher.submit(o);

        await(() -> o.status() == OrderStatus.ESPERANDO_INVENTARIO, 3000, "espera existencias");
        assertTrue(waiting.contains(o));
        assertTrue(rm.snapshot().stream().allMatch(s -> s.holders().isEmpty()), "no debe tomar recursos sin stock");

        reception.receive(1, 20);                       // llega mercadería por Recepción -> Inventario
        await(() -> o.status() == OrderStatus.COMPLETADO, 10_000, "pedido completado tras recibir stock");
        assertEquals(12, inventory.warehouse().unitsOf(1), "20 recibidas - 8 despachadas");
        assertTrue(log.recent(50).stream().anyMatch(e -> e.type().equals("CON_STOCK")));
    }

    @Test
    void lasReservasEvitanVenderDosVecesLasMismasUnidades() throws Exception {
        setUp(100);
        Order a = orderOf(laptop, 60), b = orderOf(laptop, 60);
        dispatcher.submit(a);
        dispatcher.submit(b);

        await(() -> a.status() == OrderStatus.COMPLETADO || b.status() == OrderStatus.COMPLETADO, 10_000, "uno completa");
        Order otro = a.status() == OrderStatus.COMPLETADO ? b : a;
        Thread.sleep(300);
        assertEquals(OrderStatus.ESPERANDO_INVENTARIO, otro.status(), "con 40 u. no puede atender 60");
        assertEquals(40, inventory.warehouse().unitsOf(1));

        reception.receive(1, 30);                       // ahora hay 70
        await(() -> otro.status() == OrderStatus.COMPLETADO, 10_000, "segundo pedido completo");
        assertEquals(10, inventory.warehouse().unitsOf(1));
    }

    @Test
    void preemptarDevuelveLasExistenciasReservadas() throws Exception {
        setUp(100);
        Order largo = orderOf(laptop, 90);              // 22.5 min = 450 ms de trabajo
        dispatcher.submit(largo);
        await(() -> largo.status() == OrderStatus.EN_PROCESO && largo.progress() > 0.05, 3000, "en proceso");
        assertFalse(inventory.hasStock(orderOf(laptop, 20)), "90 de 100 reservadas");

        assertTrue(dispatcher.preempt(largo.id()));
        await(() -> largo.requeues() == 1, 3000, "reencolado");
        // Tras reencolarse se vuelve a reservar y termina; el stock final refleja una sola salida
        await(() -> largo.status() == OrderStatus.COMPLETADO, 10_000, "completado");
        assertEquals(10, inventory.warehouse().unitsOf(1));
    }

    @Test
    void unPedidoSinStockNoBloqueaAOtrosQueSiTienen() throws Exception {
        setUp(50);                                      // hay laptops, no hay libros
        Order sinStock = orderOf(libro, 5);
        Order conStock = orderOf(laptop, 4);
        sinStock.status(OrderStatus.EN_ESPERA);
        dispatcher.submit(sinStock);
        dispatcher.submit(conStock);

        await(() -> conStock.status() == OrderStatus.COMPLETADO, 10_000, "el pedido con stock se completa");
        assertEquals(OrderStatus.ESPERANDO_INVENTARIO, sinStock.status());
    }
}

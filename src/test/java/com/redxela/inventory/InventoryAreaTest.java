package com.redxela.inventory;

import com.redxela.domain.*;
import com.redxela.reception.ReceptionMessage;
import com.redxela.simulation.EventLog;
import com.redxela.simulation.SimClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static com.redxela.testsupport.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryAreaTest {

    private final SimClock clock = new SimClock(10);
    private final EventLog log = new EventLog(clock, 500);
    private final Product laptop = new Product(1, "ELE-1", "Laptop", ELECTRONICO);
    private final Product libro = new Product(2, "DOC-1", "Libro", DOCUMENTOS);
    private final BlockingQueue<ReceptionMessage> channel = new ArrayBlockingQueue<>(5);
    private InventoryArea inventory;

    private InventoryArea create(Warehouse w, double base, double perUnit) {
        inventory = new InventoryArea(w, channel, Map.of(1L, laptop, 2L, libro), clock, log, base, perUnit);
        return inventory;
    }

    @AfterEach
    void tearDown() {
        if (inventory != null) inventory.stop();
    }

    private Order orderOf(Product p, int qty) {
        return new Order(System.nanoTime(), CUSTOMER, ServiceLevel.ESTANDAR, List.of(new OrderItem(p, qty)), 0, 1);
    }

    private static void await(BooleanSupplier c, long ms, String what) throws InterruptedException {
        long limit = System.currentTimeMillis() + ms;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > limit) fail("Tiempo agotado: " + what);
            Thread.sleep(5);
        }
    }

    @Test
    void reservaConfirmaYDevuelveExistencias() {
        create(new Warehouse(2, 2, 2, 50), 0, 0).seed(1, 30);
        Order a = orderOf(laptop, 20), b = orderOf(laptop, 20);

        assertTrue(inventory.hasStock(a));
        assertTrue(inventory.tryReserve(a));
        assertTrue(inventory.tryReserve(a), "reservar dos veces el mismo pedido es idempotente");
        assertFalse(inventory.hasStock(b), "20 de 30 ya están reservadas");
        assertFalse(inventory.tryReserve(b));

        inventory.release(a);                               // el pedido A no terminó
        assertTrue(inventory.tryReserve(b));
        inventory.commit(b);                                // B sí terminó: salen del almacén
        var stock = inventory.stock().stream().filter(s -> s.product().id() == 1).findFirst().orElseThrow();
        assertEquals(10, stock.onHand());
        assertEquals(0, stock.reserved());
        assertEquals(10, stock.available());
        inventory.commit(b);                                // doble confirmación: no hace nada
        assertEquals(10, inventory.warehouse().unitsOf(1));
    }

    @Test
    void laReservaEsTodoONadaEntreLineas() {
        create(new Warehouse(2, 2, 2, 50), 0, 0);
        inventory.seed(1, 10);
        Order mixto = new Order(1, CUSTOMER, ServiceLevel.ESTANDAR,
                List.of(new OrderItem(laptop, 5), new OrderItem(libro, 1)), 0, 1);   // libro: sin stock
        assertFalse(inventory.tryReserve(mixto));
        assertEquals(0, inventory.stock().stream().filter(s -> s.product().id() == 1).findFirst().orElseThrow().reserved(),
                "no debe quedar reservada la línea que sí había");
    }

    @Test
    void procesaLosAvisosDeRecepcionAsignaUbicacionesYNotifica() throws Exception {
        create(new Warehouse(2, 2, 2, 20), 0.1, 0.0).start();
        AtomicInteger notificaciones = new AtomicInteger();
        inventory.addStockListener(notificaciones::incrementAndGet);

        channel.put(new ReceptionMessage(1, 1, 45, 0));    // 20 + 20 + 5

        await(() -> inventory.processedMessages() == 1, 5000, "aviso procesado");
        assertEquals(45, inventory.warehouse().unitsOf(1));
        assertEquals(3, inventory.warehouse().locationsOf(1).size());
        assertEquals(1, notificaciones.get());
        assertTrue(log.recent(10).stream().anyMatch(e -> e.type().equals("INVENTARIO")));
    }

    /** Recepción mucho más rápida que Inventario: no se pierde ningún aviso ni nadie se bloquea para siempre. */
    @Test
    void conRecepcionRapidaNoSePierdeNingunAviso() throws Exception {
        create(new Warehouse(5, 4, 4, 100), 0.5, 0.0).start();    // 5 ms por aviso; el canal solo admite 5
        int avisos = 60;
        Thread recepcion = new Thread(() -> {
            try {
                for (int i = 1; i <= avisos; i++) {
                    channel.put(new ReceptionMessage(i, 1 + i % 2, 10, 0));   // put bloquea si el canal está lleno
                }
            } catch (InterruptedException ignored) {
            }
        });
        recepcion.start();
        recepcion.join(20_000);
        assertFalse(recepcion.isAlive(), "Recepción no debe quedar bloqueada indefinidamente");

        await(() -> inventory.processedMessages() == avisos, 20_000, "todos procesados");
        assertEquals(avisos * 10, inventory.warehouse().unitsOf(1) + inventory.warehouse().unitsOf(2));
        assertEquals(0, inventory.rejectedUnits());
        assertEquals(avisos * 10, inventory.storedUnits());
    }

    @Test
    void registraComoRechazadasLasUnidadesQueNoCaben() throws Exception {
        create(new Warehouse(1, 1, 2, 10), 0, 0).start();   // 20 u. de capacidad
        channel.put(new ReceptionMessage(1, 1, 27, 0));
        await(() -> inventory.processedMessages() == 1, 5000, "procesado");
        assertEquals(20, inventory.warehouse().unitsOf(1));
        assertEquals(7, inventory.rejectedUnits());
        assertTrue(log.recent(10).stream().anyMatch(e -> e.type().equals("ALMACEN_LLENO")));
    }

    @Test
    void unAvisoDeProductoDesconocidoNoDetieneAInventario() throws Exception {
        create(new Warehouse(1, 1, 4, 10), 0, 0).start();
        channel.put(new ReceptionMessage(1, 99, 5, 0));    // producto que no existe
        channel.put(new ReceptionMessage(2, 1, 5, 0));
        await(() -> inventory.processedMessages() == 2, 5000, "ambos procesados");
        assertEquals(5, inventory.warehouse().unitsOf(1));
        assertTrue(log.recent(10).stream().anyMatch(e -> e.type().equals("ERROR")));
    }
}

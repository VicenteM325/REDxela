package com.redxela.reception;

import com.redxela.domain.Order;
import com.redxela.domain.Product;
import com.redxela.domain.ResourceType;
import com.redxela.domain.ServiceLevel;
import com.redxela.inventory.InventoryArea;
import com.redxela.inventory.Warehouse;
import com.redxela.resource.ResourceManager;
import com.redxela.simulation.EventLog;
import com.redxela.simulation.SimClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.BooleanSupplier;

import static com.redxela.testsupport.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class ReceptionAreaTest {

    private final SimClock clock = new SimClock(10);
    private final EventLog log = new EventLog(clock, 500);
    private final ResourceManager rm = new ResourceManager();
    private final Product laptop = new Product(1, "ELE-1", "Laptop", ELECTRONICO);
    private final Map<Long, Product> catalog = Map.of(1L, laptop);
    private InventoryArea inventory;
    private ReceptionArea reception;

    private BlockingQueue<ReceptionMessage> setUp(int capacity, boolean startInventory, double inventoryMinutes) {
        BlockingQueue<ReceptionMessage> channel = new ArrayBlockingQueue<>(capacity);
        inventory = new InventoryArea(new Warehouse(5, 4, 4, 100), channel, catalog, clock, log, inventoryMinutes, 0);
        reception = new ReceptionArea(channel, rm, catalog, clock, log, 0.1, 150, new Random(1));
        if (startInventory) inventory.start();
        return channel;
    }

    @AfterEach
    void tearDown() {
        if (inventory != null) inventory.stop();
        if (reception != null) reception.stop();
    }

    private static void await(BooleanSupplier c, long ms, String what) throws InterruptedException {
        long limit = System.currentTimeMillis() + ms;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > limit) fail("Tiempo agotado: " + what);
            Thread.sleep(5);
        }
    }

    @Test
    void unLoteRecibidoLlegaAlAlmacenPorElCanal() throws Exception {
        setUp(5, true, 0.1);
        ReceptionMessage m = reception.receive(1, 40);
        assertEquals(40, m.quantity());
        await(() -> inventory.processedMessages() == 1, 5000, "procesado");
        assertEquals(40, inventory.warehouse().unitsOf(1));
        assertEquals(1, reception.sentLots());
    }

    @Test
    void validaProductoYCantidad() throws Exception {
        setUp(5, true, 0.1);
        assertThrows(IllegalArgumentException.class, () -> reception.receive(99, 5));
        assertThrows(IllegalArgumentException.class, () -> reception.receive(1, 0));
        assertThrows(IllegalArgumentException.class, () -> reception.receive(1, 20_000));
        assertEquals(0, reception.sentLots());
    }

    @Test
    void recepcionComparteElEscanerConElDespacho() throws Exception {
        setUp(5, true, 0.1);
        Order despacho = order(DOCUMENTOS, ServiceLevel.ESTANDAR, 1);
        assertTrue(rm.pool(ResourceType.ESCANER).tryAcquire(despacho));       // el despacho tiene el escáner

        // Mientras lo tenga, Recepción no puede escanear: se rinde por tiempo, sin perder nada
        assertThrows(ReceptionOverloadedException.class, () -> reception.receive(1, 10));
        assertEquals(0, reception.sentLots());
        assertTrue(rm.pool(ResourceType.ESCANER).snapshot().waiters().isEmpty());

        rm.pool(ResourceType.ESCANER).release(despacho);
        reception.receive(1, 10);                                             // ahora sí
        await(() -> inventory.warehouse().unitsOf(1) == 10, 5000, "lote almacenado");
        assertEquals(1, rm.pool(ResourceType.ESCANER).snapshot().available(), "el escáner debe quedar libre");
    }

    @Test
    void conElCanalLlenoElLoteNoSeAceptaPeroNoSePierdeNada() throws Exception {
        setUp(2, false, 0.1);            // Inventario detenido: nadie consume el canal
        reception.receive(1, 10);
        reception.receive(1, 10);
        assertEquals(2, reception.queueSize());
        assertThrows(ReceptionOverloadedException.class, () -> reception.receive(1, 10));
        assertEquals(2, reception.queueSize());
        assertEquals(2, reception.sentLots());
        assertEquals(1, rm.pool(ResourceType.ESCANER).snapshot().available(), "el escáner se libera aunque falle el envío");

        inventory.start();               // al arrancar Inventario se procesan los dos avisos pendientes
        await(() -> inventory.processedMessages() == 2, 5000, "los dos avisos pendientes");
        assertEquals(20, inventory.warehouse().unitsOf(1));
    }

    /** Un camión con muchos más lotes que capacidad del canal: todo se procesa, nada se pierde. */
    @Test
    void unCamionRapidoConInventarioLentoNoPierdeLotes() throws Exception {
        setUp(3, true, 1.0);             // Inventario tarda ~10 ms por lote; Recepción solo 1 ms
        assertTrue(reception.startBurst(40, 5));
        assertFalse(reception.startBurst(10, 5), "un solo camión a la vez");

        await(() -> reception.burstRemaining() == 0 && inventory.processedMessages() == 40, 30_000, "40 lotes procesados");
        assertEquals(200, inventory.warehouse().unitsOf(1));
        assertEquals(0, inventory.rejectedUnits());
        assertEquals(40, reception.sentLots());
        assertEquals(0, reception.queueSize());
    }

    @Test
    void laReposicionAutomaticaRecibeLosProductosBajoElPuntoDeReorden() throws Exception {
        setUp(5, true, 0.1);
        inventory.seed(1, 10);                                                // bajo el punto de reposición (30)
        AutoRestocker restocker = new AutoRestocker(inventory, reception, clock, log, 1, 30, 100);
        restocker.start();
        try {
            await(() -> inventory.warehouse().unitsOf(1) >= 110, 10_000, "reposición");
            Thread.sleep(150);
            int enAlmacen = inventory.warehouse().unitsOf(1);
            assertTrue(enAlmacen < 400, "no debe seguir reponiendo cuando ya hay suficiente: " + enAlmacen);
        } finally {
            restocker.stop();
        }
        assertFalse(restocker.isRunning());
    }
}

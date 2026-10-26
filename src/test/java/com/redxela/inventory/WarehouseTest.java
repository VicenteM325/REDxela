package com.redxela.inventory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class WarehouseTest {

    private static int sum(List<Warehouse.Allocation> allocations) {
        return allocations.stream().mapToInt(Warehouse.Allocation::quantity).sum();
    }

    @Test
    void unLoteGrandeSeReparteEnVariasUbicaciones() {
        Warehouse w = new Warehouse(2, 2, 2, 10);        // 8 ubicaciones de 10 u.
        var r = w.store(1, 25);
        assertEquals(0, r.rejected());
        assertEquals(3, r.stored().size());                // 10 + 10 + 5
        assertEquals(25, w.unitsOf(1));
        assertEquals(25, w.usedUnits());
        assertEquals(80, w.capacityUnits());
    }

    @Test
    void completaPrimeroLasUbicacionesParcialesDelMismoProducto() {
        Warehouse w = new Warehouse(1, 1, 5, 10);
        w.store(1, 4);                                     // ubicación 1: 4/10
        var r = w.store(1, 3);
        assertEquals(1, r.stored().size());
        assertEquals("P1-N1-U1", r.stored().get(0).location().label());
        assertEquals(7, w.locationsOf(1).get(0).quantity());
    }

    @Test
    void unMismoProductoPuedeQuedarEnUbicacionesNoContiguas() {
        Warehouse w = new Warehouse(1, 1, 6, 10);
        w.store(1, 10);                                    // U1 = producto 1 (lleno)
        w.store(2, 10);                                    // U2 = producto 2
        w.store(1, 10);                                    // U1 llena -> U3 para el producto 1
        var ubicaciones = w.locationsOf(1);
        assertEquals(2, ubicaciones.size());
        assertEquals(0, ubicaciones.get(0).location().position());
        assertEquals(2, ubicaciones.get(1).location().position(), "salta la ubicación 2 ocupada por otro producto");
    }

    @Test
    void guardaLoQueCabeEInformaElRechazo() {
        Warehouse w = new Warehouse(1, 1, 2, 10);          // 20 u. de capacidad
        var r = w.store(1, 27);
        assertEquals(7, r.rejected());
        assertEquals(20, w.unitsOf(1));
        // Una ubicación ocupada por otro producto no se puede usar aunque tenga espacio
        Warehouse w2 = new Warehouse(1, 1, 2, 10);
        w2.store(1, 5);
        var r2 = w2.store(2, 25);
        assertEquals(10, sum(r2.stored()));
        assertEquals(15, r2.rejected());
    }

    @Test
    void retirarLiberaPrimeroLasUbicacionesConMenosStock() {
        Warehouse w = new Warehouse(1, 1, 4, 10);
        w.store(1, 25);                                    // 10, 10, 5
        var removed = w.remove(1, 7);
        assertEquals(7, sum(removed));
        assertEquals(18, w.unitsOf(1));
        // La ubicación de 5 se vació y quedó libre para otro producto
        assertEquals(2, w.locationsOf(1).size());
        assertEquals(10, sum(w.store(2, 10).stored()));
    }

    @Test
    void noPermiteRetirarMasDeLoQueHay() {
        Warehouse w = new Warehouse(1, 1, 2, 10);
        w.store(1, 5);
        assertThrows(IllegalStateException.class, () -> w.remove(1, 6));
        assertEquals(5, w.unitsOf(1), "un retiro fallido no debe cambiar nada");
        assertThrows(IllegalArgumentException.class, () -> w.store(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Warehouse(0, 1, 1, 1));
    }

    @Test
    void esConsistenteConAccesoConcurrente() throws Exception {
        Warehouse w = new Warehouse(5, 4, 4, 25);          // 2000 u.
        ExecutorService ex = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger net = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Future<?>> fs = new java.util.ArrayList<>();
        for (int t = 0; t < 8; t++) {
            final long product = t % 4;
            fs.add(ex.submit(() -> {
                go.await();
                for (int i = 0; i < 200; i++) {
                    var r = w.store(product, 7);
                    net.addAndGet(7 - r.rejected());
                    try {
                        w.remove(product, 3);
                        net.addAndGet(-3);
                    } catch (IllegalStateException ignored) {
                        // otro hilo del mismo producto se llevó las unidades
                    }
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        ex.shutdown();
        // El total del almacén coincide con las operaciones exitosas
        assertEquals(net.get(), w.usedUnits());
        int porProducto = 0;
        for (long p = 0; p < 4; p++) porProducto += w.unitsOf(p);
        assertEquals(w.usedUnits(), porProducto);
        assertTrue(w.usedUnits() <= w.capacityUnits());
    }
}

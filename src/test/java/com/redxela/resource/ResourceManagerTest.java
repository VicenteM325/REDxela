package com.redxela.resource;

import com.redxela.domain.Order;
import com.redxela.domain.ResourceType;
import com.redxela.domain.ServiceLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.redxela.domain.ResourceType.*;
import static com.redxela.testsupport.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceManagerTest {

    @Test
    void adquisicionAtomicaTomaTodoONada() {
        ResourceManager rm = new ResourceManager();
        Order docs1 = order(DOCUMENTOS, ServiceLevel.ESTANDAR, 1);   // ENCARGADO + ESCANER
        Order docs2 = order(DOCUMENTOS, ServiceLevel.ESTANDAR, 1);
        assertTrue(rm.tryAcquireAll(docs1));
        // docs2 no puede (escáner ocupado) y NO debe quedarse con un encargado a medias
        assertFalse(rm.tryAcquireAll(docs2));
        assertEquals(9, rm.pool(ENCARGADO).snapshot().available());
        assertFalse(rm.pool(ENCARGADO).isHeldBy(docs2));

        rm.releaseAll(docs1);
        assertTrue(rm.tryAcquireAll(docs2));
        rm.releaseAll(docs2);
        rm.releaseAll(docs2); // idempotente
        for (ResourceSnapshot s : rm.snapshot()) {
            assertEquals(s.capacity(), s.available(), s.type().toString());
        }
    }

    @Test
    void ordenNaturalDependeDelTipoYElOrdenGlobalEsComunATodos() {
        ResourceManager rm = new ResourceManager();
        Order pesado = order(PESADO, ServiceLevel.ESTANDAR, 1);          // ENCARGADO, MONTACARGAS, AREA_CARGA
        Order voluminoso = order(VOLUMINOSO, ServiceLevel.ESTANDAR, 1);  // EMPAQUE, AREA_CARGA, MONTACARGAS

        assertEquals(List.of(ENCARGADO, MONTACARGAS, AREA_CARGA), rm.sequenceFor(pesado, AcquisitionMode.NATURAL));
        assertEquals(List.of(EMPAQUE, AREA_CARGA, MONTACARGAS), rm.sequenceFor(voluminoso, AcquisitionMode.NATURAL));

        // En orden global ambos piden AREA_CARGA antes que MONTACARGAS: no hay espera circular
        List<ResourceType> gp = rm.sequenceFor(pesado, AcquisitionMode.ORDERED);
        List<ResourceType> gv = rm.sequenceFor(voluminoso, AcquisitionMode.ORDERED);
        assertTrue(gp.indexOf(AREA_CARGA) < gp.indexOf(MONTACARGAS));
        assertTrue(gv.indexOf(AREA_CARGA) < gv.indexOf(MONTACARGAS));
    }

    /** Estrés: muchos pedidos de tipos en conflicto con adquisición atómica nunca se bloquean. */
    @Test
    void adquisicionAtomicaBajoEstresNoSeBloqueaNiExcedeCapacidad() throws Exception {
        ResourceManager rm = new ResourceManager();
        int hilos = 16;
        AtomicInteger completados = new AtomicInteger();
        ExecutorService ex = Executors.newFixedThreadPool(hilos);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            final int n = i;
            fs.add(ex.submit(() -> {
                for (int k = 0; k < 50; k++) {
                    Order o = n % 2 == 0 ? order(PESADO, ServiceLevel.ESTANDAR, 1)
                                         : order(VOLUMINOSO, ServiceLevel.ESTANDAR, 1);
                    while (!rm.tryAcquireAll(o)) {
                        Thread.onSpinWait();
                    }
                    for (ResourceSnapshot s : rm.snapshot()) {
                        assertTrue(s.holders().size() <= s.capacity());
                    }
                    rm.releaseAll(o);
                    completados.incrementAndGet();
                }
                return null;
            }));
        }
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        ex.shutdown();
        assertEquals(hilos * 50, completados.get());
        for (ResourceSnapshot s : rm.snapshot()) {
            assertEquals(s.capacity(), s.available());
        }
    }
}

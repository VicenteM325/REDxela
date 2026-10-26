package com.redxela.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.redxela.domain.ResourceType.*;
import static com.redxela.testsupport.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderTest {

    @Test
    void sumaLasUnidadesDeTodasLasLineas() {
        Order o = new Order(1, CUSTOMER, ServiceLevel.EXPRES,
                List.of(new OrderItem(product(FRAGIL), 3), new OrderItem(product(FRAGIL), 4)), 0, 1);
        assertEquals(7, o.totalUnits());
    }

    @Test
    void losRecursosSonLaUnionDeLosTiposConservandoElOrden() {
        Order o = new Order(1, CUSTOMER, ServiceLevel.ESTANDAR,
                List.of(new OrderItem(product(DOCUMENTOS), 1), new OrderItem(product(PESADO), 1)), 0, 1);
        // DOCUMENTOS: ENCARGADO, ESCANER; PESADO añade MONTACARGAS y AREA_CARGA (ENCARGADO ya estaba)
        assertEquals(List.of(ENCARGADO, ESCANER, MONTACARGAS, AREA_CARGA), o.requiredResources());
        assertEquals(java.util.Set.of("DOCUMENTOS", "PESADO"), o.merchandiseTypeCodes());
    }

    @Test
    void rechazaPedidosSinProductosOCantidadesNoPositivas() {
        assertThrows(IllegalArgumentException.class,
                () -> new Order(1, CUSTOMER, ServiceLevel.ESTANDAR, List.of(), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(product(FRAGIL), 0));
    }

    @Test
    void reencolarDescartaElProgresoYContabilizaElReintento() {
        Order o = order(FRAGIL, ServiceLevel.ESTANDAR, 4, 10);
        o.markStarted(15);
        o.status(OrderStatus.EN_PROCESO);
        o.progress(0.6);
        assertEquals(5, o.waitMinutes(), 1e-9);

        o.resetForRequeue();
        assertEquals(OrderStatus.EN_ESPERA, o.status());
        assertEquals(0, o.progress());
        assertTrue(Double.isNaN(o.startedAt()));
        assertEquals(1, o.requeues());
        // la espera original se conserva: el primer inicio no se pierde
        assertEquals(15, o.firstStartedAt(), 1e-9);
        assertEquals(20, o.ageMinutes(30), 1e-9);
    }
}

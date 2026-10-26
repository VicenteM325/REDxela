package com.redxela.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServiceLevelTest {

    @Test
    void objetivosDeEsperaCoincidenConElEnunciado() {
        assertEquals(0, ServiceLevel.EXPRES.maxWaitMinutes());
        assertEquals(10, ServiceLevel.PRIORITARIO.maxWaitMinutes());
        assertEquals(30, ServiceLevel.ESTANDAR.maxWaitMinutes());
        assertEquals(60, ServiceLevel.PROGRAMADO.maxWaitMinutes());
        assertEquals(120, ServiceLevel.ECONOMICO.maxWaitMinutes());
    }

    @Test
    void fromLevelRechazaNivelesInvalidos() {
        assertEquals(ServiceLevel.ESTANDAR, ServiceLevel.fromLevel(3));
        assertThrows(IllegalArgumentException.class, () -> ServiceLevel.fromLevel(0));
        assertThrows(IllegalArgumentException.class, () -> ServiceLevel.fromLevel(6));
    }

    @Test
    void capacidadesDeRecursosCoincidenConElEnunciado() {
        assertEquals(6, ResourceType.EMPAQUE.capacity());
        assertEquals(2, ResourceType.AREA_CARGA.capacity());
        assertEquals(10, ResourceType.ENCARGADO.capacity());
        assertEquals(3, ResourceType.MONTACARGAS.capacity());
        assertEquals(4, ResourceType.CONTROL_CALIDAD.capacity());
        assertEquals(1, ResourceType.ESCANER.capacity());
    }
}

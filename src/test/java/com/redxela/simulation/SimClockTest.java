package com.redxela.simulation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimClockTest {

    @Test
    void convierteMinutosSimuladosAMilisegundosSegunVelocidad() {
        SimClock clock = new SimClock(1000);
        assertEquals(10_000, clock.toRealMillis(10));
        clock.setSpeed(4);
        assertEquals(2_500, clock.toRealMillis(10));
    }

    @Test
    void elTiempoAvanzaYNoRetrocedeAlCambiarLaVelocidad() throws InterruptedException {
        SimClock clock = new SimClock(10);
        Thread.sleep(30);
        double antes = clock.now();
        assertTrue(antes >= 2.5, "deberían haber pasado ~3 minutos simulados, fue " + antes);
        clock.setSpeed(10);
        assertTrue(clock.now() >= antes);
    }

    @Test
    void rechazaVelocidadesFueraDeRango() {
        SimClock clock = new SimClock(1000);
        assertThrows(IllegalArgumentException.class, () -> clock.setSpeed(0));
        assertThrows(IllegalArgumentException.class, () -> clock.setSpeed(1000));
        assertThrows(IllegalArgumentException.class, () -> new SimClock(0));
    }
}

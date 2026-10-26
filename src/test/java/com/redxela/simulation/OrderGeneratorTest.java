package com.redxela.simulation;

import com.redxela.domain.Order;
import com.redxela.domain.ServiceLevel;
import com.redxela.processing.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:generator;DB_CLOSE_DELAY=-1",
        "redxela.simulation.ms-per-sim-minute=20"})
class OrderGeneratorTest {

    @Autowired
    OrderGenerator generator;
    @Autowired
    OrderService orders;

    @Test
    void generaPedidosContinuamenteDeTodosLosNivelesYSePuedeDetener() throws Exception {
        int antes = orders.retainedCount();
        generator.setRate(50);
        generator.start();
        assertTrue(generator.isRunning());

        long limite = System.currentTimeMillis() + 15_000;
        while (orders.retainedCount() - antes < 150 && System.currentTimeMillis() < limite) {
            Thread.sleep(50);
        }
        generator.stop();
        assertFalse(generator.isRunning());
        assertTrue(orders.retainedCount() - antes >= 150, "debería haber generado al menos 150 pedidos");

        Set<Integer> niveles = new TreeSet<>();
        for (Order o : orders.recent(200)) {
            niveles.add(o.serviceLevel().level());
            assertTrue(o.totalUnits() >= 1);
            assertFalse(o.requiredResources().isEmpty());
        }
        assertEquals(Set.of(1, 2, 3, 4, 5), niveles, "deben aparecer todos los niveles de servicio");

        // Detenido de verdad: ya no llegan pedidos nuevos
        Thread.sleep(200);
        int despuesDeParar = orders.retainedCount();
        Thread.sleep(300);
        assertEquals(despuesDeParar, orders.retainedCount());
    }

    @Test
    void unPedidoGeneradoManualmenteEsValido() {
        Order o = generator.generateOne();
        assertNotNull(o.customer());
        assertTrue(o.serviceLevel() instanceof ServiceLevel);
        assertTrue(o.workMinutes() > 0);
    }

    @Test
    void rechazaTasasFueraDeRango() {
        assertThrows(IllegalArgumentException.class, () -> generator.setRate(0));
        assertThrows(IllegalArgumentException.class, () -> generator.setRate(1000));
    }
}

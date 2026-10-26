package com.redxela.simulation;

import com.redxela.domain.Product;
import com.redxela.domain.ServiceLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static com.redxela.testsupport.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderMixTest {

    private static List<Product> catalog() {
        List<Product> products = new ArrayList<>();
        for (var type : List.of(FRAGIL, PESADO, VOLUMINOSO, DOCUMENTOS, ELECTRONICO)) {
            for (int i = 0; i < 3; i++) products.add(product(type));
        }
        return products;
    }

    private static String trace(OrderMix mix, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(mix.nextLevel()).append(':');
            mix.nextLines().forEach(l -> sb.append(l.product().id()).append('x').append(l.quantity()).append(','));
            sb.append(';');
        }
        return sb.toString();
    }

    @Test
    void conLaMismaSemillaLaSecuenciaEsIdentica() {
        List<Product> catalog = catalog();
        assertEquals(trace(new OrderMix(new Random(7), catalog), 200), trace(new OrderMix(new Random(7), catalog), 200));
        assertNotEquals(trace(new OrderMix(new Random(7), catalog), 200), trace(new OrderMix(new Random(8), catalog), 200));
    }

    @Test
    void generaPedidosValidosDeTodosLosNivelesYAlgunosMixtos() {
        OrderMix mix = new OrderMix(new Random(1), catalog());
        Set<ServiceLevel> niveles = new TreeSet<>();
        int mixtos = 0;
        for (int i = 0; i < 1000; i++) {
            niveles.add(mix.nextLevel());
            var lines = mix.nextLines();
            assertFalse(lines.isEmpty());
            assertTrue(lines.size() <= 3);
            lines.forEach(l -> assertTrue(l.quantity() >= 1 && l.quantity() <= 8));
            if (lines.stream().map(l -> l.product().merchandiseType().code()).distinct().count() > 1) mixtos++;
        }
        assertEquals(5, niveles.size());
        assertTrue(mixtos > 50 && mixtos < 300, "alrededor del 15% de los pedidos mezclan tipos: " + mixtos);
    }

    @Test
    void rechazaUnCatalogoVacio() {
        assertThrows(IllegalArgumentException.class, () -> new OrderMix(new Random(1), List.of()));
    }
}

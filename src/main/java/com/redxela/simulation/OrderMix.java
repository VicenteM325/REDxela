package com.redxela.simulation;

import com.redxela.domain.Product;
import com.redxela.domain.ServiceLevel;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Mezcla aleatoria de pedidos: nivel de servicio y productos de cada pedido. Con la misma semilla
 * produce siempre la misma secuencia, lo que permite someter a todas las políticas a una carga
 * idéntica. La comparten el generador automático y el comparador de políticas.
 */
public class OrderMix {

    /** Peso relativo de cada nivel de servicio (1..5) en las llegadas. */
    private static final int[] LEVEL_WEIGHTS = {10, 20, 35, 20, 15};
    /** Fracción de pedidos que mezclan dos tipos de mercancía. */
    private static final double MIXED_RATE = 0.15;
    private static final int MAX_UNITS_PER_LINE = 8;

    /** Línea de un pedido generado. */
    public record Line(Product product, int quantity) {
    }

    private final Random random;
    private final Map<String, List<Product>> productsByType;

    public OrderMix(Random random, Collection<Product> catalog) {
        this.random = random;
        this.productsByType = catalog.stream()
                .collect(Collectors.groupingBy(p -> p.merchandiseType().code(), TreeMap::new, Collectors.toList()));
        if (productsByType.isEmpty()) {
            throw new IllegalArgumentException("El catálogo está vacío");
        }
    }

    public ServiceLevel nextLevel() {
        int total = Arrays.stream(LEVEL_WEIGHTS).sum();
        int r = random.nextInt(total);
        for (int i = 0; i < LEVEL_WEIGHTS.length; i++) {
            r -= LEVEL_WEIGHTS[i];
            if (r < 0) {
                return ServiceLevel.fromLevel(i + 1);
            }
        }
        return ServiceLevel.ESTANDAR;
    }

    /** Mayoría de pedidos de un solo tipo de mercancía; una fracción mezcla dos tipos. */
    public List<Line> nextLines() {
        List<String> types = new ArrayList<>(productsByType.keySet());
        Collections.shuffle(types, random);
        List<Line> lines = new ArrayList<>();
        if (random.nextDouble() < MIXED_RATE && types.size() >= 2) {
            for (String type : types.subList(0, 2)) {
                List<Product> products = productsByType.get(type);
                lines.add(new Line(products.get(random.nextInt(products.size())), 1 + random.nextInt(MAX_UNITS_PER_LINE)));
            }
        } else {
            List<Product> pool = new ArrayList<>(productsByType.get(types.get(0)));
            Collections.shuffle(pool, random);
            int count = 1 + random.nextInt(Math.min(3, pool.size()));
            for (Product p : pool.subList(0, count)) {
                lines.add(new Line(p, 1 + random.nextInt(MAX_UNITS_PER_LINE)));
            }
        }
        return lines;
    }
}

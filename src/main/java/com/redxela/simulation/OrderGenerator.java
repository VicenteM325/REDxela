package com.redxela.simulation;

import com.redxela.domain.*;
import com.redxela.persistence.CatalogRepository;
import com.redxela.persistence.CustomerRepository;
import com.redxela.processing.OrderService;
import org.springframework.dao.DuplicateKeyException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generador automático de pedidos: simula la llegada continua de clientes de todos los niveles,
 * sin límite en su cantidad, con llegadas de Poisson (tiempos entre llegadas exponenciales) a una
 * tasa ajustable en caliente. Corre en su propio hilo.
 */
public class OrderGenerator {

    /** Peso relativo de cada nivel de servicio (1..5) en las llegadas. */
    private static final int[] LEVEL_WEIGHTS = {10, 20, 35, 20, 15};
    /** Fracción de pedidos que mezclan dos tipos de mercancía. */
    private static final double MIXED_RATE = 0.15;
    private static final int MAX_UNITS_PER_LINE = 8;

    private final OrderService orders;
    private final CatalogRepository catalog;
    private final CustomerRepository customers;
    private final SimClock clock;
    private final EventLog log;
    private final Random random;

    private volatile double ratePerSimMinute;
    private Thread thread;
    private volatile boolean running;

    private Map<ServiceLevel, Customer> generatedCustomers;
    private Map<String, List<Product>> productsByType;

    public OrderGenerator(OrderService orders, CatalogRepository catalog, CustomerRepository customers,
                          SimClock clock, EventLog log, Random random, double initialRatePerSimMinute) {
        this.orders = orders;
        this.catalog = catalog;
        this.customers = customers;
        this.clock = clock;
        this.log = log;
        this.random = random;
        setRate(initialRatePerSimMinute);
    }

    /** Arranca la generación (idempotente). */
    public synchronized void start() {
        if (running) {
            return;
        }
        prepare();
        running = true;
        thread = new Thread(this::loop, "redxela-generator");
        thread.setDaemon(true);
        thread.start();
        log.add("GENERADOR", "Generador automático iniciado (" + ratePerSimMinute + " pedidos por minuto simulado)");
    }

    /** Detiene la generación (idempotente). */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        thread.interrupt();
        log.add("GENERADOR", "Generador automático detenido");
    }

    public boolean isRunning() {
        return running;
    }

    public double rate() {
        return ratePerSimMinute;
    }

    /** Cambia la tasa de llegadas (pedidos por minuto simulado), válida entre 0.01 y 100. */
    public void setRate(double ratePerSimMinute) {
        if (ratePerSimMinute < 0.01 || ratePerSimMinute > 100) {
            throw new IllegalArgumentException("La tasa debe estar entre 0.01 y 100 pedidos por minuto");
        }
        this.ratePerSimMinute = ratePerSimMinute;
    }

    /** Crea un único pedido aleatorio ahora mismo (útil para pruebas y escenarios). */
    public Order generateOne() {
        prepare();
        return orders.create(generatedCustomers.get(randomLevel()), null, randomItems());
    }

    // ---------------------------------------------------------------- interno

    /** Carga los clientes sintéticos (uno por nivel) y el catálogo agrupado por tipo. */
    private synchronized void prepare() {
        if (generatedCustomers != null) {
            return;
        }
        Map<ServiceLevel, Customer> map = new EnumMap<>(ServiceLevel.class);
        for (ServiceLevel level : ServiceLevel.values()) {
            String email = "gen-nivel" + level.level() + "@redxela.sim";
            Customer c = customers.findByEmail(email).orElseGet(() -> {
                try {
                    // Hash inválido a propósito: estas cuentas sintéticas no pueden iniciar sesión
                    return customers.insert("Generador " + level.displayName(), email, "!", level);
                } catch (DuplicateKeyException e) {
                    return customers.findByEmail(email).orElseThrow();
                }
            });
            map.put(level, c);
        }
        productsByType = catalog.findProducts().stream()
                .collect(Collectors.groupingBy(p -> p.merchandiseType().code(), TreeMap::new, Collectors.toList()));
        generatedCustomers = map;
    }

    private void loop() {
        while (running) {
            try {
                waitNextArrival();
                if (running) {
                    orders.create(generatedCustomers.get(randomLevel()), null, randomItems());
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // Un pedido mal formado no debe detener la generación
                log.add("ERROR", "Generador: " + e);
            }
        }
    }

    /**
     * Espera el tiempo entre llegadas (exponencial). Duerme en tramos cortos para reaccionar a
     * cambios de velocidad, de tasa o a la detención sin esperar un intervalo largo completo.
     */
    private void waitNextArrival() throws InterruptedException {
        double gapMinutes = -Math.log(1 - random.nextDouble()) / ratePerSimMinute;
        double target = clock.now() + gapMinutes;
        while (running && clock.now() < target) {
            Thread.sleep(Math.max(1, Math.min(100, clock.toRealMillis(target - clock.now()))));
        }
    }

    private ServiceLevel randomLevel() {
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
    private List<OrderService.ItemRequest> randomItems() {
        List<String> types = new ArrayList<>(productsByType.keySet());
        Collections.shuffle(types, random);
        List<OrderService.ItemRequest> items = new ArrayList<>();
        if (random.nextDouble() < MIXED_RATE && types.size() >= 2) {
            for (String type : types.subList(0, 2)) {
                items.add(randomLine(productsByType.get(type)));
            }
        } else {
            List<Product> pool = new ArrayList<>(productsByType.get(types.get(0)));
            Collections.shuffle(pool, random);
            int lines = 1 + random.nextInt(Math.min(3, pool.size()));
            for (Product p : pool.subList(0, lines)) {
                items.add(new OrderService.ItemRequest(p.id(), 1 + random.nextInt(MAX_UNITS_PER_LINE)));
            }
        }
        return items;
    }

    private OrderService.ItemRequest randomLine(List<Product> products) {
        Product p = products.get(random.nextInt(products.size()));
        return new OrderService.ItemRequest(p.id(), 1 + random.nextInt(MAX_UNITS_PER_LINE));
    }
}

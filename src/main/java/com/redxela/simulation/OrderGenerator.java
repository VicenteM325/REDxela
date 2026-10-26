package com.redxela.simulation;

import com.redxela.domain.*;
import com.redxela.persistence.CatalogRepository;
import com.redxela.persistence.CustomerRepository;
import com.redxela.processing.OrderService;
import org.springframework.dao.DuplicateKeyException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generador automático de pedidos: simula la llegada continua de clientes de todos los niveles,
 * sin límite en su cantidad, con llegadas de Poisson (tiempos entre llegadas exponenciales) a una
 * tasa ajustable en caliente. Corre en su propio hilo.
 */
public class OrderGenerator {

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
    private OrderMix mix;

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

    /** Cliente sintético del nivel dado (el que usan el generador y los escenarios de demostración). */
    public Customer systemCustomer(ServiceLevel level) {
        prepare();
        return generatedCustomers.get(level);
    }

    /** Crea un único pedido aleatorio ahora mismo (útil para pruebas y escenarios). */
    public Order generateOne() {
        prepare();
        return orders.create(generatedCustomers.get(mix.nextLevel()), null, nextItems());
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
        mix = new OrderMix(random, catalog.findProducts());
        generatedCustomers = map;
    }

    private void loop() {
        while (running) {
            try {
                waitNextArrival();
                if (running) {
                    orders.create(generatedCustomers.get(mix.nextLevel()), null, nextItems());
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

    private List<OrderService.ItemRequest> nextItems() {
        return mix.nextLines().stream()
                .map(l -> new OrderService.ItemRequest(l.product().id(), l.quantity())).toList();
    }
}

package com.redxela.simulation;

import com.redxela.domain.Product;
import com.redxela.inventory.InventoryArea;
import com.redxela.inventory.Warehouse;
import com.redxela.persistence.CatalogRepository;
import com.redxela.persistence.CustomerRepository;
import com.redxela.processing.Dispatcher;
import com.redxela.processing.OrderService;
import com.redxela.reception.AutoRestocker;
import com.redxela.reception.ReceptionArea;
import com.redxela.reception.ReceptionMessage;
import com.redxela.resource.ResourceManager;
import com.redxela.scheduler.SchedulingPolicies;
import com.redxela.scheduler.SchedulingPolicy;
import com.redxela.scheduler.WaitingList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Cablea los componentes de la simulación como beans únicos (singletons) compartidos por la API,
 * el generador y el despachador. Los parámetros se ajustan en {@code application.yml}.
 */
@Configuration
public class SimulationConfig {

    @Bean
    public SimClock simClock(@Value("${redxela.simulation.ms-per-sim-minute:1000}") double msPerSimMinute) {
        return new SimClock(msPerSimMinute);
    }

    @Bean
    public EventLog eventLog(SimClock clock) {
        return new EventLog(clock, 500);
    }

    @Bean
    public WorkEstimator workEstimator(@Value("${redxela.simulation.minutes-per-unit:0.25}") double minutesPerUnit) {
        return new WorkEstimator(minutesPerUnit);
    }

    @Bean
    public ResourceManager resourceManager() {
        return new ResourceManager();
    }

    @Bean
    public WaitingList waitingList() {
        return new WaitingList();
    }

    /** Almacén físico; sus dimensiones y la capacidad por ubicación las define el desarrollador. */
    @Bean
    public Warehouse warehouse(@Value("${redxela.warehouse.aisles:5}") int aisles,
                               @Value("${redxela.warehouse.levels:4}") int levels,
                               @Value("${redxela.warehouse.positions:4}") int positions,
                               @Value("${redxela.warehouse.slot-capacity:25}") int slotCapacity) {
        return new Warehouse(aisles, levels, positions, slotCapacity);
    }

    /** Canal acotado por el que Recepción avisa a Inventario (única vía de comunicación entre ambas áreas). */
    @Bean
    public BlockingQueue<ReceptionMessage> receptionChannel(
            @Value("${redxela.reception.queue-capacity:10}") int capacity) {
        return new ArrayBlockingQueue<>(capacity);
    }

    /** Inventario arranca con existencias iniciales y consume el canal en su propio hilo. */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public InventoryArea inventoryArea(Warehouse warehouse, BlockingQueue<ReceptionMessage> channel,
                                       CatalogRepository catalog, SimClock clock, EventLog log,
                                       @Value("${redxela.inventory.base-minutes:0.2}") double baseMinutes,
                                       @Value("${redxela.inventory.minutes-per-unit:0.02}") double minutesPerUnit,
                                       @Value("${redxela.inventory.initial-stock-per-product:60}") int initialStock) {
        Map<Long, Product> products = new LinkedHashMap<>();
        catalog.findProducts().forEach(p -> products.put(p.id(), p));
        InventoryArea inventory = new InventoryArea(warehouse, channel, products, clock, log, baseMinutes, minutesPerUnit);
        if (initialStock > 0) {
            products.keySet().forEach(id -> inventory.seed(id, initialStock));
        }
        return inventory;
    }

    @Bean(destroyMethod = "stop")
    public ReceptionArea receptionArea(BlockingQueue<ReceptionMessage> channel, ResourceManager resources,
                                       CatalogRepository catalog, SimClock clock, EventLog log,
                                       @Value("${redxela.reception.scan-minutes:0.2}") double scanMinutes,
                                       @Value("${redxela.reception.timeout-ms:3000}") long timeoutMs) {
        Map<Long, Product> products = new LinkedHashMap<>();
        catalog.findProducts().forEach(p -> products.put(p.id(), p));
        return new ReceptionArea(channel, resources, products, clock, log, scanMinutes, timeoutMs, new Random());
    }

    /** Proveedores simulados: reponen por Recepción los productos que se agotan. */
    @Bean(destroyMethod = "stop")
    public AutoRestocker autoRestocker(InventoryArea inventory, ReceptionArea reception, SimClock clock, EventLog log,
                                       @Value("${redxela.restock.check-every-minutes:5}") double checkEvery,
                                       @Value("${redxela.restock.reorder-point:30}") int reorderPoint,
                                       @Value("${redxela.restock.lot-size:100}") int lotSize,
                                       @Value("${redxela.restock.autostart:false}") boolean autostart) {
        AutoRestocker restocker = new AutoRestocker(inventory, reception, clock, log, checkEvery, reorderPoint, lotSize);
        if (autostart) {
            restocker.start();
        }
        return restocker;
    }

    /** El despachador arranca y se detiene con el contexto de Spring. */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public Dispatcher dispatcher(WaitingList waiting, ResourceManager resources, SimClock clock, EventLog log,
                                 InventoryArea inventory,
                                 @Value("${redxela.dispatcher.policy:AGING}") String policyId,
                                 @Value("${redxela.dispatcher.max-in-flight:40}") int maxInFlight,
                                 @Value("${redxela.dispatcher.acquisition-pause-ms:400}") long acquisitionPauseMs) {
        SchedulingPolicy policy = SchedulingPolicies.byId(policyId)
                .orElseThrow(() -> new IllegalArgumentException("Política desconocida: " + policyId));
        Dispatcher dispatcher = new Dispatcher(waiting, resources, clock, log, policy, maxInFlight,
                acquisitionPauseMs, inventory);
        // Cuando llega mercadería, el despachador reevalúa los pedidos que esperaban existencias
        inventory.addStockListener(dispatcher::signal);
        return dispatcher;
    }

    @Bean(destroyMethod = "stop")
    public OrderGenerator orderGenerator(OrderService orders, CatalogRepository catalog, CustomerRepository customers,
                                         SimClock clock, EventLog log,
                                         @Value("${redxela.generator.rate-per-sim-minute:1.0}") double rate,
                                         @Value("${redxela.generator.autostart:false}") boolean autostart) {
        OrderGenerator generator = new OrderGenerator(orders, catalog, customers, clock, log, new Random(), rate);
        if (autostart) {
            generator.start();
        }
        return generator;
    }
}

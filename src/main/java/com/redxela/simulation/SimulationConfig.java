package com.redxela.simulation;

import com.redxela.persistence.CatalogRepository;
import com.redxela.persistence.CustomerRepository;
import com.redxela.processing.Dispatcher;
import com.redxela.processing.OrderService;
import com.redxela.resource.ResourceManager;
import com.redxela.scheduler.SchedulingPolicies;
import com.redxela.scheduler.SchedulingPolicy;
import com.redxela.scheduler.WaitingList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Random;

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

    /** El despachador arranca y se detiene con el contexto de Spring. */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public Dispatcher dispatcher(WaitingList waiting, ResourceManager resources, SimClock clock, EventLog log,
                                 @Value("${redxela.dispatcher.policy:AGING}") String policyId,
                                 @Value("${redxela.dispatcher.max-in-flight:40}") int maxInFlight,
                                 @Value("${redxela.dispatcher.acquisition-pause-ms:400}") long acquisitionPauseMs) {
        SchedulingPolicy policy = SchedulingPolicies.byId(policyId)
                .orElseThrow(() -> new IllegalArgumentException("Política desconocida: " + policyId));
        return new Dispatcher(waiting, resources, clock, log, policy, maxInFlight, acquisitionPauseMs);
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

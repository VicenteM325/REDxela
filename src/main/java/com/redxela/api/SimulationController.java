package com.redxela.api;

import com.redxela.domain.Order;
import com.redxela.processing.Dispatcher;
import com.redxela.processing.OrderService;
import com.redxela.resource.AcquisitionMode;
import com.redxela.resource.ResourceManager;
import com.redxela.scheduler.SchedulingPolicies;
import com.redxela.scheduler.SchedulingPolicy;
import com.redxela.simulation.EventLog;
import com.redxela.simulation.OrderGenerator;
import com.redxela.simulation.SimClock;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;

/** Observación y control de la simulación: estado, política, modo de adquisición, velocidad y generador. */
@RestController
@RequestMapping("/api")
public class SimulationController {

    private final Dispatcher dispatcher;
    private final ResourceManager resources;
    private final OrderService orders;
    private final OrderGenerator generator;
    private final SimClock clock;
    private final EventLog log;

    public SimulationController(Dispatcher dispatcher, ResourceManager resources, OrderService orders,
                                OrderGenerator generator, SimClock clock, EventLog log) {
        this.dispatcher = dispatcher;
        this.resources = resources;
        this.orders = orders;
        this.generator = generator;
        this.clock = clock;
        this.log = log;
    }

    @GetMapping("/state")
    public StateDto state() {
        double now = clock.now();
        SchedulingPolicy policy = dispatcher.policy();
        List<OrderDto> waiting = dispatcher.waitingList().ordered(policy, now).stream()
                .map(o -> OrderDto.of(o, now)).toList();
        List<OrderDto> running = dispatcher.runningOrders().stream()
                .sorted(Comparator.comparingLong(Order::id))
                .map(o -> OrderDto.of(o, now)).toList();
        List<StateDto.ResourceDto> res = resources.snapshot().stream().map(s -> new StateDto.ResourceDto(
                s.type().name(), s.type().displayName(), s.capacity(), s.available(),
                s.capacity() - s.available(), s.holders(), s.waiters())).toList();
        return new StateDto(
                new StateDto.ClockDto(now, clock.speed()),
                policyDto(policy),
                dispatcher.mode().name(),
                new StateDto.GeneratorDto(generator.isRunning(), generator.rate()),
                waiting, running, res, orders.retainedCount());
    }

    @GetMapping("/events")
    public List<EventLog.Event> events(@RequestParam(defaultValue = "50") int limit) {
        return log.recent(Math.max(1, Math.min(limit, 500)));
    }

    // ------------------------------------------------------------ políticas

    @GetMapping("/policies")
    public List<StateDto.PolicyDto> policies() {
        return SchedulingPolicies.all().stream().map(SimulationController::policyDto).toList();
    }

    @PutMapping("/policy")
    public StateDto.PolicyDto setPolicy(@Valid @RequestBody PolicyRequest req) {
        SchedulingPolicy policy = SchedulingPolicies.byId(req.id())
                .orElseThrow(() -> new IllegalArgumentException("Política desconocida: " + req.id()));
        dispatcher.setPolicy(policy);
        return policyDto(policy);
    }

    // ------------------------------------------------------- modo y velocidad

    @PutMapping("/mode")
    public ModeResponse setMode(@Valid @RequestBody ModeRequest req) {
        dispatcher.setMode(req.mode());
        return new ModeResponse(dispatcher.mode().name());
    }

    @PutMapping("/speed")
    public StateDto.ClockDto setSpeed(@Valid @RequestBody SpeedRequest req) {
        clock.setSpeed(req.speed());
        log.add("VELOCIDAD", "Velocidad de la simulación: " + req.speed() + "x");
        return new StateDto.ClockDto(clock.now(), clock.speed());
    }

    // ------------------------------------------------------------- generador

    @PostMapping("/generator/start")
    public StateDto.GeneratorDto startGenerator() {
        generator.start();
        return new StateDto.GeneratorDto(generator.isRunning(), generator.rate());
    }

    @PostMapping("/generator/stop")
    public StateDto.GeneratorDto stopGenerator() {
        generator.stop();
        return new StateDto.GeneratorDto(generator.isRunning(), generator.rate());
    }

    @PutMapping("/generator")
    public StateDto.GeneratorDto setRate(@Valid @RequestBody RateRequest req) {
        generator.setRate(req.ratePerSimMinute());
        return new StateDto.GeneratorDto(generator.isRunning(), generator.rate());
    }

    private static StateDto.PolicyDto policyDto(SchedulingPolicy p) {
        return new StateDto.PolicyDto(p.id(), p.name(), p.description());
    }

    public record PolicyRequest(@NotBlank String id) {
    }

    public record ModeRequest(@NotNull AcquisitionMode mode) {
    }

    public record ModeResponse(String mode) {
    }

    public record SpeedRequest(double speed) {
    }

    public record RateRequest(double ratePerSimMinute) {
    }
}

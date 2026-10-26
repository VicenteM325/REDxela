package com.redxela.api;

import com.redxela.auth.AuthService;
import com.redxela.domain.Customer;
import com.redxela.domain.ServiceLevel;
import com.redxela.processing.OrderService;
import com.redxela.simulation.SimClock;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Alta y consulta de pedidos. */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orders;
    private final AuthService auth;
    private final SimClock clock;

    public OrderController(OrderService orders, AuthService auth, SimClock clock) {
        this.orders = orders;
        this.auth = auth;
        this.clock = clock;
    }

    /**
     * Registra un pedido a nombre del cliente de la sesión. Normalmente se atiende con el nivel
     * contratado; {@code serviceLevel} permite forzar otro para provocar situaciones de prueba.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDto create(@RequestHeader("Authorization") String authorization,
                           @Valid @RequestBody CreateOrderRequest req) {
        Customer customer = auth.authenticate(bearer(authorization));
        ServiceLevel override = req.serviceLevel() == null ? null : ServiceLevel.fromLevel(req.serviceLevel());
        List<OrderService.ItemRequest> items = req.items().stream()
                .map(i -> new OrderService.ItemRequest(i.productId(), i.quantity())).toList();
        return OrderDto.of(orders.create(customer, override, items), clock.now());
    }

    @GetMapping
    public List<OrderDto> recent(@RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        double now = clock.now();
        return orders.recent(limit).stream().map(o -> OrderDto.of(o, now)).toList();
    }

    @GetMapping("/{id}")
    public OrderDto get(@PathVariable long id) {
        return orders.find(id).map(o -> OrderDto.of(o, clock.now()))
                .orElseThrow(() -> new NotFoundException("Pedido inexistente: " + id));
    }

    private static String bearer(String header) {
        return header.startsWith("Bearer ") ? header.substring(7).trim() : header.trim();
    }

    public record CreateOrderRequest(@NotEmpty List<@Valid ItemRequest> items,
                                     @Min(1) @Max(5) Integer serviceLevel) {
    }

    public record ItemRequest(@Min(1) long productId, @Min(1) @Max(500) int quantity) {
    }
}

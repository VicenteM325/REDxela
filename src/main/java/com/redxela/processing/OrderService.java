package com.redxela.processing;

import com.redxela.domain.*;
import com.redxela.persistence.CatalogRepository;
import com.redxela.simulation.EventLog;
import com.redxela.simulation.SimClock;
import com.redxela.simulation.WorkEstimator;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Crea pedidos (desde la API o el generador), les asigna un id único, estima su trabajo y los
 * entrega al despachador. Conserva los pedidos recientes para consultarlos.
 */
@Service
public class OrderService {

    /** Máximo de pedidos retenidos en memoria; el generador no tiene límite, la memoria sí. */
    private static final int MAX_RETAINED = 5000;
    private static final int MAX_LINES = 20;
    private static final int MAX_QUANTITY = 500;

    /** Línea solicitada: producto y cantidad de unidades. */
    public record ItemRequest(long productId, int quantity) {
    }

    private final CatalogRepository catalog;
    private final Dispatcher dispatcher;
    private final SimClock clock;
    private final WorkEstimator estimator;
    private final EventLog log;

    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentNavigableMap<Long, Order> orders = new ConcurrentSkipListMap<>();
    private volatile Map<Long, Product> productsById;

    public OrderService(CatalogRepository catalog, Dispatcher dispatcher, SimClock clock,
                        WorkEstimator estimator, EventLog log) {
        this.catalog = catalog;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.estimator = estimator;
        this.log = log;
    }

    /**
     * Crea un pedido y lo pone en la lista de espera.
     *
     * @param levelOverride nivel de servicio a usar en lugar del contratado por el cliente
     *                      (ingreso manual para provocar situaciones); {@code null} usa el del cliente
     * @throws IllegalArgumentException si las líneas son inválidas o algún producto no existe
     */
    public Order create(Customer customer, ServiceLevel levelOverride, List<ItemRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("El pedido necesita al menos un producto");
        }
        if (requests.size() > MAX_LINES) {
            throw new IllegalArgumentException("Un pedido admite como máximo " + MAX_LINES + " líneas");
        }
        // Un mismo producto repetido se agrupa en una sola línea
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (ItemRequest r : requests) {
            if (r.quantity() < 1 || r.quantity() > MAX_QUANTITY) {
                throw new IllegalArgumentException("La cantidad debe estar entre 1 y " + MAX_QUANTITY);
            }
            quantities.merge(r.productId(), r.quantity(), Integer::sum);
        }
        Map<Long, Product> catalogById = products();
        List<OrderItem> items = quantities.entrySet().stream().map(e -> {
            Product p = catalogById.get(e.getKey());
            if (p == null) {
                throw new IllegalArgumentException("Producto inexistente: " + e.getKey());
            }
            return new OrderItem(p, e.getValue());
        }).toList();

        int units = items.stream().mapToInt(OrderItem::quantity).sum();
        ServiceLevel level = levelOverride != null ? levelOverride : customer.serviceLevel();
        Order order = new Order(ids.incrementAndGet(), customer, level, items, clock.now(),
                estimator.workMinutes(units));
        orders.put(order.id(), order);
        evictIfNeeded();
        dispatcher.submit(order);
        return order;
    }

    public Optional<Order> find(long id) {
        return Optional.ofNullable(orders.get(id));
    }

    /** Los pedidos más recientes, del más nuevo al más antiguo. */
    public List<Order> recent(int limit) {
        return orders.descendingMap().values().stream().limit(limit).toList();
    }

    public int retainedCount() {
        return orders.size();
    }

    /** Catálogo de productos por id (se carga una vez: el catálogo es fijo). */
    public Map<Long, Product> products() {
        Map<Long, Product> cached = productsById;
        if (cached == null) {
            synchronized (this) {
                cached = productsById;
                if (cached == null) {
                    Map<Long, Product> map = new LinkedHashMap<>();
                    catalog.findProducts().forEach(p -> map.put(p.id(), p));
                    productsById = cached = Map.copyOf(map);
                }
            }
        }
        return cached;
    }

    /** Descarta los pedidos completados más antiguos cuando se supera el máximo retenido. */
    private void evictIfNeeded() {
        if (orders.size() <= MAX_RETAINED) {
            return;
        }
        for (Order o : orders.values()) {
            if (orders.size() <= MAX_RETAINED * 4 / 5) {
                break;
            }
            if (o.status() == OrderStatus.COMPLETADO) {
                orders.remove(o.id());
            }
        }
    }
}

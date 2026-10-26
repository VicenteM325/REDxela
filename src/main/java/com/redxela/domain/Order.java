package com.redxela.domain;

import java.time.Instant;
import java.util.List;

/**
 * Pedido del centro de distribución. Los datos de identidad son inmutables; el estado y las
 * marcas de tiempo cambian desde distintos hilos, por eso son {@code volatile}.
 */
public class Order {

    private final long id;
    private final Customer customer;
    private final ServiceLevel serviceLevel;
    private final MerchandiseType merchandiseType;
    private final List<OrderItem> items;
    private final Instant createdAt;

    private volatile OrderStatus status = OrderStatus.EN_ESPERA;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;

    public Order(long id, Customer customer, ServiceLevel serviceLevel,
                 MerchandiseType merchandiseType, List<OrderItem> items, Instant createdAt) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Un pedido necesita al menos un producto");
        }
        this.id = id;
        this.customer = customer;
        this.serviceLevel = serviceLevel;
        this.merchandiseType = merchandiseType;
        this.items = List.copyOf(items);
        this.createdAt = createdAt;
    }

    /** Total de artículos del pedido: es la estimación base del trabajo a realizar. */
    public int totalUnits() {
        return items.stream().mapToInt(OrderItem::quantity).sum();
    }

    public long id() { return id; }
    public Customer customer() { return customer; }
    public ServiceLevel serviceLevel() { return serviceLevel; }
    public MerchandiseType merchandiseType() { return merchandiseType; }
    public List<OrderItem> items() { return items; }
    public Instant createdAt() { return createdAt; }
    public OrderStatus status() { return status; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }

    public void status(OrderStatus status) { this.status = status; }
    public void startedAt(Instant startedAt) { this.startedAt = startedAt; }
    public void finishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}

package com.redxela.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pedido del centro de distribución.
 *
 * <p>Los datos de identidad son inmutables. El estado y las marcas de tiempo los modifican hilos
 * distintos (despachador, worker, API), por eso son {@code volatile}. Todas las marcas de tiempo
 * están en minutos simulados (ver {@code SimClock}) y valen {@link Double#NaN} mientras no ocurran.
 */
public class Order {

    private final long id;
    private final Customer customer;
    private final ServiceLevel serviceLevel;
    private final List<OrderItem> items;
    private final double createdAt;
    private final double workMinutes;
    private final List<ResourceType> requiredResources;

    private volatile OrderStatus status = OrderStatus.EN_ESPERA;
    private volatile double firstStartedAt = Double.NaN;
    private volatile double startedAt = Double.NaN;
    private volatile double finishedAt = Double.NaN;
    private volatile double progress;
    private volatile ResourceType waitingFor;
    private final AtomicInteger requeues = new AtomicInteger();

    /**
     * @param serviceLevel nivel con el que se atiende; normalmente el del cliente, pero el ingreso
     *                     manual puede forzar otro para provocar situaciones de prueba
     * @param createdAt    minuto simulado de llegada
     * @param workMinutes  trabajo total estimado (minutos simulados), proporcional a las unidades
     */
    public Order(long id, Customer customer, ServiceLevel serviceLevel, List<OrderItem> items,
                 double createdAt, double workMinutes) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Un pedido necesita al menos un producto");
        }
        this.id = id;
        this.customer = customer;
        this.serviceLevel = serviceLevel;
        this.items = List.copyOf(items);
        this.createdAt = createdAt;
        this.workMinutes = workMinutes;
        this.requiredResources = computeRequiredResources(this.items);
    }

    /**
     * Recursos del pedido: unión de los que exige el tipo de cada producto, conservando el orden
     * natural de adquisición (primero los del primer producto, y así sucesivamente).
     */
    private static List<ResourceType> computeRequiredResources(List<OrderItem> items) {
        Set<ResourceType> union = new LinkedHashSet<>();
        for (OrderItem item : items) {
            union.addAll(item.product().merchandiseType().resources());
        }
        return List.copyOf(union);
    }

    /** Total de artículos del pedido: es la estimación base del trabajo a realizar. */
    public int totalUnits() {
        return items.stream().mapToInt(OrderItem::quantity).sum();
    }

    /** Códigos de los tipos de mercancía presentes en el pedido. */
    public Set<String> merchandiseTypeCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (OrderItem item : items) {
            codes.add(item.product().merchandiseType().code());
        }
        return codes;
    }

    /** Minutos simulados que lleva (o llevó) esperando antes de iniciar; se usa para el envejecimiento. */
    public double ageMinutes(double now) {
        return now - createdAt;
    }

    /** Espera total antes del primer inicio de procesamiento, o NaN si aún no inicia. */
    public double waitMinutes() {
        return firstStartedAt - createdAt;
    }

    /** Minutos simulados que faltan para terminar el procesamiento actual. */
    public double remainingMinutes() {
        return status == OrderStatus.COMPLETADO ? 0 : (1 - progress) * workMinutes;
    }

    /** Marca el inicio de un intento de procesamiento. */
    public void markStarted(double now) {
        startedAt = now;
        if (Double.isNaN(firstStartedAt)) {
            firstStartedAt = now;
        }
    }

    /** Devuelve el pedido a la lista de espera descartando el progreso del intento actual. */
    public void resetForRequeue() {
        status = OrderStatus.EN_ESPERA;
        progress = 0;
        startedAt = Double.NaN;
        waitingFor = null;
        requeues.incrementAndGet();
    }

    public long id() { return id; }
    public Customer customer() { return customer; }
    public ServiceLevel serviceLevel() { return serviceLevel; }
    public List<OrderItem> items() { return items; }
    public double createdAt() { return createdAt; }
    public double workMinutes() { return workMinutes; }
    public List<ResourceType> requiredResources() { return requiredResources; }
    public OrderStatus status() { return status; }
    public double firstStartedAt() { return firstStartedAt; }
    public double startedAt() { return startedAt; }
    public double finishedAt() { return finishedAt; }
    public double progress() { return progress; }
    public ResourceType waitingFor() { return waitingFor; }
    public int requeues() { return requeues.get(); }

    public void status(OrderStatus status) { this.status = status; }
    public void finishedAt(double finishedAt) { this.finishedAt = finishedAt; }
    public void progress(double progress) { this.progress = progress; }
    public void waitingFor(ResourceType waitingFor) { this.waitingFor = waitingFor; }
}

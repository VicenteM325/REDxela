package com.redxela.inventory;

import com.redxela.domain.Order;
import com.redxela.domain.OrderItem;
import com.redxela.domain.Product;
import com.redxela.processing.StockGate;
import com.redxela.reception.ReceptionMessage;
import com.redxela.simulation.EventLog;
import com.redxela.simulation.SimClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Área de Control de Inventario: dueña exclusiva del almacén y de las existencias.
 *
 * <p>Se comunica con Recepción solo mediante el canal de mensajes: consume los avisos en su propio
 * hilo y a su propio ritmo (simulando el tiempo que lleva registrar cada lote), asigna
 * ubicaciones, actualiza existencias y avisa a los interesados para que reevalúen los pedidos que
 * esperaban ese producto. Al despachador le ofrece la interfaz {@link StockGate} (reservar,
 * confirmar, devolver), nunca acceso directo a sus estructuras.
 *
 * <p>Existencias disponibles = unidades en el almacén − unidades reservadas por pedidos en proceso.
 */
public class InventoryArea implements StockGate {

    /** Vista de las existencias de un producto. */
    public record ProductStock(Product product, int onHand, int reserved, List<Warehouse.Allocation> locations) {
        public int available() {
            return onHand - reserved;
        }
    }

    private final Warehouse warehouse;
    private final BlockingQueue<ReceptionMessage> channel;
    private final Map<Long, Product> catalog;
    private final SimClock clock;
    private final EventLog log;
    private final double baseMinutes;
    private final double minutesPerUnit;

    /** Protege las existencias, las reservas y las operaciones compuestas sobre el almacén. */
    private final Object stockLock = new Object();
    private final Map<Long, Integer> reservedByProduct = new HashMap<>();
    private final Map<Long, List<OrderItem>> reservationsByOrder = new HashMap<>();
    private final List<Runnable> stockListeners = new CopyOnWriteArrayList<>();

    private final AtomicLong processedMessages = new AtomicLong();
    private final AtomicLong storedUnits = new AtomicLong();
    private final AtomicLong rejectedUnits = new AtomicLong();

    private Thread thread;
    private volatile boolean active;

    /**
     * @param baseMinutes    minutos simulados fijos que toma registrar cualquier lote
     * @param minutesPerUnit minutos simulados adicionales por unidad del lote
     */
    public InventoryArea(Warehouse warehouse, BlockingQueue<ReceptionMessage> channel, Map<Long, Product> catalog,
                         SimClock clock, EventLog log, double baseMinutes, double minutesPerUnit) {
        this.warehouse = warehouse;
        this.channel = channel;
        this.catalog = catalog;
        this.clock = clock;
        this.log = log;
        this.baseMinutes = baseMinutes;
        this.minutesPerUnit = minutesPerUnit;
    }

    // ------------------------------------------------------------ ciclo de vida

    /** Arranca el hilo que consume los avisos de Recepción (idempotente). */
    public synchronized void start() {
        if (active) {
            return;
        }
        active = true;
        thread = new Thread(this::loop, "redxela-inventory");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        if (!active) {
            return;
        }
        active = false;
        thread.interrupt();
    }

    /** Se invoca (desde el hilo de Inventario) cada vez que cambian las existencias. */
    public void addStockListener(Runnable listener) {
        stockListeners.add(listener);
    }

    /** Carga existencias iniciales directamente, sin pasar por Recepción (solo para el arranque). */
    public void seed(long productId, int quantity) {
        store(productId, quantity, 0, "carga inicial");
    }

    // ---------------------------------------------------------------- StockGate

    @Override
    public boolean hasStock(Order order) {
        synchronized (stockLock) {
            return allAvailable(order.items());
        }
    }

    @Override
    public boolean tryReserve(Order order) {
        synchronized (stockLock) {
            if (reservationsByOrder.containsKey(order.id())) {
                return true;
            }
            if (!allAvailable(order.items())) {
                return false;
            }
            for (OrderItem item : order.items()) {
                reservedByProduct.merge(item.product().id(), item.quantity(), Integer::sum);
            }
            reservationsByOrder.put(order.id(), order.items());
            return true;
        }
    }

    @Override
    public void commit(Order order) {
        synchronized (stockLock) {
            List<OrderItem> items = reservationsByOrder.remove(order.id());
            if (items == null) {
                return;
            }
            for (OrderItem item : items) {
                reservedByProduct.merge(item.product().id(), -item.quantity(), Integer::sum);
                warehouse.remove(item.product().id(), item.quantity());
            }
        }
        log.add("SALIDA", "Pedido #" + order.id() + " retiró " + order.totalUnits() + " u. del almacén");
    }

    @Override
    public void release(Order order) {
        synchronized (stockLock) {
            List<OrderItem> items = reservationsByOrder.remove(order.id());
            if (items == null) {
                return;
            }
            for (OrderItem item : items) {
                reservedByProduct.merge(item.product().id(), -item.quantity(), Integer::sum);
            }
        }
    }

    // ---------------------------------------------------------------- consultas

    /** Existencias de todos los productos del catálogo, ordenadas por SKU. */
    public List<ProductStock> stock() {
        List<ProductStock> out = new ArrayList<>();
        synchronized (stockLock) {
            for (Product p : catalog.values()) {
                out.add(new ProductStock(p, warehouse.unitsOf(p.id()),
                        reservedByProduct.getOrDefault(p.id(), 0), warehouse.locationsOf(p.id())));
            }
        }
        out.sort(java.util.Comparator.comparing(s -> s.product().sku()));
        return out;
    }

    public Warehouse warehouse() {
        return warehouse;
    }

    public int queueSize() {
        return channel.size();
    }

    public long processedMessages() {
        return processedMessages.get();
    }

    public long storedUnits() {
        return storedUnits.get();
    }

    /** Unidades recibidas que no cupieron en el almacén. */
    public long rejectedUnits() {
        return rejectedUnits.get();
    }

    // ----------------------------------------------------------------- interno

    private boolean allAvailable(List<OrderItem> items) {
        for (OrderItem item : items) {
            long id = item.product().id();
            int available = warehouse.unitsOf(id) - reservedByProduct.getOrDefault(id, 0);
            if (available < item.quantity()) {
                return false;
            }
        }
        return true;
    }

    private void loop() {
        while (active) {
            try {
                ReceptionMessage message = channel.take();
                // Registrar el lote lleva tiempo: Inventario trabaja a su propio ritmo
                double minutes = baseMinutes + message.quantity() * minutesPerUnit;
                Thread.sleep(clock.toRealMillis(minutes));
                store(message.productId(), message.quantity(), message.id(), "lote #" + message.id());
                processedMessages.incrementAndGet();
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // Un aviso defectuoso no debe detener a Inventario
                log.add("ERROR", "Inventario: " + e);
            }
        }
    }

    private void store(long productId, int quantity, long lotId, String origin) {
        Product product = catalog.get(productId);
        if (product == null) {
            log.add("ERROR", "Inventario: producto desconocido " + productId + " (" + origin + ")");
            return;
        }
        Warehouse.StoreResult result;
        synchronized (stockLock) {
            result = warehouse.store(productId, quantity);
        }
        int stored = quantity - result.rejected();
        storedUnits.addAndGet(stored);
        rejectedUnits.addAndGet(result.rejected());
        StringBuilder where = new StringBuilder();
        for (Warehouse.Allocation a : result.stored()) {
            where.append(where.length() == 0 ? "" : ", ").append(a.location().label()).append("×").append(a.quantity());
        }
        log.add("INVENTARIO", "Inventario registró " + origin + ": " + stored + " u. de " + product.name()
                + (stored > 0 ? " en " + where : ""));
        if (result.rejected() > 0) {
            log.add("ALMACEN_LLENO", "Almacén sin espacio: " + result.rejected() + " u. de " + product.name()
                    + " no cupieron");
        }
        if (stored > 0) {
            for (Runnable listener : stockListeners) {
                try {
                    listener.run();
                } catch (RuntimeException e) {
                    log.add("ERROR", "Listener de inventario: " + e);
                }
            }
        }
    }
}

package com.redxela.reception;

import com.redxela.domain.Product;
import com.redxela.domain.ResourceType;
import com.redxela.resource.ResourceManager;
import com.redxela.resource.ResourcePool;
import com.redxela.simulation.EventLog;
import com.redxela.simulation.SimClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Área de Recepción de Mercadería: donde llegan los camiones con stock nuevo.
 *
 * <p>No toca las estructuras de Inventario: solo escanea cada lote (usando el escáner único que
 * comparte con el despacho, por lo que compite con los pedidos que lo necesitan) y deja un
 * {@link ReceptionMessage} en el canal. El canal es una cola <strong>acotada</strong>: si Recepción
 * produce más rápido de lo que Inventario procesa, el envío espera (contrapresión) en lugar de
 * perder datos, y las llamadas interactivas tienen un tiempo límite para que nadie se bloquee
 * indefinidamente.
 */
public class ReceptionArea {

    private static final int MAX_LOT_UNITS = 10_000;

    private final BlockingQueue<ReceptionMessage> channel;
    private final ResourceManager resources;
    private final Map<Long, Product> catalog;
    private final SimClock clock;
    private final EventLog log;
    private final double scanMinutes;
    private final long timeoutMs;
    private final Random random;

    private final AtomicLong lotIds = new AtomicLong();
    private final AtomicLong sentLots = new AtomicLong();
    private final AtomicInteger burstRemaining = new AtomicInteger();
    private Thread burstThread;

    /**
     * @param scanMinutes minutos simulados que toma escanear un lote
     * @param timeoutMs   plazo real máximo para obtener el escáner y para entregar al canal
     */
    public ReceptionArea(BlockingQueue<ReceptionMessage> channel, ResourceManager resources,
                         Map<Long, Product> catalog, SimClock clock, EventLog log,
                         double scanMinutes, long timeoutMs, Random random) {
        this.channel = channel;
        this.resources = resources;
        this.catalog = catalog;
        this.clock = clock;
        this.log = log;
        this.scanMinutes = scanMinutes;
        this.timeoutMs = timeoutMs;
        this.random = random;
    }

    /**
     * Registra la llegada de un lote (ingreso manual): lo escanea y lo envía a Inventario.
     *
     * @throws IllegalArgumentException    si el producto no existe o la cantidad no es válida
     * @throws ReceptionOverloadedException si no se consiguió el escáner o el canal estaba lleno
     */
    public ReceptionMessage receive(long productId, int quantity) throws InterruptedException {
        Product product = catalog.get(productId);
        if (product == null) {
            throw new IllegalArgumentException("Producto inexistente: " + productId);
        }
        if (quantity < 1 || quantity > MAX_LOT_UNITS) {
            throw new IllegalArgumentException("La cantidad debe estar entre 1 y " + MAX_LOT_UNITS);
        }
        ReceptionMessage message = scan(productId, quantity);
        if (!channel.offer(message, timeoutMs, TimeUnit.MILLISECONDS)) {
            log.add("RECEPCION_SATURADA", "Inventario no alcanza a procesar: lote #" + message.id()
                    + " (" + product.name() + ") no fue aceptado, reintente");
            throw new ReceptionOverloadedException("Inventario está saturado; el lote no fue aceptado, reintente");
        }
        sentLots.incrementAndGet();
        log.add("RECEPCION", "Llegó lote #" + message.id() + ": " + quantity + " u. de " + product.name());
        return message;
    }

    /**
     * Simula un camión con varios lotes: se envían uno tras otro tan rápido como permite el escáner,
     * esperando (sin perder nada) cuando el canal está lleno. Un solo camión a la vez.
     *
     * @return {@code false} si ya hay un camión descargando
     */
    public synchronized boolean startBurst(int lots, int unitsPerLot) {
        if (lots < 1 || lots > 500 || unitsPerLot < 1 || unitsPerLot > MAX_LOT_UNITS) {
            throw new IllegalArgumentException("Camión inválido: 1-500 lotes de 1-" + MAX_LOT_UNITS + " unidades");
        }
        if (burstThread != null && burstThread.isAlive()) {
            return false;
        }
        burstRemaining.set(lots);
        List<Long> ids = new ArrayList<>(catalog.keySet());
        burstThread = new Thread(() -> runBurst(ids, unitsPerLot), "redxela-reception-burst");
        burstThread.setDaemon(true);
        burstThread.start();
        log.add("CAMION", "Llegó un camión con " + lots + " lotes de " + unitsPerLot + " u.");
        return true;
    }

    /** Detiene un camión en curso (se usa al apagar). */
    public synchronized void stop() {
        if (burstThread != null) {
            burstThread.interrupt();
        }
    }

    public int queueSize() {
        return channel.size();
    }

    public int queueCapacity() {
        return channel.size() + channel.remainingCapacity();
    }

    public long sentLots() {
        return sentLots.get();
    }

    /** Lotes del camión actual que aún no se envían. */
    public int burstRemaining() {
        return burstRemaining.get();
    }

    // ----------------------------------------------------------------- interno

    /** Obtiene el escáner compartido, escanea el lote y lo libera. */
    private ReceptionMessage scan(long productId, int quantity) throws InterruptedException {
        long lotId = lotIds.incrementAndGet();
        ResourcePool scanner = resources.pool(ResourceType.ESCANER);
        long owner = -lotId;                                 // ids negativos: lotes de recepción
        if (!scanner.tryAcquire(owner, timeoutMs)) {
            throw new ReceptionOverloadedException("El escáner está ocupado; reintente");
        }
        try {
            Thread.sleep(clock.toRealMillis(scanMinutes));
        } finally {
            scanner.release(owner);
        }
        return new ReceptionMessage(lotId, productId, quantity, clock.now());
    }

    private void runBurst(List<Long> productIds, int unitsPerLot) {
        try {
            while (burstRemaining.get() > 0) {
                long productId = productIds.get(random.nextInt(productIds.size()));
                ReceptionMessage message = scanBlocking(productId, unitsPerLot);
                channel.put(message);                         // contrapresión: espera si el canal está lleno
                sentLots.incrementAndGet();
                burstRemaining.decrementAndGet();
            }
            log.add("CAMION", "El camión terminó de descargar");
        } catch (InterruptedException e) {
            burstRemaining.set(0);
        }
    }

    /** Como {@link #scan}, pero reintenta hasta conseguir el escáner (el camión no desiste). */
    private ReceptionMessage scanBlocking(long productId, int quantity) throws InterruptedException {
        while (true) {
            try {
                return scan(productId, quantity);
            } catch (ReceptionOverloadedException e) {
                // escáner ocupado por el despacho: se vuelve a intentar
            }
        }
    }
}

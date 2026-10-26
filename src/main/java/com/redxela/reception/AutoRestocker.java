package com.redxela.reception;

import com.redxela.inventory.InventoryArea;
import com.redxela.simulation.EventLog;
import com.redxela.simulation.SimClock;

/**
 * Proveedores simulados: cada cierto tiempo revisa (solo lectura) las existencias y, para los
 * productos por debajo del punto de reposición, hace llegar un lote por Recepción. Mantiene la
 * simulación funcionando de forma continua cuando el generador de pedidos consume el almacén.
 */
public class AutoRestocker {

    private final InventoryArea inventory;
    private final ReceptionArea reception;
    private final SimClock clock;
    private final EventLog log;
    private final double checkEveryMinutes;
    private final int reorderPoint;
    private final int lotSize;

    private Thread thread;
    private volatile boolean running;

    public AutoRestocker(InventoryArea inventory, ReceptionArea reception, SimClock clock, EventLog log,
                         double checkEveryMinutes, int reorderPoint, int lotSize) {
        this.inventory = inventory;
        this.reception = reception;
        this.clock = clock;
        this.log = log;
        this.checkEveryMinutes = checkEveryMinutes;
        this.reorderPoint = reorderPoint;
        this.lotSize = lotSize;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "redxela-restocker");
        thread.setDaemon(true);
        thread.start();
        log.add("REPOSICION", "Reposición automática activada");
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        thread.interrupt();
        log.add("REPOSICION", "Reposición automática desactivada");
    }

    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                for (InventoryArea.ProductStock s : inventory.stock()) {
                    if (!running) {
                        return;
                    }
                    if (s.available() < reorderPoint) {
                        try {
                            reception.receive(s.product().id(), lotSize);
                        } catch (ReceptionOverloadedException e) {
                            // Sistema saturado: se reintenta en la próxima revisión
                        }
                    }
                }
                Thread.sleep(Math.max(1, clock.toRealMillis(checkEveryMinutes)));
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log.add("ERROR", "Reposición: " + e);
            }
        }
    }
}

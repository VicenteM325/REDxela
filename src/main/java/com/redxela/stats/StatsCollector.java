package com.redxela.stats;

import com.redxela.domain.Order;
import com.redxela.domain.ResourceType;
import com.redxela.domain.ServiceLevel;
import com.redxela.resource.ResourceManager;
import com.redxela.resource.ResourceSnapshot;
import com.redxela.simulation.SimClock;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Estadísticas generales del sistema: pedidos completados, tiempos promedio de espera y de
 * procesamiento, cumplimiento del tiempo objetivo de cada nivel y ocupación de los recursos.
 *
 * <p>Los pedidos terminados llegan desde los hilos worker (se registra como listener del
 * despachador); un hilo muestreador toma periódicamente la ocupación de cada recurso y la promedia
 * ponderando por el tiempo simulado transcurrido. Todo se protege con un solo monitor: las
 * operaciones son muy cortas.
 *
 * <p>Un pedido "cumple" si esperó, antes de iniciar, no más que el máximo de su nivel. Como el
 * Exprés exige procesamiento "casi instantáneo" (0 min), se le da una tolerancia de
 * {@value #MIN_TARGET_MINUTES} minuto simulado.
 */
public class StatsCollector {

    /** Tolerancia mínima del objetivo de espera, para que el nivel Exprés (0 min) sea medible. */
    public static final double MIN_TARGET_MINUTES = 1.0;

    /** Estadísticas de un nivel de servicio. */
    public record LevelStats(int level, String name, double targetMinutes, long completed,
                             double averageWaitMinutes, double averageProcessingMinutes, double slaCompliancePct) {
    }

    /** Ocupación de un recurso: instantánea y promedio desde el inicio. */
    public record ResourceUsage(ResourceType type, int capacity, int inUse, double currentPct, double averagePct) {
    }

    /** Foto de todas las estadísticas. */
    public record Snapshot(long completed, double averageWaitMinutes, double averageProcessingMinutes,
                           double slaCompliancePct, double throughputPerMinute, double elapsedMinutes,
                           List<LevelStats> levels, List<ResourceUsage> resources) {
    }

    private static final class Acc {
        long completed;
        double waitSum;
        double processingSum;
        long slaMet;
    }

    private final SimClock clock;
    private final ResourceManager resources;
    private final long samplePeriodMs;

    private final Object lock = new Object();
    private final Map<ServiceLevel, Acc> byLevel = new EnumMap<>(ServiceLevel.class);
    private final Map<ResourceType, Double> busyIntegral = new EnumMap<>(ResourceType.class);
    private double sampledMinutes;
    private double lastSampleAt;
    private double startedAt;

    private Thread thread;
    private volatile boolean running;

    public StatsCollector(SimClock clock, ResourceManager resources, long samplePeriodMs) {
        this.clock = clock;
        this.resources = resources;
        this.samplePeriodMs = samplePeriodMs;
        reset();
    }

    /** Arranca el muestreo de ocupación (idempotente). */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "redxela-stats");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        thread.interrupt();
    }

    /** Vuelve todos los contadores a cero (para medir desde un punto de partida limpio). */
    public void reset() {
        synchronized (lock) {
            byLevel.clear();
            for (ServiceLevel l : ServiceLevel.values()) {
                byLevel.put(l, new Acc());
            }
            for (ResourceType t : ResourceType.values()) {
                busyIntegral.put(t, 0.0);
            }
            sampledMinutes = 0;
            startedAt = clock.now();
            lastSampleAt = startedAt;
        }
    }

    /** Registra un pedido que terminó de procesarse. */
    public void onCompleted(Order order) {
        double wait = order.waitMinutes();
        double processing = order.finishedAt() - order.startedAt();
        double target = Math.max(order.serviceLevel().maxWaitMinutes(), MIN_TARGET_MINUTES);
        synchronized (lock) {
            Acc a = byLevel.get(order.serviceLevel());
            a.completed++;
            a.waitSum += wait;
            a.processingSum += processing;
            if (wait <= target) {
                a.slaMet++;
            }
        }
    }

    public Snapshot snapshot() {
        List<ResourceSnapshot> now = resources.snapshot();
        synchronized (lock) {
            long completed = 0;
            double wait = 0;
            double processing = 0;
            long met = 0;
            List<LevelStats> levels = new ArrayList<>();
            for (ServiceLevel l : ServiceLevel.values()) {
                Acc a = byLevel.get(l);
                completed += a.completed;
                wait += a.waitSum;
                processing += a.processingSum;
                met += a.slaMet;
                levels.add(new LevelStats(l.level(), l.displayName(), Math.max(l.maxWaitMinutes(), MIN_TARGET_MINUTES),
                        a.completed, avg(a.waitSum, a.completed), avg(a.processingSum, a.completed),
                        pct(a.slaMet, a.completed)));
            }
            List<ResourceUsage> usage = new ArrayList<>();
            for (ResourceSnapshot s : now) {
                int inUse = s.capacity() - s.available();
                double average = sampledMinutes == 0 ? 0 : busyIntegral.get(s.type()) / sampledMinutes;
                usage.add(new ResourceUsage(s.type(), s.capacity(), inUse,
                        100.0 * inUse / s.capacity(), 100.0 * average / s.capacity()));
            }
            double elapsed = clock.now() - startedAt;
            return new Snapshot(completed, avg(wait, completed), avg(processing, completed), pct(met, completed),
                    elapsed > 0 ? completed / elapsed : 0, elapsed, levels, usage);
        }
    }

    // ----------------------------------------------------------------- interno

    private void loop() {
        while (running) {
            try {
                Thread.sleep(samplePeriodMs);
                sample();
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Acumula la ocupación actual ponderada por el tiempo simulado desde la muestra anterior. */
    void sample() {
        List<ResourceSnapshot> now = resources.snapshot();
        synchronized (lock) {
            double t = clock.now();
            double dt = t - lastSampleAt;
            lastSampleAt = t;
            if (dt <= 0) {
                return;
            }
            sampledMinutes += dt;
            for (ResourceSnapshot s : now) {
                busyIntegral.merge(s.type(), (s.capacity() - s.available()) * dt, Double::sum);
            }
        }
    }

    private static double avg(double sum, long n) {
        return n == 0 ? 0 : sum / n;
    }

    private static double pct(long part, long total) {
        return total == 0 ? 0 : 100.0 * part / total;
    }
}

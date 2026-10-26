package com.redxela.simulation;

import com.redxela.domain.*;
import com.redxela.persistence.CatalogRepository;
import com.redxela.processing.Dispatcher;
import com.redxela.resource.ResourceManager;
import com.redxela.scheduler.SchedulingPolicies;
import com.redxela.scheduler.SchedulingPolicy;
import com.redxela.scheduler.WaitingList;

import java.util.*;

/**
 * Compara el desempeño de las políticas de atención antes de elegir una para producción.
 *
 * <p>Genera <strong>una sola carga</strong> reproducible (misma semilla ⇒ mismas llegadas, niveles y
 * tamaños) y se la aplica por separado a cada política, en un sistema aislado (recursos, lista de
 * espera y despachador propios, sin inventario) y con el tiempo acelerado. Usa el mismo
 * {@link Dispatcher} que el sistema real, así que mide el comportamiento verdadero y no un modelo
 * aparte. Como corre con hilos reales hay una pequeña variación entre ejecuciones.
 *
 * <p>Al cortar en el horizonte quedan pedidos sin iniciar. Su espera se toma como la edad que
 * llevan (observación censurada: la espera real sería al menos esa) y el cumplimiento solo cuenta
 * pedidos con desenlace ya decidido (iniciaron, o ya superaron su tiempo objetivo), para no premiar
 * a una política por dejar pedidos sin atender.
 */
public class PolicyComparator {

    /** Parámetros de la comparación. */
    public record Params(double horizonMinutes, double ratePerMinute, long seed, double msPerSimMinute) {
        public Params {
            if (horizonMinutes < 10 || horizonMinutes > 600) {
                throw new IllegalArgumentException("El horizonte debe estar entre 10 y 600 minutos");
            }
            if (ratePerMinute < 0.1 || ratePerMinute > 20) {
                throw new IllegalArgumentException("La tasa debe estar entre 0.1 y 20 pedidos por minuto");
            }
            if (msPerSimMinute < 2 || msPerSimMinute > 200) {
                throw new IllegalArgumentException("La escala debe estar entre 2 y 200 ms por minuto simulado");
            }
        }

        public static Params defaults() {
            return new Params(90, 4.0, 42, 20);
        }
    }

    /** Resultado de un nivel de servicio. */
    public record LevelResult(int level, String name, int arrived, double averageWaitMinutes, double slaCompliancePct) {
    }

    /** Resultado de una política. */
    public record PolicyResult(String policyId, String name, int arrived, int completed, int started,
                               double averageWaitMinutes, double maxWaitMinutes, double slaCompliancePct,
                               double throughputPerMinute, List<LevelResult> levels) {
    }

    /** Estado de la comparación (para consultarla mientras corre). */
    public record Status(String state, int policiesDone, int policiesTotal, String currentPolicy, Params params,
                         List<PolicyResult> results, String best, String error) {
    }

    private record Arrival(double minute, ServiceLevel level, List<OrderItem> items) {
    }

    private final CatalogRepository catalog;
    private final WorkEstimator estimator;

    private final Object lock = new Object();
    private String state = "IDLE";
    private int done;
    private int total;
    private String current;
    private Params params;
    private List<PolicyResult> results = List.of();
    private String error;
    private Thread thread;

    public PolicyComparator(CatalogRepository catalog, WorkEstimator estimator) {
        this.catalog = catalog;
        this.estimator = estimator;
    }

    /** Inicia una comparación en segundo plano. @return {@code false} si ya hay una en curso */
    public boolean start(Params p) {
        synchronized (lock) {
            if ("RUNNING".equals(state)) {
                return false;
            }
            state = "RUNNING";
            params = p;
            done = 0;
            total = SchedulingPolicies.all().size();
            current = null;
            results = List.of();
            error = null;
            thread = new Thread(() -> run(p), "redxela-comparator");
            thread.setDaemon(true);
            thread.start();
            return true;
        }
    }

    public Status status() {
        synchronized (lock) {
            return new Status(state, done, total, current, params, List.copyOf(results), bestOf(results), error);
        }
    }

    public void stop() {
        synchronized (lock) {
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    // ----------------------------------------------------------------- interno

    private void run(Params p) {
        try {
            List<Arrival> workload = buildWorkload(p);
            List<PolicyResult> out = new ArrayList<>();
            for (SchedulingPolicy policy : SchedulingPolicies.all()) {
                synchronized (lock) {
                    current = policy.name();
                }
                out.add(runPolicy(policy, workload, p));
                synchronized (lock) {
                    done++;
                    results = List.copyOf(out);
                }
            }
            synchronized (lock) {
                state = "DONE";
                current = null;
            }
        } catch (InterruptedException e) {
            synchronized (lock) {
                state = "FAILED";
                error = "Comparación interrumpida";
            }
        } catch (RuntimeException e) {
            synchronized (lock) {
                state = "FAILED";
                error = String.valueOf(e.getMessage());
            }
        }
    }

    private List<Arrival> buildWorkload(Params p) {
        Random random = new Random(p.seed());
        OrderMix mix = new OrderMix(random, catalog.findProducts());
        List<Arrival> arrivals = new ArrayList<>();
        double t = 0;
        while (true) {
            t += -Math.log(1 - random.nextDouble()) / p.ratePerMinute();   // llegadas de Poisson
            if (t >= p.horizonMinutes()) {
                return arrivals;
            }
            List<OrderItem> items = mix.nextLines().stream()
                    .map(l -> new OrderItem(l.product(), l.quantity())).toList();
            arrivals.add(new Arrival(t, mix.nextLevel(), items));
        }
    }

    private PolicyResult runPolicy(SchedulingPolicy policy, List<Arrival> workload, Params p) throws InterruptedException {
        SimClock clock = new SimClock(p.msPerSimMinute());
        Dispatcher dispatcher = new Dispatcher(new WaitingList(), new ResourceManager(), clock,
                new EventLog(clock, 10), policy, 500, 0);
        Customer customer = new Customer(0, "Comparador", "comparador@redxela.sim", "!", ServiceLevel.ESTANDAR);
        List<Order> orders = new ArrayList<>();
        dispatcher.start();
        double end;
        try {
            long id = 0;
            for (Arrival a : workload) {
                sleepUntil(clock, a.minute());
                Order o = new Order(++id, customer, a.level(), a.items(), clock.now(),
                        estimator.workMinutes(a.items().stream().mapToInt(OrderItem::quantity).sum()));
                orders.add(o);
                dispatcher.submit(o);
            }
            sleepUntil(clock, p.horizonMinutes());
            end = clock.now();
            return summarize(policy, orders, end, p.horizonMinutes());
        } finally {
            dispatcher.stop();
        }
    }

    private static void sleepUntil(SimClock clock, double minute) throws InterruptedException {
        while (clock.now() < minute) {
            Thread.sleep(Math.max(1, clock.toRealMillis(minute - clock.now())));
        }
    }

    private PolicyResult summarize(SchedulingPolicy policy, List<Order> orders, double end, double horizon) {
        Acc all = new Acc();
        Map<ServiceLevel, Acc> byLevel = new EnumMap<>(ServiceLevel.class);
        for (ServiceLevel l : ServiceLevel.values()) {
            byLevel.put(l, new Acc());
        }
        int started = 0;
        int completed = 0;
        for (Order o : orders) {
            boolean hasStarted = !Double.isNaN(o.firstStartedAt());
            // Si no inició, la espera real es al menos la edad actual (observación censurada)
            double wait = hasStarted ? o.firstStartedAt() - o.createdAt() : end - o.createdAt();
            double target = Math.max(o.serviceLevel().maxWaitMinutes(), com.redxela.stats.StatsCollector.MIN_TARGET_MINUTES);
            boolean decided = hasStarted || wait > target;
            boolean met = hasStarted && wait <= target;
            if (hasStarted) {
                started++;
            }
            if (!Double.isNaN(o.finishedAt())) {
                completed++;
            }
            all.add(wait, decided, met);
            byLevel.get(o.serviceLevel()).add(wait, decided, met);
        }
        List<LevelResult> levels = new ArrayList<>();
        for (ServiceLevel l : ServiceLevel.values()) {
            Acc a = byLevel.get(l);
            levels.add(new LevelResult(l.level(), l.displayName(), a.arrived, a.averageWait(), a.slaPct()));
        }
        return new PolicyResult(policy.id(), policy.name(), orders.size(), completed, started, all.averageWait(),
                all.maxWait, all.slaPct(), completed / horizon, levels);
    }

    /** La mejor política: mayor cumplimiento; a igualdad, menor espera promedio. */
    private static String bestOf(List<PolicyResult> results) {
        return results.stream()
                .max(Comparator.comparingDouble(PolicyResult::slaCompliancePct)
                        .thenComparingDouble(r -> -r.averageWaitMinutes()))
                .map(PolicyResult::policyId).orElse(null);
    }

    /** Acumulador de espera y cumplimiento. */
    private static final class Acc {
        int arrived;
        int decided;
        int met;
        double waitSum;
        double maxWait;

        void add(double wait, boolean wasDecided, boolean wasMet) {
            arrived++;
            waitSum += wait;
            maxWait = Math.max(maxWait, wait);
            if (wasDecided) {
                decided++;
            }
            if (wasMet) {
                met++;
            }
        }

        double averageWait() {
            return arrived == 0 ? 0 : waitSum / arrived;
        }

        double slaPct() {
            return decided == 0 ? 0 : 100.0 * met / decided;
        }
    }
}

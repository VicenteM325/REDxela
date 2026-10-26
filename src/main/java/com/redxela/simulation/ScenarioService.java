package com.redxela.simulation;

import com.redxela.domain.Order;
import com.redxela.domain.Product;
import com.redxela.domain.ServiceLevel;
import com.redxela.inventory.InventoryArea;
import com.redxela.persistence.CatalogRepository;
import com.redxela.processing.Dispatcher;
import com.redxela.processing.OrderService;
import com.redxela.reception.ReceptionArea;
import com.redxela.reception.ReceptionOverloadedException;
import com.redxela.resource.AcquisitionMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Escenarios de demostración para provocar deliberadamente las situaciones que describe el
 * enunciado sin depender de que el azar las produzca.
 */
public class ScenarioService {

    /** Resultado de armar un escenario. */
    public record ScenarioResult(String scenario, String description, String acquisitionMode, List<Long> orderIds,
                                 boolean generatorStopped, String warning) {
    }

    private static final int UNITS = 4;
    /** Plazo real máximo para que el reabastecimiento pedido a Recepción llegue al almacén. */
    private static final long RESTOCK_WAIT_MS = 5000;

    private final OrderService orders;
    private final OrderGenerator generator;
    private final Dispatcher dispatcher;
    private final CatalogRepository catalog;
    private final InventoryArea inventory;
    private final ReceptionArea reception;
    private final EventLog log;

    public ScenarioService(OrderService orders, OrderGenerator generator, Dispatcher dispatcher,
                           CatalogRepository catalog, InventoryArea inventory, ReceptionArea reception, EventLog log) {
        this.orders = orders;
        this.generator = generator;
        this.dispatcher = dispatcher;
        this.catalog = catalog;
        this.inventory = inventory;
        this.reception = reception;
        this.log = log;
    }

    /**
     * Conflicto de recursos: 3 pedidos PESADOS (retienen los 3 montacargas y piden área de carga) y
     * 2 VOLUMINOSOS (retienen las 2 áreas de carga y piden montacargas). Cada grupo pide los mismos
     * dos recursos que el otro en sentido inverso: espera circular. Requiere el modo NATURAL, que se
     * activa aquí, y se detiene el generador para que ningún otro pedido altere el entrelazado.
     */
    public ScenarioResult provokeDeadlock() throws InterruptedException {
        boolean wasRunning = generator.isRunning();
        generator.stop();
        dispatcher.setMode(AcquisitionMode.NATURAL);
        String warning = busyWarning();
        // Se asegura el stock de ambos tipos ANTES de crear ningún pedido: si alguno esperara existencias
        // arrancaría escalonado y el entrelazado que forma el ciclo no se produciría
        ensureStock("PESADO", 3);
        ensureStock("VOLUMINOSO", 2);
        List<Long> ids = new ArrayList<>();
        ids.addAll(submit("PESADO", 3));
        ids.addAll(submit("VOLUMINOSO", 2));
        log.add("ESCENARIO", "Escenario de deadlock armado: pedidos " + ids.stream().map(i -> "#" + i).toList());
        return new ScenarioResult("deadlock",
                "3 pedidos pesados retienen los montacargas y esperan el área de carga; 2 voluminosos retienen "
                        + "el área de carga y esperan montacargas. Espere unos segundos a que se forme el ciclo.",
                dispatcher.mode().name(), ids, wasRunning, warning);
    }

    /**
     * Contención: pedidos pesados y voluminosos compiten por los mismos dos recursos compartidos
     * (montacargas y área de carga) en momentos cercanos; con adquisición atómica el sistema los
     * serializa mediante la lista de espera sin bloquearse.
     */
    public ScenarioResult provokeContention() throws InterruptedException {
        dispatcher.setMode(AcquisitionMode.ATOMIC);
        ensureStock("PESADO", 3);
        ensureStock("VOLUMINOSO", 3);
        List<Long> ids = new ArrayList<>();
        ids.addAll(submit("PESADO", 3));
        ids.addAll(submit("VOLUMINOSO", 3));
        log.add("ESCENARIO", "Escenario de contención armado: pedidos " + ids.stream().map(i -> "#" + i).toList());
        return new ScenarioResult("contention",
                "3 pedidos pesados y 3 voluminosos necesitan montacargas y área de carga a la vez: los que no "
                        + "alcanzan pasan a la lista de espera.",
                dispatcher.mode().name(), ids, false, null);
    }

    /** Aviso si hay otros pedidos compitiendo por recursos, que pueden alterar el entrelazado del escenario. */
    private String busyWarning() {
        int active = dispatcher.activeOrderCount();
        return active == 0 ? null : "Hay " + active + " pedidos en curso o en espera de recursos; para un escenario "
                + "limpio detenga el generador y espere a que el sistema se vacíe.";
    }

    /** Producto con que se arma el escenario para un tipo de mercancía. */
    private Product productOf(String typeCode) {
        return catalog.findProducts().stream()
                .filter(p -> p.merchandiseType().code().equals(typeCode)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No hay productos del tipo " + typeCode));
    }

    /**
     * Garantiza existencias para {@code count} pedidos del tipo: si faltan, hace llegar mercadería por
     * Recepción y espera a que Inventario la registre, de modo que todos los pedidos puedan iniciar a la vez.
     */
    private void ensureStock(String typeCode, int count) throws InterruptedException {
        Product product = productOf(typeCode);
        int needed = count * UNITS;
        if (availableOf(product) >= needed) {
            return;
        }
        try {
            reception.receive(product.id(), needed * 3);
        } catch (ReceptionOverloadedException e) {
            return;               // los pedidos esperarán existencias hasta que llegue la mercadería
        }
        long limit = System.currentTimeMillis() + RESTOCK_WAIT_MS;
        while (availableOf(product) < needed && System.currentTimeMillis() < limit) {
            Thread.sleep(25);
        }
    }

    private int availableOf(Product product) {
        return inventory.stock().stream().filter(s -> s.product().id() == product.id())
                .mapToInt(InventoryArea.ProductStock::available).findFirst().orElse(0);
    }

    /** Crea {@code count} pedidos Exprés de un tipo de mercancía. */
    private List<Long> submit(String typeCode, int count) {
        Product product = productOf(typeCode);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Order o = orders.create(generator.systemCustomer(ServiceLevel.EXPRES), ServiceLevel.EXPRES,
                    List.of(new OrderService.ItemRequest(product.id(), UNITS)));
            ids.add(o.id());
        }
        return ids;
    }
}

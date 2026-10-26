package com.redxela.testsupport;

import com.redxela.domain.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.redxela.domain.ResourceType.*;

/** Fábrica de objetos de dominio para pruebas unitarias (sin base de datos). */
public final class TestData {

    private static final AtomicLong IDS = new AtomicLong();

    public static final MerchandiseType FRAGIL = new MerchandiseType("FRAGIL", "Frágil", "d",
            List.of(ENCARGADO, EMPAQUE, CONTROL_CALIDAD));
    public static final MerchandiseType PESADO = new MerchandiseType("PESADO", "Pesado", "d",
            List.of(ENCARGADO, MONTACARGAS, AREA_CARGA));
    public static final MerchandiseType VOLUMINOSO = new MerchandiseType("VOLUMINOSO", "Voluminoso", "d",
            List.of(EMPAQUE, AREA_CARGA, MONTACARGAS));
    public static final MerchandiseType DOCUMENTOS = new MerchandiseType("DOCUMENTOS", "Documentos", "d",
            List.of(ENCARGADO, ESCANER));
    public static final MerchandiseType ELECTRONICO = new MerchandiseType("ELECTRONICO", "Electrónico", "d",
            List.of(ESCANER, CONTROL_CALIDAD, EMPAQUE));

    public static final Customer CUSTOMER = new Customer(1, "Cliente", "c@test", "hash", ServiceLevel.ESTANDAR);

    private TestData() {
    }

    public static Product product(MerchandiseType type) {
        long id = IDS.incrementAndGet();
        return new Product(id, "T-" + id, "Producto " + id, type);
    }

    /** Pedido de un solo producto del tipo dado, con las unidades y el nivel indicados. */
    public static Order order(MerchandiseType type, ServiceLevel level, int units, double createdAt) {
        return new Order(IDS.incrementAndGet(), CUSTOMER, level,
                List.of(new OrderItem(product(type), units)), createdAt, units * 0.25);
    }

    public static Order order(MerchandiseType type, ServiceLevel level, int units) {
        return order(type, level, units, 0);
    }
}

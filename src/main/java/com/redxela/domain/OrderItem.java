package com.redxela.domain;

/** Línea de un pedido: producto solicitado y cantidad de unidades. */
public record OrderItem(Product product, int quantity) {

    public OrderItem {
        if (quantity <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser positiva");
        }
    }
}

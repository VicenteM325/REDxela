package com.redxela.processing;

import com.redxela.domain.Order;

/**
 * Puerta de acceso del despachador al inventario. El despachador no toca las estructuras del
 * almacén: solo pregunta si hay existencias y las reserva, confirma o devuelve.
 */
public interface StockGate {

    /** Hay existencias disponibles (no reservadas) para todas las líneas del pedido. */
    boolean hasStock(Order order);

    /** Reserva todas las líneas o ninguna. @return {@code false} si ya no alcanzan */
    boolean tryReserve(Order order);

    /** El pedido terminó: las unidades reservadas salen definitivamente del almacén. */
    void commit(Order order);

    /** El pedido no terminó (preempción, fallo, cierre): las unidades reservadas vuelven a estar disponibles. */
    void release(Order order);

    /** Implementación sin límite de existencias, para pruebas del despachador aisladas. */
    StockGate UNLIMITED = new StockGate() {
        @Override
        public boolean hasStock(Order order) {
            return true;
        }

        @Override
        public boolean tryReserve(Order order) {
            return true;
        }

        @Override
        public void commit(Order order) {
        }

        @Override
        public void release(Order order) {
        }
    };
}

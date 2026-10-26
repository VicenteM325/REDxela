package com.redxela.api;

/** El recurso pedido (pedido, producto…) no existe. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}

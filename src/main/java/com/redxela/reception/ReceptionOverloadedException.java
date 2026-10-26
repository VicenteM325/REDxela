package com.redxela.reception;

/**
 * Recepción no pudo entregar el lote a tiempo (escáner ocupado o canal hacia Inventario lleno).
 * El lote <strong>no se pierde</strong>: simplemente no fue aceptado y el llamador puede reintentar.
 */
public class ReceptionOverloadedException extends RuntimeException {
    public ReceptionOverloadedException(String message) {
        super(message);
    }
}

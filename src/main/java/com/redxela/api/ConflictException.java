package com.redxela.api;

/** La operación no es válida en el estado actual (p. ej. ya hay un camión descargando). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}

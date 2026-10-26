package com.redxela.resource;

/**
 * Estrategia con la que un pedido obtiene los recursos que necesita. Cada una corresponde a una
 * forma distinta de tratar el deadlock (condiciones de Coffman):
 */
public enum AcquisitionMode {

    /**
     * Todo o nada: el pedido toma todos sus recursos de una vez o no toma ninguno. Niega la
     * condición de "retener y esperar", por lo que el deadlock es imposible. Es el modo por defecto.
     */
    ATOMIC,

    /**
     * Adquisición incremental siguiendo un orden global fijo de recursos (el del enum
     * {@code ResourceType}). Niega la "espera circular": el deadlock es imposible.
     */
    ORDERED,

    /**
     * Adquisición incremental en el orden propio de cada tipo de mercancía. No previene nada:
     * dos tipos que piden los mismos recursos en sentido inverso pueden bloquearse entre sí. Se usa
     * para provocar el conflicto deliberadamente (detección y recuperación manual).
     */
    NATURAL
}

package com.redxela.domain;

/** Ciclo de vida de un pedido dentro del sistema. */
public enum OrderStatus {
    /** En la lista de espera, aún sin recursos asignados. */
    EN_ESPERA,
    /** Esperando que Inventario reciba existencias de algún producto. */
    ESPERANDO_INVENTARIO,
    /** Un worker lo está procesando y mantiene recursos. */
    EN_PROCESO,
    /** Involucrado en un conflicto de recursos (deadlock) pendiente de resolución. */
    BLOQUEADO,
    COMPLETADO
}

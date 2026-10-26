package com.redxela.reception;

/**
 * Aviso que Recepción envía a Inventario: qué mercadería llegó y en qué cantidad. Es inmutable,
 * de modo que pasa de un área a la otra sin compartir estructuras mutables.
 *
 * @param id          consecutivo del lote recibido
 * @param productId   producto del catálogo
 * @param quantity    unidades recibidas
 * @param sentAtMinute minuto simulado en que Recepción lo envió
 */
public record ReceptionMessage(long id, long productId, int quantity, double sentAtMinute) {

    public ReceptionMessage {
        if (quantity <= 0) {
            throw new IllegalArgumentException("La cantidad recibida debe ser positiva");
        }
    }
}

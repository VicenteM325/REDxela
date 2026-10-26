package com.redxela.deadlock;

import java.util.List;

/**
 * Descripción de un deadlock confirmado, pensada para mostrarse tal cual en la interfaz: qué
 * recursos están involucrados, qué pedidos se bloquean entre sí y por qué.
 *
 * @param detectedAtMinute minuto simulado en que se confirmó
 * @param orders           pedidos bloqueados, cada uno con lo que retiene y lo que espera
 * @param resources        recursos disputados (los que los pedidos bloqueados están esperando)
 * @param explanation      explicación en lenguaje llano de la espera circular
 */
public record DeadlockReport(double detectedAtMinute, List<BlockedOrder> orders, List<String> resources,
                             String explanation) {

    /**
     * @param holds     recursos que el pedido retiene
     * @param waitsFor  recurso que necesita y no puede obtener
     * @param blockedBy pedidos bloqueados que tienen ese recurso
     */
    public record BlockedOrder(long orderId, String serviceLevel, int totalUnits, List<String> holds,
                               String waitsFor, List<Long> blockedBy) {
    }

    public boolean involves(long orderId) {
        return orders.stream().anyMatch(o -> o.orderId() == orderId);
    }
}

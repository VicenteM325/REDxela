package com.redxela.api;

import java.util.List;

/**
 * Foto completa del sistema para la interfaz: reloj, política, modo, generador, lista de espera
 * (ya ordenada según la política), pedidos en proceso y estado de cada recurso.
 */
public record StateDto(ClockDto clock, PolicyDto policy, String acquisitionMode, GeneratorDto generator,
                       List<OrderDto> waiting, List<OrderDto> running, List<ResourceDto> resources,
                       int retainedOrders) {

    public record ClockDto(double nowMinutes, double speed) {
    }

    public record PolicyDto(String id, String name, String description) {
    }

    public record GeneratorDto(boolean running, double ratePerSimMinute) {
    }

    /**
     * @param holders ids de los pedidos que lo usan ahora ("en uso, y por quién")
     * @param waiters ids de los pedidos bloqueados esperándolo
     */
    public record ResourceDto(String type, String name, int capacity, int available, int inUse,
                              List<Long> holders, List<Long> waiters) {
    }
}

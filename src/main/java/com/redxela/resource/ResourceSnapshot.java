package com.redxela.resource;

import com.redxela.domain.ResourceType;

import java.util.List;

/**
 * Foto consistente del estado de un recurso compartido, para la interfaz y el detector de deadlock.
 *
 * @param holders ids de los pedidos que lo usan ahora
 * @param waiters ids de los pedidos bloqueados esperándolo, en orden de llegada
 */
public record ResourceSnapshot(ResourceType type, int capacity, int available,
                               List<Long> holders, List<Long> waiters) {
}

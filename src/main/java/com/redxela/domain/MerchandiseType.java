package com.redxela.domain;

import java.util.List;

/**
 * Tipo de mercancía: define qué recursos necesita un pedido para procesarse.
 *
 * @param code        identificador estable (p. ej. FRAGIL)
 * @param name        nombre legible
 * @param description características de la mercancía
 * @param resources   recursos requeridos, en el orden "natural" de adquisición del tipo. Ese orden
 *                    es el que permite provocar deadlock deliberadamente cuando dos tipos piden
 *                    los mismos recursos en sentido inverso.
 */
public record MerchandiseType(String code, String name, String description, List<ResourceType> resources) {

    public MerchandiseType {
        resources = List.copyOf(resources);
    }
}

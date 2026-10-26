package com.redxela.domain;

/** Recursos compartidos del centro de distribución y su capacidad fija (según el enunciado). */
public enum ResourceType {

    EMPAQUE("Estación de empaque", 6),
    AREA_CARGA("Área de carga", 2),
    ENCARGADO("Encargado de bodega", 10),
    MONTACARGAS("Montacargas", 3),
    CONTROL_CALIDAD("Control de calidad", 4),
    /** Sistema de escaneo único, compartido por recepción y despacho (exclusión mutua). */
    ESCANER("Sistema de escaneo", 1);

    private final String displayName;
    private final int capacity;

    ResourceType(String displayName, int capacity) {
        this.displayName = displayName;
        this.capacity = capacity;
    }

    public String displayName() {
        return displayName;
    }

    public int capacity() {
        return capacity;
    }
}

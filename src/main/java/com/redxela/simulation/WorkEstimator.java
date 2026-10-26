package com.redxela.simulation;

/**
 * Estima el trabajo de un pedido a partir de sus unidades: un pedido con más unidades usa los
 * recursos durante más tiempo que uno con pocas, como exige el enunciado.
 */
public class WorkEstimator {

    private final double minutesPerUnit;

    public WorkEstimator(double minutesPerUnit) {
        if (minutesPerUnit <= 0) {
            throw new IllegalArgumentException("El tiempo por unidad debe ser positivo");
        }
        this.minutesPerUnit = minutesPerUnit;
    }

    /** Minutos simulados de uso de recursos para un pedido de {@code units} artículos. */
    public double workMinutes(int units) {
        return units * minutesPerUnit;
    }
}

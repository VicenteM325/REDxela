package com.redxela.domain;

/**
 * Nivel de servicio contratado por el cliente. Determina cuándo debe atenderse un pedido
 * respecto a los demás que esperan (la mercancía determina, en cambio, qué recursos usa).
 */
public enum ServiceLevel {

    EXPRES(1, "Exprés", 0),
    PRIORITARIO(2, "Prioritario", 10),
    ESTANDAR(3, "Estándar", 30),
    PROGRAMADO(4, "Programado", 60),
    ECONOMICO(5, "Económico", 120);

    private final int level;
    private final String displayName;
    private final int maxWaitMinutes;

    ServiceLevel(int level, String displayName, int maxWaitMinutes) {
        this.level = level;
        this.displayName = displayName;
        this.maxWaitMinutes = maxWaitMinutes;
    }

    /** Número de nivel (1 = más urgente). */
    public int level() {
        return level;
    }

    public String displayName() {
        return displayName;
    }

    /** Tiempo máximo objetivo en espera, en minutos simulados (0 = casi instantáneo). */
    public int maxWaitMinutes() {
        return maxWaitMinutes;
    }

    /** Obtiene el nivel por su número (1..5); lanza {@link IllegalArgumentException} si no existe. */
    public static ServiceLevel fromLevel(int level) {
        for (ServiceLevel s : values()) {
            if (s.level == level) {
                return s;
            }
        }
        throw new IllegalArgumentException("Nivel de servicio inexistente: " + level);
    }
}

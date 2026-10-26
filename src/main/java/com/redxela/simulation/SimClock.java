package com.redxela.simulation;

/**
 * Reloj simulado. Todas las esperas y duraciones del sistema se expresan en minutos simulados;
 * el reloj las traduce a milisegundos reales según una escala base y una velocidad ajustable en
 * caliente, de modo que "10 minutos de espera" no obligue a esperar 10 minutos reales.
 *
 * <p>Cuando cambia la velocidad se re-ancla el tiempo acumulado, así {@link #now()} nunca
 * retrocede ni da saltos. Los métodos son sincronizados porque lo consultan muchos hilos.
 */
public class SimClock {

    private final double baseMsPerSimMinute;
    private long anchorNanos = System.nanoTime();
    private double anchorSimMinutes;
    private double speed = 1.0;

    /** @param baseMsPerSimMinute milisegundos reales que dura un minuto simulado a velocidad 1x */
    public SimClock(double baseMsPerSimMinute) {
        if (baseMsPerSimMinute <= 0) {
            throw new IllegalArgumentException("La escala debe ser positiva");
        }
        this.baseMsPerSimMinute = baseMsPerSimMinute;
    }

    /** Minutos simulados transcurridos desde que se creó el reloj. */
    public synchronized double now() {
        double realMs = (System.nanoTime() - anchorNanos) / 1_000_000.0;
        return anchorSimMinutes + realMs / baseMsPerSimMinute * speed;
    }

    /** Cambia la velocidad (1.0 = normal, 2.0 = el doble de rápido). */
    public synchronized void setSpeed(double newSpeed) {
        if (newSpeed < 0.1 || newSpeed > 100) {
            throw new IllegalArgumentException("La velocidad debe estar entre 0.1 y 100");
        }
        anchorSimMinutes = now();
        anchorNanos = System.nanoTime();
        speed = newSpeed;
    }

    public synchronized double speed() {
        return speed;
    }

    /** Milisegundos reales que equivalen, a la velocidad actual, a los minutos simulados dados. */
    public synchronized long toRealMillis(double simMinutes) {
        return Math.max(0, Math.round(simMinutes * baseMsPerSimMinute / speed));
    }
}

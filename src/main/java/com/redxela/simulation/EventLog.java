package com.redxela.simulation;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bitácora acotada de eventos del sistema (últimos N), pensada para el panel de eventos de la
 * interfaz y para explicar qué ocurre mientras corre la simulación. Es segura entre hilos.
 */
public class EventLog {

    /** Un evento registrado. */
    public record Event(long seq, double simMinute, Instant wallTime, String type, String message) {
    }

    private final SimClock clock;
    private final int capacity;
    private final Deque<Event> events = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong();

    public EventLog(SimClock clock, int capacity) {
        this.clock = clock;
        this.capacity = capacity;
    }

    /** Registra un evento; descarta el más antiguo si la bitácora está llena. */
    public void add(String type, String message) {
        Event e = new Event(sequence.incrementAndGet(), clock.now(), Instant.now(), type, message);
        synchronized (events) {
            if (events.size() == capacity) {
                events.removeFirst();
            }
            events.addLast(e);
        }
    }

    /** Los últimos {@code max} eventos, del más reciente al más antiguo. */
    public List<Event> recent(int max) {
        List<Event> copy;
        synchronized (events) {
            copy = new ArrayList<>(events);
        }
        List<Event> out = new ArrayList<>();
        for (int i = copy.size() - 1; i >= 0 && out.size() < max; i--) {
            out.add(copy.get(i));
        }
        return out;
    }
}

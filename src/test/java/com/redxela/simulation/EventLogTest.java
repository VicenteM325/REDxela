package com.redxela.simulation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class EventLogTest {

    @Test
    void conservaSoloLosUltimosEventosYDevuelveElMasRecientePrimero() {
        EventLog log = new EventLog(new SimClock(1000), 3);
        for (int i = 1; i <= 5; i++) {
            log.add("T", "e" + i);
        }
        List<EventLog.Event> ev = log.recent(10);
        assertEquals(3, ev.size());
        assertEquals("e5", ev.get(0).message());
        assertEquals("e3", ev.get(2).message());
        assertEquals(1, log.recent(1).size());
    }

    @Test
    void esSeguroConEscritoresConcurrentes() throws Exception {
        EventLog log = new EventLog(new SimClock(1000), 1000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < 8; t++) {
            pool.submit(() -> {
                go.await();
                for (int i = 0; i < 100; i++) log.add("T", "x");
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        List<EventLog.Event> ev = log.recent(1000);
        assertEquals(800, ev.size());
        assertEquals(800, ev.stream().map(EventLog.Event::seq).distinct().count(), "las secuencias deben ser únicas");
    }
}

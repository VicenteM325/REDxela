package com.redxela.resource;

import com.redxela.domain.Order;
import com.redxela.domain.ResourceType;
import com.redxela.domain.ServiceLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.redxela.testsupport.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourcePoolTest {

    private static Order newOrder() {
        return order(FRAGIL, ServiceLevel.ESTANDAR, 1);
    }

    @Test
    void nuncaHayMasTitularesQueLaCapacidad() throws Exception {
        ResourcePool pool = new ResourcePool(ResourceType.MONTACARGAS); // capacidad 3
        int hilos = 20;
        AtomicInteger dentro = new AtomicInteger();
        AtomicInteger maximo = new AtomicInteger();
        ExecutorService ex = Executors.newFixedThreadPool(hilos);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            Order o = newOrder();
            fs.add(ex.submit(() -> {
                for (int k = 0; k < 20; k++) {
                    pool.acquire(o);
                    int now = dentro.incrementAndGet();
                    maximo.accumulateAndGet(now, Math::max);
                    Thread.sleep(1);
                    dentro.decrementAndGet();
                    pool.release(o);
                }
                return null;
            }));
        }
        for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        ex.shutdown();

        assertTrue(maximo.get() <= 3, "máximo simultáneo: " + maximo.get());
        assertEquals(3, pool.snapshot().available());
        assertTrue(pool.snapshot().holders().isEmpty());
    }

    @Test
    void elEscanerEsUnMutex() {
        ResourcePool scanner = new ResourcePool(ResourceType.ESCANER);
        Order a = newOrder(), b = newOrder();
        assertTrue(scanner.tryAcquire(a));
        assertFalse(scanner.tryAcquire(b));
        assertTrue(scanner.isHeldBy(a));
        scanner.release(a);
        assertTrue(scanner.tryAcquire(b));
    }

    @Test
    void registraTitularesYEsperasYSoltarEsIdempotente() throws Exception {
        ResourcePool scanner = new ResourcePool(ResourceType.ESCANER);
        Order a = newOrder(), b = newOrder();
        scanner.acquire(a);

        Thread t = new Thread(() -> {
            try {
                scanner.acquire(b);
            } catch (InterruptedException ignored) {
            }
        });
        t.start();
        long limite = System.currentTimeMillis() + 5000;
        while (scanner.snapshot().waiters().isEmpty() && System.currentTimeMillis() < limite) Thread.sleep(5);

        ResourceSnapshot s = scanner.snapshot();
        assertEquals(List.of(a.id()), s.holders());
        assertEquals(List.of(b.id()), s.waiters());
        assertEquals(0, s.available());

        scanner.release(a);
        scanner.release(a); // doble liberación: no debe inflar el semáforo
        t.join(5000);
        assertEquals(List.of(b.id()), scanner.snapshot().holders());
        assertEquals(0, scanner.snapshot().available());
        scanner.release(b);
        assertEquals(1, scanner.snapshot().available());
    }

    @Test
    void interrumpirUnaEsperaNoDejaPermisosNiRegistroColgados() throws Exception {
        ResourcePool scanner = new ResourcePool(ResourceType.ESCANER);
        Order a = newOrder(), b = newOrder();
        scanner.acquire(a);
        CountDownLatch interrumpido = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                scanner.acquire(b);
            } catch (InterruptedException e) {
                interrumpido.countDown();
            }
        });
        t.start();
        long limite = System.currentTimeMillis() + 5000;
        while (scanner.snapshot().waiters().isEmpty() && System.currentTimeMillis() < limite) Thread.sleep(5);
        t.interrupt();
        assertTrue(interrumpido.await(5, TimeUnit.SECONDS));
        assertTrue(scanner.snapshot().waiters().isEmpty());
        scanner.release(a);
        assertEquals(1, scanner.snapshot().available());
    }

    @Test
    void noPermiteQueUnPedidoTomeDosUnidadesDelMismoRecurso() {
        ResourcePool pool = new ResourcePool(ResourceType.EMPAQUE);
        Order a = newOrder();
        assertTrue(pool.tryAcquire(a));
        assertThrows(IllegalStateException.class, () -> pool.tryAcquire(a));
    }
}

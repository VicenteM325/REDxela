package com.redxela.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redxela.simulation.EventLog;
import com.redxela.stats.StatsCollector;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Mantiene las conexiones WebSocket abiertas y les envía el {@link LiveSnapshot} periódicamente.
 * Un único hilo planificado arma y envía los mensajes; cada sesión se envuelve en un decorador que
 * limita el tiempo y el búfer de envío, así un cliente lento no frena a los demás ni al sistema.
 */
@Component
public class LiveBroadcaster extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveBroadcaster.class);
    private static final int SEND_TIME_LIMIT_MS = 2000;
    private static final int BUFFER_LIMIT_BYTES = 1024 * 1024;

    private final SimulationController simulation;
    private final StatsController stats;
    private final DeadlockController deadlock;
    private final ReceptionController reception;
    private final InventoryController inventory;
    private final EventLog events;
    private final ObjectMapper mapper;

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "redxela-live");
        t.setDaemon(true);
        return t;
    });

    public LiveBroadcaster(SimulationController simulation, StatsController stats, DeadlockController deadlock,
                           ReceptionController reception, InventoryController inventory, EventLog events,
                           ObjectMapper mapper, @Value("${redxela.live.period-ms:500}") long periodMs) {
        this.simulation = simulation;
        this.stats = stats;
        this.deadlock = deadlock;
        this.reception = reception;
        this.inventory = inventory;
        this.events = events;
        this.mapper = mapper;
        scheduler.scheduleWithFixedDelay(this::broadcast, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_LIMIT_BYTES));
        send(session.getId(), snapshotJson());                 // la UI se pinta de inmediato, sin esperar el siguiente tick
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.removeIf(s -> s.getId().equals(session.getId()));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.removeIf(s -> s.getId().equals(session.getId()));
    }

    /** Arma un snapshot y lo envía a todos los clientes conectados. */
    void broadcast() {
        if (sessions.isEmpty()) {
            return;
        }
        String json = snapshotJson();
        if (json == null) {
            return;
        }
        for (WebSocketSession s : sessions) {
            sendTo(s, json);
        }
    }

    public int connectedClients() {
        return sessions.size();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private void send(String sessionId, String json) {
        if (json == null) {
            return;
        }
        sessions.stream().filter(s -> s.getId().equals(sessionId)).findFirst().ifPresent(s -> sendTo(s, json));
    }

    private void sendTo(WebSocketSession session, String json) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            // Cliente caído o demasiado lento: se descarta su sesión sin afectar a los demás
            log.debug("Se descarta la sesión {}: {}", session.getId(), e.toString());
            sessions.remove(session);
            try {
                session.close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (Exception ignored) {
                // ya estaba cerrada
            }
        }
    }

    private String snapshotJson() {
        try {
            LiveSnapshot snap = new LiveSnapshot(simulation.state(), stats.stats(), deadlock.status(),
                    reception.status(), inventory.inventory(), events.recent(40));
            return mapper.writeValueAsString(snap);
        } catch (Exception e) {
            log.warn("No se pudo armar el snapshot en vivo", e);
            return null;
        }
    }
}

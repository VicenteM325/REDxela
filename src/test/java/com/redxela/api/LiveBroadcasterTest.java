package com.redxela.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:live;DB_CLOSE_DELAY=-1",
        "redxela.live.period-ms=100"})
class LiveBroadcasterTest {

    @LocalServerPort
    int port;
    @Autowired
    LiveBroadcaster broadcaster;
    @Autowired
    ObjectMapper mapper;

    private WebSocket connect(BlockingQueue<String> inbox) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", "http://localhost:" + port)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            inbox.add(buf.toString());
                            buf.setLength(0);
                        }
                        ws.request(1);
                        return null;
                    }
                }).get(10, TimeUnit.SECONDS);
    }

    @Test
    void elClienteRecibeElSnapshotCompletoAlConectarseYLuegoPeriodicamente() throws Exception {
        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocket ws = connect(inbox);
        try {
            String first = inbox.poll(5, TimeUnit.SECONDS);
            assertNotNull(first, "debe recibir un snapshot inmediato");
            JsonNode json = mapper.readTree(first);
            assertEquals(6, json.path("state").path("resources").size());
            assertEquals(5, json.path("stats").path("levels").size());
            assertTrue(json.has("deadlock"));
            assertTrue(json.path("inventory").path("products").size() >= 10);
            assertTrue(json.has("reception"));
            assertTrue(json.has("events"));
            assertEquals(1, broadcaster.connectedClients());

            // y después siguen llegando actualizaciones sin pedirlas
            assertNotNull(inbox.poll(5, TimeUnit.SECONDS));
            assertNotNull(inbox.poll(5, TimeUnit.SECONDS));
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "fin").get(5, TimeUnit.SECONDS);
        }
        long limite = System.currentTimeMillis() + 5000;
        while (broadcaster.connectedClients() != 0 && System.currentTimeMillis() < limite) Thread.sleep(25);
        assertEquals(0, broadcaster.connectedClients(), "la sesión cerrada debe retirarse");
    }

    @Test
    void variosClientesRecibenLosMensajes() throws Exception {
        BlockingQueue<String> a = new LinkedBlockingQueue<>(), b = new LinkedBlockingQueue<>();
        WebSocket wa = connect(a), wb = connect(b);
        try {
            assertNotNull(a.poll(5, TimeUnit.SECONDS));
            assertNotNull(b.poll(5, TimeUnit.SECONDS));
            assertNotNull(a.poll(5, TimeUnit.SECONDS));
            assertNotNull(b.poll(5, TimeUnit.SECONDS));
        } finally {
            wa.abort();
            wb.abort();
        }
    }
}

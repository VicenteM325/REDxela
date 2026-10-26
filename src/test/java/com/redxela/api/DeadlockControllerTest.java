package com.redxela.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:deadlockapi;DB_CLOSE_DELAY=-1",
        "redxela.simulation.ms-per-sim-minute=20",
        "redxela.dispatcher.acquisition-pause-ms=100",
        "redxela.deadlock.check-period-ms=25"})
@AutoConfigureMockMvc
class DeadlockControllerTest {

    @Autowired
    MockMvc mvc;

    private String cuerpo(String path) throws Exception {
        return mvc.perform(get(path)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void sinConflictoElEstadoEstaInactivoYResolverDa409() throws Exception {
        mvc.perform(get("/api/deadlock")).andExpect(status().isOk()).andExpect(jsonPath("$.active", is(false)));
        mvc.perform(post("/api/deadlock/resolve").contentType(MediaType.APPLICATION_JSON).content("{\"keepOrderId\":1}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/deadlock/resolve").contentType(MediaType.APPLICATION_JSON).content("{\"keepOrderId\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void flujoCompletoProvocarVisualizarYResolver() throws Exception {
        String res = mvc.perform(post("/api/scenarios/deadlock")).andExpect(status().isOk())
                .andExpect(jsonPath("$.acquisitionMode", is("NATURAL")))
                .andExpect(jsonPath("$.orderIds", hasSize(5)))
                .andReturn().getResponse().getContentAsString();
        List<Integer> ids = JsonPath.read(res, "$.orderIds");

        long limite = System.currentTimeMillis() + 15_000;
        boolean activo = false;
        while (!activo && System.currentTimeMillis() < limite) {
            Thread.sleep(50);
            activo = JsonPath.read(cuerpo("/api/deadlock"), "$.active");
        }
        assertTrue(activo, "el deadlock debe formarse y confirmarse");

        mvc.perform(get("/api/deadlock")).andExpect(jsonPath("$.report.orders", hasSize(5)))
                .andExpect(jsonPath("$.report.resources", hasItems("MONTACARGAS", "AREA_CARGA")))
                .andExpect(jsonPath("$.report.explanation", containsString("espera circular")));
        // El estado general también muestra los pedidos bloqueados
        mvc.perform(get("/api/state")).andExpect(jsonPath("$.running[?(@.status=='BLOQUEADO')]", hasSize(5)));

        mvc.perform(post("/api/deadlock/resolve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keepOrderId\":" + ids.get(0) + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active", is(false)));

        limite = System.currentTimeMillis() + 30_000;
        boolean todos = false;
        while (!todos && System.currentTimeMillis() < limite) {
            Thread.sleep(100);
            todos = ids.stream().allMatch(id -> {
                try {
                    return "COMPLETADO".equals(JsonPath.read(cuerpo("/api/orders/" + id), "$.status"));
                } catch (Exception e) {
                    return false;
                }
            });
        }
        assertTrue(todos, "tras resolver, los cinco pedidos deben completarse");
        mvc.perform(put("/api/mode").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ATOMIC\"}"));
    }

    @Test
    void elEscenarioDeContencionSeSerializaSinBloquearse() throws Exception {
        mvc.perform(post("/api/scenarios/contention")).andExpect(status().isOk())
                .andExpect(jsonPath("$.acquisitionMode", is("ATOMIC")))
                .andExpect(jsonPath("$.orderIds", hasSize(6)));
        Thread.sleep(300);
        mvc.perform(get("/api/deadlock")).andExpect(jsonPath("$.active", is(false)));
    }
}

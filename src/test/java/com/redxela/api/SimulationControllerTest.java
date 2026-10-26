package com.redxela.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:simulation;DB_CLOSE_DELAY=-1",
        "redxela.simulation.ms-per-sim-minute=40"})
@AutoConfigureMockMvc
class SimulationControllerTest {

    @Autowired
    MockMvc mvc;

    private void enviarPut(String path, String json, int expected) throws Exception {
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is(expected));
    }

    @Test
    void elEstadoIncluyeLosSeisRecursosConSusCapacidades() throws Exception {
        mvc.perform(get("/api/state")).andExpect(status().isOk())
                .andExpect(jsonPath("$.resources", hasSize(6)))
                .andExpect(jsonPath("$.resources[?(@.type=='ESCANER')].capacity", contains(1)))
                .andExpect(jsonPath("$.resources[?(@.type=='EMPAQUE')].capacity", contains(6)))
                .andExpect(jsonPath("$.resources[?(@.type=='AREA_CARGA')].capacity", contains(2)))
                .andExpect(jsonPath("$.resources[?(@.type=='ENCARGADO')].capacity", contains(10)))
                .andExpect(jsonPath("$.resources[?(@.type=='MONTACARGAS')].capacity", contains(3)))
                .andExpect(jsonPath("$.resources[?(@.type=='CONTROL_CALIDAD')].capacity", contains(4)))
                .andExpect(jsonPath("$.policy.description", not(emptyString())))
                .andExpect(jsonPath("$.clock.speed", is(greaterThan(0.0))));
    }

    @Test
    void cambiaLaPoliticaYRechazaLasDesconocidas() throws Exception {
        mvc.perform(get("/api/policies")).andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(3))));
        enviarPut("/api/policy", "{\"id\":\"SHORTEST_FIRST\"}", 200);
        mvc.perform(get("/api/state")).andExpect(jsonPath("$.policy.id", is("SHORTEST_FIRST")));
        enviarPut("/api/policy", "{\"id\":\"INVENTADA\"}", 400);
        enviarPut("/api/policy", "{\"id\":\"\"}", 400);
        enviarPut("/api/policy", "{\"id\":\"AGING\"}", 200);
    }

    @Test
    void cambiaElModoDeAdquisicionYValidaElValor() throws Exception {
        enviarPut("/api/mode", "{\"mode\":\"ORDERED\"}", 200);
        mvc.perform(get("/api/state")).andExpect(jsonPath("$.acquisitionMode", is("ORDERED")));
        enviarPut("/api/mode", "{\"mode\":\"NO_EXISTE\"}", 400);
        enviarPut("/api/mode", "{}", 400);
        enviarPut("/api/mode", "{\"mode\":\"ATOMIC\"}", 200);
    }

    @Test
    void ajustaLaVelocidadDentroDelRango() throws Exception {
        enviarPut("/api/speed", "{\"speed\":2.5}", 200);
        mvc.perform(get("/api/state")).andExpect(jsonPath("$.clock.speed", is(2.5)));
        enviarPut("/api/speed", "{\"speed\":0}", 400);
        enviarPut("/api/speed", "{\"speed\":5000}", 400);
        enviarPut("/api/speed", "{\"speed\":1}", 200);
    }

    @Test
    void controlaElGeneradorYSuTasa() throws Exception {
        mvc.perform(put("/api/generator").contentType(MediaType.APPLICATION_JSON).content("{\"ratePerSimMinute\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ratePerSimMinute", is(3.0)));
        mvc.perform(post("/api/generator/start")).andExpect(jsonPath("$.running", is(true)));
        mvc.perform(get("/api/state")).andExpect(jsonPath("$.generator.running", is(true)));
        mvc.perform(post("/api/generator/stop")).andExpect(jsonPath("$.running", is(false)));
        enviarPut("/api/generator", "{\"ratePerSimMinute\":0}", 400);
    }

    @Test
    void laBitacoraRegistraLosCambios() throws Exception {
        enviarPut("/api/policy", "{\"id\":\"FCFS\"}", 200);
        mvc.perform(get("/api/events?limit=10")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type=='POLITICA')]", not(empty())));
        enviarPut("/api/policy", "{\"id\":\"AGING\"}", 200);
    }
}

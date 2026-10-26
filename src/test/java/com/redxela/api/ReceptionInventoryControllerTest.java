package com.redxela.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:receptioninv;DB_CLOSE_DELAY=-1",
        "redxela.simulation.ms-per-sim-minute=20"})
@AutoConfigureMockMvc
class ReceptionInventoryControllerTest {

    @Autowired
    MockMvc mvc;

    private long productId() throws Exception {
        String body = mvc.perform(get("/api/products")).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$[0].id")).longValue();
    }

    private int onHand(long id) throws Exception {
        String body = mvc.perform(get("/api/inventory")).andReturn().getResponse().getContentAsString();
        return ((java.util.List<Integer>) JsonPath.read(body, "$.products[?(@.id==" + id + ")].onHand")).get(0);
    }

    @Test
    void elInventarioArrancaConExistenciasInicialesYMapaDelAlmacen() throws Exception {
        mvc.perform(get("/api/inventory")).andExpect(status().isOk())
                .andExpect(jsonPath("$.warehouse.capacityUnits", is(2000)))
                .andExpect(jsonPath("$.warehouse.aisles", is(5)))
                .andExpect(jsonPath("$.products", hasSize(greaterThanOrEqualTo(10))))
                .andExpect(jsonPath("$.products[0].onHand", is(greaterThanOrEqualTo(60))))   // otros tests pueden reponer
                .andExpect(jsonPath("$.products[0].locations", not(empty())))
                .andExpect(jsonPath("$.warehouse.occupiedSlots", not(empty())));
    }

    @Test
    void registrarMercaderiaActualizaLasExistenciasEnSegundoPlano() throws Exception {
        long id = productId();
        int antes = onHand(id);
        mvc.perform(post("/api/reception").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + id + ",\"quantity\":15}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.quantity", is(15)));

        long limite = System.currentTimeMillis() + 10_000;
        while (onHand(id) != antes + 15 && System.currentTimeMillis() < limite) Thread.sleep(25);
        assertEquals(antes + 15, onHand(id));
    }

    @Test
    void validaElLoteYElCamion() throws Exception {
        mvc.perform(post("/api/reception").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":999999,\"quantity\":5}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/reception").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":1,\"quantity\":0}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/reception/truck").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lots\":0,\"unitsPerLot\":5}")).andExpect(status().isBadRequest());
    }

    @Test
    void elCamionDescargaVariosLotesYElEstadoLoMuestra() throws Exception {
        mvc.perform(post("/api/reception/truck").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lots\":12,\"unitsPerLot\":3}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.queueCapacity", is(10)));
        long limite = System.currentTimeMillis() + 15_000;
        int pendientes = 1;
        while (pendientes > 0 && System.currentTimeMillis() < limite) {
            Thread.sleep(25);
            pendientes = JsonPath.read(mvc.perform(get("/api/reception")).andReturn().getResponse().getContentAsString(),
                    "$.truckLotsPending");
        }
        assertEquals(0, pendientes);
    }

    @Test
    void laReposicionAutomaticaSePuedeActivarYDesactivar() throws Exception {
        mvc.perform(put("/api/reception/auto-restock").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(jsonPath("$.autoRestock", is(true)));
        mvc.perform(put("/api/reception/auto-restock").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(jsonPath("$.autoRestock", is(false)));
    }
}

package com.redxela.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:orders;DB_CLOSE_DELAY=-1",
        "redxela.simulation.ms-per-sim-minute=40"})
@AutoConfigureMockMvc
class OrderControllerTest {

    @Autowired
    MockMvc mvc;

    private String token;

    @BeforeEach
    void sesion() throws Exception {
        String email = "o" + UUID.randomUUID().toString().substring(0, 8) + "@redxela.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Cliente\",\"email\":\"" + email + "\",\"password\":\"secreto1\",\"serviceLevel\":2}"))
                .andExpect(status().isCreated());
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secreto1\"}"))
                .andReturn().getResponse().getContentAsString();
        token = JsonPath.read(body, "$.token");
    }

    private long firstProductId() throws Exception {
        String body = mvc.perform(get("/api/products")).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$[0].id")).longValue();
    }

    private String create(String json) throws Exception {
        return mvc.perform(post("/api/orders").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void exponeElCatalogoYLosTiposDeMercancia() throws Exception {
        mvc.perform(get("/api/products")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(10))))
                .andExpect(jsonPath("$[0].resources", not(empty())));
        mvc.perform(get("/api/merchandise-types")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(5))));
    }

    @Test
    void creaUnPedidoConElNivelDelClienteYLoProcesaHastaCompletarlo() throws Exception {
        long product = firstProductId();
        String body = create("{\"items\":[{\"productId\":" + product + ",\"quantity\":3},{\"productId\":" + product
                + ",\"quantity\":1}]}");
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();
        assertEquals(2, (int) JsonPath.read(body, "$.serviceLevel.level"));
        assertEquals(4, (int) JsonPath.read(body, "$.totalUnits"));        // líneas del mismo producto se agrupan
        assertEquals(1, ((java.util.List<?>) JsonPath.read(body, "$.items")).size());

        long limite = System.currentTimeMillis() + 15_000;
        String estado = "";
        while (!"COMPLETADO".equals(estado) && System.currentTimeMillis() < limite) {
            Thread.sleep(50);
            estado = JsonPath.read(mvc.perform(get("/api/orders/" + id)).andReturn().getResponse().getContentAsString(),
                    "$.status");
        }
        assertEquals("COMPLETADO", estado);
    }

    @Test
    void permiteForzarUnNivelDistintoEnElIngresoManual() throws Exception {
        String body = create("{\"serviceLevel\":1,\"items\":[{\"productId\":" + firstProductId() + ",\"quantity\":1}]}");
        assertEquals(1, (int) JsonPath.read(body, "$.serviceLevel.level"));
    }

    @Test
    void validaLosDatosYLaSesion() throws Exception {
        mvc.perform(post("/api/orders").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":999999,\"quantity\":1}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("esto no es json"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").header("Authorization", "Bearer falso")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":1,\"quantity\":1}]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders/987654321")).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders?limit=0")).andExpect(status().isBadRequest());
    }

    @Test
    void listaLosPedidosRecientesPrimeroElMasNuevo() throws Exception {
        long product = firstProductId();
        create("{\"items\":[{\"productId\":" + product + ",\"quantity\":1}]}");
        String ultimo = create("{\"items\":[{\"productId\":" + product + ",\"quantity\":2}]}");
        long id = ((Number) JsonPath.read(ultimo, "$.id")).longValue();
        mvc.perform(get("/api/orders?limit=5")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id", is((int) id)));
    }
}

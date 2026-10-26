package com.redxela.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:comparisonapi;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ComparisonControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void validaLosParametrosYElEstadoInicialEsInactivo() throws Exception {
        mvc.perform(get("/api/compare")).andExpect(status().isOk());
        mvc.perform(post("/api/compare").contentType(MediaType.APPLICATION_JSON).content("{\"horizonMinutes\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/compare").contentType(MediaType.APPLICATION_JSON).content("{\"ratePerMinute\":500}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ejecutaLaComparacionEnSegundoPlanoYPublicaLosResultados() throws Exception {
        mvc.perform(post("/api/compare").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"horizonMinutes\":30,\"ratePerMinute\":4,\"seed\":5,\"msPerSimMinute\":5}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state", is("RUNNING")))
                .andExpect(jsonPath("$.policiesTotal", is(4)));
        // Mientras corre no se puede iniciar otra
        mvc.perform(post("/api/compare")).andExpect(status().isConflict());

        long limite = System.currentTimeMillis() + 60_000;
        String estado = "RUNNING";
        while ("RUNNING".equals(estado) && System.currentTimeMillis() < limite) {
            Thread.sleep(100);
            estado = JsonPath.read(mvc.perform(get("/api/compare")).andReturn().getResponse().getContentAsString(), "$.state");
        }
        assertEquals("DONE", estado);
        mvc.perform(get("/api/compare")).andExpect(jsonPath("$.results", hasSize(4)))
                .andExpect(jsonPath("$.best", not(emptyOrNullString())))
                .andExpect(jsonPath("$.results[0].levels", hasSize(5)));
    }
}

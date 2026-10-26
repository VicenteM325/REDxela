package com.redxela.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:auth;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    MockMvc mvc;

    private static String uniqueEmail() {
        return "u" + UUID.randomUUID().toString().substring(0, 8) + "@redxela.test";
    }

    private static String registerBody(String email, int level) {
        return """
                {"name":"Cliente Prueba","email":"%s","password":"secreto1","serviceLevel":%d}
                """.formatted(email, level);
    }

    @Test
    void listaLosCincoNivelesDeServicio() throws Exception {
        mvc.perform(get("/api/service-levels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].name", is("Exprés")))
                .andExpect(jsonPath("$[4].maxWaitMinutes", is(120)));
    }

    @Test
    void registraClienteNuevoConNivelElegidoSinExponerElHash() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(uniqueEmail(), 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.serviceLevel.level", is(2)))
                .andExpect(jsonPath("$.serviceLevel.name", is("Prioritario")))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void rechazaCorreoDuplicado() throws Exception {
        String email = uniqueEmail();
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email, 3))).andExpect(status().isCreated());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email.toUpperCase(), 3)))
                .andExpect(status().isConflict());
    }

    @Test
    void rechazaNivelDeServicioInvalidoYDatosIncompletos() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(uniqueEmail(), 9)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"no-es-correo\",\"password\":\"1\",\"serviceLevel\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginDevuelveTokenYPermiteCambiarDeNivel() throws Exception {
        String email = uniqueEmail();
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email, 5))).andExpect(status().isCreated());

        MvcResult res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secreto1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", not(emptyString())))
                .andReturn();
        String token = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.token");

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceLevel.level", is(5)));

        mvc.perform(put("/api/auth/service-level").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"serviceLevel\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceLevel.name", is("Exprés")));

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.serviceLevel.level", is(1)));
    }

    @Test
    void loginConContrasenaIncorrectaYSesionInvalidaDan401() throws Exception {
        String email = uniqueEmail();
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email, 4))).andExpect(status().isCreated());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"incorrecta\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer token-falso"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    /** Dos registros simultáneos con el mismo correo: solo uno debe prosperar. */
    @Test
    void registrosConcurrentesConElMismoCorreoSoloCreanUnaCuenta() throws Exception {
        String email = uniqueEmail();
        int hilos = 8;
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch salida = new CountDownLatch(1);
        AtomicInteger creados = new AtomicInteger();
        AtomicInteger conflictos = new AtomicInteger();
        java.util.List<Future<?>> tareas = new java.util.ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            tareas.add(pool.submit(() -> {
                salida.await();
                int s = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email, 3))).andReturn().getResponse().getStatus();
                if (s == 201) creados.incrementAndGet();
                else if (s == 409) conflictos.incrementAndGet();
                return null;
            }));
        }
        salida.countDown();
        for (Future<?> t : tareas) t.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, creados.get());
        assertEquals(hilos - 1, conflictos.get());
    }
}

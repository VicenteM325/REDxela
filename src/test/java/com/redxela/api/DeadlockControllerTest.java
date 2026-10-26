package com.redxela.api;

import com.jayway.jsonpath.JsonPath;
import com.redxela.domain.*;
import com.redxela.inventory.InventoryArea;
import com.redxela.persistence.CatalogRepository;
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
    @Autowired
    InventoryArea inventory;
    @Autowired
    CatalogRepository catalog;

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

    /**
     * Si el producto del escenario está casi agotado, el escenario hace llegar mercadería por Recepción y
     * espera a que se registre ANTES de crear los pedidos; si no, arrancarían escalonados y no habría ciclo.
     */
    @Test
    void elEscenarioDeDeadlockSeFormaAunConElStockAgotado() throws Exception {
        Product pesado = catalog.findProducts().stream()
                .filter(p -> p.merchandiseType().code().equals("PESADO")).findFirst().orElseThrow();
        int disponible = inventory.stock().stream().filter(x -> x.product().id() == pesado.id())
                .mapToInt(InventoryArea.ProductStock::available).findFirst().orElseThrow();
        // Un pedido ficticio se lleva casi todas las unidades (sale del almacén, sin pasar por el despachador)
        Customer c = new Customer(1, "t", "t@t", "x", ServiceLevel.ESTANDAR);
        Order consumo = new Order(900_000, c, ServiceLevel.ESTANDAR,
                List.of(new OrderItem(pesado, disponible - 2)), 0, 1);
        assertTrue(inventory.tryReserve(consumo));
        inventory.commit(consumo);

        String res = mvc.perform(post("/api/scenarios/deadlock")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Integer> ids = JsonPath.read(res, "$.orderIds");

        long limite = System.currentTimeMillis() + 15_000;
        boolean activo = false;
        while (!activo && System.currentTimeMillis() < limite) {
            Thread.sleep(50);
            activo = JsonPath.read(cuerpo("/api/deadlock"), "$.active");
        }
        assertTrue(activo, "el ciclo debe formarse aunque faltara stock al armar el escenario");

        mvc.perform(post("/api/deadlock/resolve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"keepOrderId\":" + ids.get(0) + "}")).andExpect(status().isOk());
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
        assertTrue(todos);
        mvc.perform(put("/api/mode").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ATOMIC\"}"));
    }
}

package com.redxela.persistence;

import com.redxela.domain.MerchandiseType;
import com.redxela.domain.ResourceType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:catalog;DB_CLOSE_DELAY=-1")
class CatalogRepositoryTest {

    @Autowired
    CatalogRepository catalog;

    @Test
    void defineAlMenosCincoTiposConCombinacionesDistintas() {
        List<MerchandiseType> types = catalog.findMerchandiseTypes();
        assertTrue(types.size() >= 5, "El enunciado exige al menos 5 tipos de mercancía");

        Set<Set<ResourceType>> combinaciones = new HashSet<>();
        for (MerchandiseType t : types) {
            assertTrue(combinaciones.add(Set.copyOf(t.resources())),
                    "Combinación de recursos repetida en " + t.code());
        }
    }

    @Test
    void existeContencionEntreDosTiposSobreLosMismosDosRecursos() {
        List<MerchandiseType> types = catalog.findMerchandiseTypes();
        boolean hayContencion = types.stream().anyMatch(a -> types.stream().anyMatch(b ->
                !a.code().equals(b.code())
                        && a.resources().stream().filter(b.resources()::contains).count() >= 2));
        assertTrue(hayContencion, "Debe haber dos tipos que compartan al menos dos recursos");
    }

    @Test
    void cargaElCatalogoDeProductosConSuTipo() {
        var products = catalog.findProducts();
        assertFalse(products.isEmpty());
        assertTrue(products.stream().allMatch(p -> !p.merchandiseType().resources().isEmpty()));
        assertTrue(catalog.findProductById(products.get(0).id()).isPresent());
    }
}

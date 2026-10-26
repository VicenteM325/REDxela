package com.redxela.api;

import com.redxela.domain.MerchandiseType;
import com.redxela.persistence.CatalogRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Catálogo de solo lectura: productos y tipos de mercancía (no se crean productos desde la interfaz). */
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogRepository catalog;

    public CatalogController(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    public record ProductDto(long id, String sku, String name, String merchandiseType, String merchandiseTypeName,
                             List<String> resources) {
    }

    public record MerchandiseTypeDto(String code, String name, String description, List<String> resources) {
    }

    @GetMapping("/products")
    public List<ProductDto> products() {
        return catalog.findProducts().stream().map(p -> new ProductDto(p.id(), p.sku(), p.name(),
                p.merchandiseType().code(), p.merchandiseType().name(),
                p.merchandiseType().resources().stream().map(Enum::name).toList())).toList();
    }

    @GetMapping("/merchandise-types")
    public List<MerchandiseTypeDto> merchandiseTypes() {
        return catalog.findMerchandiseTypes().stream().map(CatalogController::dto).toList();
    }

    private static MerchandiseTypeDto dto(MerchandiseType t) {
        return new MerchandiseTypeDto(t.code(), t.name(), t.description(),
                t.resources().stream().map(Enum::name).toList());
    }
}

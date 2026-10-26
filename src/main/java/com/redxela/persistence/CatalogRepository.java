package com.redxela.persistence;

import com.redxela.domain.MerchandiseType;
import com.redxela.domain.Product;
import com.redxela.domain.ResourceType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Acceso de solo lectura al catálogo de productos y a los tipos de mercancía. */
@Repository
public class CatalogRepository {

    private static final String PRODUCT_SELECT = """
            SELECT p.id, p.sku, p.name,
                   t.code, t.name AS type_name, t.description, t.resources
              FROM product p
              JOIN merchandise_type t ON t.code = p.merchandise_type
            """;

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Todos los tipos de mercancía definidos. */
    public List<MerchandiseType> findMerchandiseTypes() {
        return jdbc.query("SELECT code, name, description, resources FROM merchandise_type ORDER BY code",
                (rs, i) -> mapType(rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getString("resources")));
    }

    /** Catálogo completo de productos. */
    public List<Product> findProducts() {
        return jdbc.query(PRODUCT_SELECT + " ORDER BY p.sku", (rs, i) -> mapProduct(rs));
    }

    public Optional<Product> findProductById(long id) {
        return jdbc.query(PRODUCT_SELECT + " WHERE p.id = ?", (rs, i) -> mapProduct(rs), id)
                .stream().findFirst();
    }

    private static Product mapProduct(java.sql.ResultSet rs) throws java.sql.SQLException {
        MerchandiseType type = mapType(rs.getString("code"), rs.getString("type_name"),
                rs.getString("description"), rs.getString("resources"));
        return new Product(rs.getLong("id"), rs.getString("sku"), rs.getString("name"), type);
    }

    private static MerchandiseType mapType(String code, String name, String description, String resources) {
        List<ResourceType> list = Arrays.stream(resources.split(","))
                .map(String::trim)
                .map(ResourceType::valueOf)
                .toList();
        return new MerchandiseType(code, name, description, list);
    }
}

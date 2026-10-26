package com.redxela.persistence;

import com.redxela.domain.Customer;
import com.redxela.domain.ServiceLevel;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;

/** Persistencia de cuentas de clientes. */
@Repository
public class CustomerRepository {

    private static final RowMapper<Customer> MAPPER = (rs, i) -> new Customer(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("email"),
            rs.getString("password_hash"),
            ServiceLevel.fromLevel(rs.getInt("service_level")));

    private final JdbcTemplate jdbc;

    public CustomerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserta un cliente nuevo.
     *
     * @throws DuplicateKeyException si el correo ya está registrado (restricción UNIQUE de la BD,
     *                               que es la única garantía real ante registros concurrentes)
     */
    public Customer insert(String name, String email, String passwordHash, ServiceLevel level) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO customer (name, email, password_hash, service_level) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, email);
            ps.setString(3, passwordHash);
            ps.setInt(4, level.level());
            return ps;
        }, keys);
        Number id = (Number) keys.getKeys().get("ID");
        return new Customer(id.longValue(), name, email, passwordHash, level);
    }

    public Optional<Customer> findByEmail(String email) {
        return jdbc.query("SELECT * FROM customer WHERE email = ?", MAPPER, email).stream().findFirst();
    }

    public Optional<Customer> findById(long id) {
        return jdbc.query("SELECT * FROM customer WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public void updateServiceLevel(long id, ServiceLevel level) {
        jdbc.update("UPDATE customer SET service_level = ? WHERE id = ?", level.level(), id);
    }
}

package com.redxela.auth;

import com.redxela.domain.Customer;
import com.redxela.domain.ServiceLevel;
import com.redxela.persistence.CustomerRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

/** Registro de clientes, inicio de sesión y selección del nivel de servicio. */
@Service
public class AuthService {

    private final CustomerRepository customers;
    private final TokenStore tokens;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(CustomerRepository customers, TokenStore tokens) {
        this.customers = customers;
        this.tokens = tokens;
    }

    /** Crea una cuenta nueva con el nivel de servicio elegido. */
    public Customer register(String name, String email, String password, int serviceLevel) {
        ServiceLevel level = ServiceLevel.fromLevel(serviceLevel);
        String normalized = normalize(email);
        try {
            return customers.insert(name.trim(), normalized, encoder.encode(password), level);
        } catch (DuplicateKeyException e) {
            throw new EmailAlreadyRegisteredException(normalized);
        }
    }

    /** Valida credenciales y abre una sesión. */
    public Session login(String email, String password) {
        Customer customer = customers.findByEmail(normalize(email))
                .filter(c -> encoder.matches(password, c.passwordHash()))
                .orElseThrow(() -> new InvalidCredentialsException("Correo o contraseña incorrectos"));
        return new Session(tokens.issue(customer.id()), customer);
    }

    /** Cliente dueño del token; lanza {@link InvalidCredentialsException} si la sesión no es válida. */
    public Customer authenticate(String token) {
        Long id = tokens.resolve(token);
        if (id == null) {
            throw new InvalidCredentialsException("Sesión inválida o expirada");
        }
        return customers.findById(id)
                .orElseThrow(() -> new InvalidCredentialsException("Sesión inválida o expirada"));
    }

    /** Cambia el nivel de servicio contratado por el cliente de la sesión. */
    public Customer changeServiceLevel(String token, int serviceLevel) {
        Customer customer = authenticate(token);
        ServiceLevel level = ServiceLevel.fromLevel(serviceLevel);
        customers.updateServiceLevel(customer.id(), level);
        return new Customer(customer.id(), customer.name(), customer.email(), customer.passwordHash(), level);
    }

    public void logout(String token) {
        tokens.revoke(token);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** Resultado de un inicio de sesión exitoso. */
    public record Session(String token, Customer customer) {
    }
}

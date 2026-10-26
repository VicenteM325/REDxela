package com.redxela.auth;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sesiones activas en memoria: token opaco -> id de cliente. Se usa {@link ConcurrentHashMap}
 * porque varias peticiones HTTP (hilos distintos) crean y consultan sesiones a la vez.
 */
@Component
public class TokenStore {

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();

    /** Crea una sesión nueva para el cliente y devuelve su token. */
    public String issue(long customerId) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, customerId);
        return token;
    }

    /** @return id del cliente dueño del token, o {@code null} si no existe */
    public Long resolve(String token) {
        return token == null ? null : sessions.get(token);
    }

    public void revoke(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }
}

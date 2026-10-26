package com.redxela.auth;

/** Correo o contraseña incorrectos, o token de sesión inválido. */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}

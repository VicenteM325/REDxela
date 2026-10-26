package com.redxela.auth;

/** El correo ya pertenece a otra cuenta. */
public class EmailAlreadyRegisteredException extends RuntimeException {
    public EmailAlreadyRegisteredException(String email) {
        super("El correo ya está registrado: " + email);
    }
}

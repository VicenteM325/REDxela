package com.redxela.domain;

/**
 * Cliente con cuenta en el sistema.
 *
 * @param id           identificador en base de datos
 * @param name         nombre completo
 * @param email        correo (único, se usa para iniciar sesión)
 * @param passwordHash hash BCrypt de la contraseña (nunca se guarda en claro)
 * @param serviceLevel nivel de servicio contratado
 */
public record Customer(long id, String name, String email, String passwordHash, ServiceLevel serviceLevel) {
}

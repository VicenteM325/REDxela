package com.redxela;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de entrada de REDxela: levanta el backend (hilos de simulación, API REST y WebSocket)
 * y sirve la interfaz web estática.
 */
@SpringBootApplication
public class RedxelaApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedxelaApplication.class, args);
    }
}

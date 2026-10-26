# REDxela

Simulador del centro de distribución de comercio electrónico **REDXela**, proyecto final de
Sistemas Operativos 2 (USAC CUNOC, segundo semestre 2026).

El sistema simula la recepción, el procesamiento y el despacho de pedidos que compiten por un
conjunto limitado de recursos, aplicando planificación, sincronización, exclusión mutua,
semáforos, prevención y detección de deadlock, comunicación entre procesos y administración de
almacenamiento.

## Stack

- Java 21 + Maven
- Spring Boot 3 (REST + WebSocket)
- H2 embebida (modo archivo) para cuentas, catálogo, pedidos y almacén
- Frontend HTML/JS/CSS servido por el mismo jar

## Ejecución

```bash
mvn spring-boot:run
```

La interfaz queda disponible en <http://localhost:8080>.

## Pruebas

```bash
mvn test
```

## Documentación

- `docs/enunciado.pdf`: enunciado del proyecto.

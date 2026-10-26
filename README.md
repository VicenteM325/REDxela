# REDxela

Simulador del centro de distribución de comercio electrónico **REDXela** — proyecto final de
**Sistemas Operativos 2** (USAC CUNOC, segundo semestre 2026).

Pedidos de cinco niveles de servicio compiten por recursos compartidos (estaciones de empaque,
montacargas, áreas de carga, encargados, control de calidad y un escáner único). El sistema aplica
**planificación, sincronización, exclusión mutua, semáforos, prevención y detección de deadlock,
comunicación entre procesos y administración de almacenamiento**, y se observa en tiempo real desde
una interfaz web.

## Qué incluye

| Área | Resumen |
|------|---------|
| Recursos | 6 empaque · 2 áreas de carga · 10 encargados · 3 montacargas · 4 control de calidad · 1 escáner compartido · almacén de 2000 unidades. Cada uno es un `Semaphore` justo |
| Pedidos | 5 niveles de servicio, 6 tipos de mercancía con combinaciones distintas de recursos, generador automático ilimitado e ingreso manual con cuentas de clientes |
| Planificación | 4 políticas intercambiables en caliente (prioridad estricta, envejecimiento, menor cantidad primero, FCFS) y **comparador** con carga reproducible |
| Deadlock | Prevención (asignación atómica u orden global) o permitido para provocarlo; **detección** con recursos de múltiples instancias y **resolución manual** |
| Recepción ↔ Inventario | Áreas independientes que se comunican solo por una cola acotada (productor/consumidor con contrapresión); sin pérdida de lotes |
| Almacén | Cuadrícula de pasillos × niveles; un lote se reparte en ubicaciones no contiguas; reservas de existencias en dos fases |
| Interfaz | Estado en tiempo real por WebSocket: recursos, pedidos en proceso, lista de espera, estadísticas, almacén, conflicto de recursos, comparador y bitácora |

## Stack

Java 21 · Maven · Spring Boot 3.5 (REST + WebSocket) · H2 en modo archivo · HTML/CSS/JS sin dependencias.

## Ejecución

```bash
mvn spring-boot:run
# o bien
mvn -DskipTests package && java -jar target/redxela-0.1.0-SNAPSHOT.jar
```

Abrir <http://localhost:8080>. Debe verse **«en vivo»** en la esquina superior derecha.

## Pruebas

```bash
mvn test        # 120 pruebas, incluidas pruebas de concurrencia con hilos reales
```

## Documentación

| Documento | Contenido |
|-----------|-----------|
| [`docs/manual-usuario.md`](docs/manual-usuario.md) | Cómo usar la interfaz, guiones de demostración, parámetros y solución de problemas |
| [`docs/manual-tecnico.md`](docs/manual-tecnico.md) | Infraestructura, arquitectura, **decisiones de diseño con su fundamento teórico**, pruebas y problemas encontrados |
| [`docs/enunciado.pdf`](docs/enunciado.pdf) | Enunciado del proyecto |

## Demostración rápida

1. **Funcionamiento normal:** pulsar *Iniciar* en el generador y activar la reposición automática.
2. **Contención:** *Provocar contención* — pedidos pesados y voluminosos compiten por montacargas y área de carga.
3. **Deadlock:** detener el generador, pulsar *Provocar deadlock*, leer el panel rojo y elegir *«Continuar este pedido»*.
4. **Recepción vs. Inventario:** *Descargar camión* con 40 lotes y observar el canal llenarse sin perder datos.
5. **Políticas:** *Comparar políticas* y analizar los compromisos entre ellas.

## Estructura

```
src/main/java/com/redxela/   resource · scheduler · processing · reception · inventory ·
                             deadlock · stats · simulation · auth · persistence · api · domain
src/main/resources/          schema.sql · data.sql · application.yml · static/ (interfaz web)
src/test/java/               pruebas unitarias, de concurrencia y de API
docs/                        enunciado y manuales
```

# REDxela — Manual de usuario

**Sistemas Operativos 2 · Proyecto final · USAC CUNOC · Segundo semestre 2026**

REDxela simula el funcionamiento interno de un centro de distribución de comercio electrónico:
llegan pedidos de clientes, compiten por recursos limitados (estaciones de empaque, montacargas,
encargados, etc.), se atienden según una política configurable, y el área de Recepción abastece al
almacén a través del área de Inventario. Todo se observa **en tiempo real** desde una página web.

---

## 1. Requisitos y puesta en marcha

| Requisito | Versión |
|-----------|---------|
| Java (JDK) | 21 o superior |
| Maven | 3.9 o superior (solo para compilar/ejecutar desde el código fuente) |
| Navegador | Cualquiera moderno (Chrome, Firefox, Edge) |

**Ejecutar desde el código fuente**

```bash
mvn spring-boot:run
```

**Ejecutar desde el JAR**

```bash
mvn -DskipTests package
java -jar target/redxela-0.1.0-SNAPSHOT.jar
```

Abra **<http://localhost:8080>**. En la esquina superior derecha debe verse **«en vivo»** en verde: la
página recibe el estado del sistema por WebSocket dos veces por segundo.

> **Datos que persisten:** cuentas de clientes y catálogo (carpeta `./data`, base H2).
> **Datos que no persisten:** pedidos, existencias y almacén; cada arranque comienza con 60 unidades
> de cada producto y la lista de espera vacía.

Para reiniciar desde cero basta con detener la aplicación y borrar la carpeta `data/`.

---

## 2. Conceptos básicos

### 2.1 Niveles de servicio

Cada pedido se clasifica por el servicio que contrató el cliente. Determina **cuándo** se atiende.

| Nivel | Tipo de envío | Tiempo objetivo en espera |
|:-----:|---------------|---------------------------|
| 1 | Exprés | Procesamiento casi instantáneo (se mide con tolerancia de 1 min) |
| 2 | Prioritario | Máximo 10 minutos |
| 3 | Estándar | Máximo 30 minutos |
| 4 | Programado | Máximo 60 minutos |
| 5 | Económico | Máximo 120 minutos |

Todos los tiempos son **minutos simulados**: con velocidad 1× un minuto simulado dura 1 segundo real.

### 2.2 Tipos de mercancía

El tipo determina **qué recursos** necesita el pedido para procesarse. Cada tipo requiere una
combinación distinta (el orden indicado es el orden en que los pide en el modo «natural»).

| Tipo | Qué es | Recursos que requiere |
|------|--------|-----------------------|
| Frágil | Vidrio, cerámica, objetos delicados | Encargado → Empaque → Control de calidad |
| Pesado | Maquinaria, artículos de alto peso | Encargado → Montacargas → Área de carga |
| Voluminoso | Muebles, electrodomésticos grandes | Empaque → Área de carga → Montacargas |
| Electrónico | Equipos de alto valor | Escáner → Control de calidad → Empaque |
| Documentos | Paquetes pequeños y documentos | Encargado → Escáner |
| Perecedero | Alimentos y medicinas | Control de calidad → Encargado → Área de carga |

Un pedido con productos de varios tipos necesita **la unión** de los recursos de todos ellos.

### 2.3 Recursos compartidos

| Recurso | Cantidad |
|---------|:--------:|
| Estaciones de empaque | 6 |
| Áreas de carga | 2 |
| Encargados de bodega | 10 |
| Montacargas | 3 |
| Estaciones de control de calidad | 4 |
| Sistema de escaneo (compartido entre Recepción y despacho) | 1 |

Un pedido usa **una unidad** de cada recurso que necesita durante todo su procesamiento. El tiempo
de procesamiento es proporcional a su cantidad de unidades (0.25 min por unidad).

### 2.4 Estados de un pedido

| Estado | Significado |
|--------|-------------|
| **En espera** | Está en la lista de espera; aún no obtiene sus recursos |
| **Sin existencias** | No hay unidades suficientes en el almacén; espera a que Recepción/Inventario las repongan |
| **En proceso** | Un hilo lo está procesando y mantiene sus recursos |
| **Bloqueado** | Participa en un conflicto de recursos (deadlock) pendiente de resolver |
| **Completado** | Terminó; sus unidades salieron del almacén |

---

## 3. Recorrido por la interfaz

La página se divide, de arriba hacia abajo, en los siguientes paneles.

### 3.1 Barra superior
Muestra la conexión en vivo, el **minuto simulado** actual y la velocidad de la simulación.

### 3.2 Conflicto de recursos (aparece solo cuando ocurre)
Un panel rojo se abre arriba de todo cuando el sistema detecta que varios pedidos se bloquean entre
sí. Indica:

* la **explicación** de la espera circular en lenguaje llano,
* los **recursos involucrados**,
* una tabla con cada pedido bloqueado: qué **retiene**, qué **espera** y **quién se lo impide**,
* un botón **«Continuar este pedido»** por fila (ver §5.3).

### 3.3 Control de la simulación

| Control | Qué hace |
|---------|----------|
| **Política de atención** | Elige cómo se ordena la lista de espera (ver §6). Cambia en caliente y reordena la lista al instante. Debajo se explica el criterio. |
| **Adquisición de recursos** | *Atómica* (todo o nada) y *Orden global* previenen el deadlock; *Orden natural* lo permite, para poder provocarlo. |
| **Velocidad** | 0.5× a 10×. A 10× un minuto simulado dura 0.1 s. |
| **Generador automático** | Inicia/detiene la llegada continua de pedidos de todos los niveles, sin límite. El campo indica los **pedidos por minuto simulado**. |
| **Reposición automática** | Activa proveedores simulados que, cuando un producto baja de 30 unidades, hacen llegar 100 por Recepción. |
| **Provocar deadlock** / **Provocar contención** | Arman escenarios de demostración (ver §5). |

### 3.4 Recursos compartidos
Una tarjeta por recurso con sus unidades (cuadros azules = en uso), **quién lo usa** (`#12` = pedido 12;
`Lote 3` = lote en Recepción) y **quién espera** por él. Una tarjeta con borde naranja está agotada.

### 3.5 Pedidos en proceso
Pedido, nivel, unidades, tipo, estado, **barra de progreso** y **tiempo restante estimado**. Si un pedido
está bloqueado esperando un recurso lo indica debajo del estado.

### 3.6 Lista de espera
Muestra el **criterio de orden aplicado** y los pedidos **ya ordenados** según la política activa.
La columna «Espera / objetivo» compara lo que lleva esperando contra el máximo de su nivel:
verde = holgado, naranja = cerca del límite, **rojo = excedido**. `↺n` indica que el pedido regresó
a la lista `n` veces (por un conflicto resuelto).

### 3.7 Estadísticas
* Pedidos completados, espera promedio, procesamiento promedio, **% que cumplió su tiempo objetivo**,
  pedidos por minuto y cuántos esperan (y cuántos de ellos sin existencias).
* Tabla por nivel de servicio.
* **Ocupación de cada recurso**: barra = uso actual; «prom.» = promedio desde el inicio.

### 3.8 Nuevo pedido (cuentas de clientes)

1. **Crear cuenta**: pestaña «Crear cuenta». Indique nombre, correo, contraseña (mínimo 6 caracteres) y
   **tipo de servicio** (nivel 1 a 5). Al crearla se inicia sesión automáticamente.
2. **Iniciar sesión**: con correo y contraseña.
3. Con la sesión iniciada se muestra el cliente y su nivel (puede **cambiarlo** en el selector).
4. **Registrar un pedido**: elija producto y cantidad; con «+ Añadir producto» agrega hasta 6 líneas.
   El pedido se atiende con el nivel del cliente.
5. **Forzar nivel (solo pruebas)**: atiende ese pedido con otro nivel sin cambiar el del cliente.
   Sirve para provocar situaciones concretas (p. ej. un Exprés en medio de pedidos Económicos).

Después de registrar un pedido puede verlo aparecer en la lista de espera y pasar a «en proceso».

### 3.9 Recepción de mercadería

* **Registrar llegada**: seleccione un producto del catálogo (no se crean productos nuevos) y la cantidad.
  El resto es automático: Recepción escanea el lote y avisa a Inventario, que actualiza las existencias,
  asigna ubicaciones en el almacén y **reevalúa los pedidos que esperaban ese producto**.
* **Descargar camión**: envía varios lotes seguidos. Si Recepción produce más rápido de lo que Inventario
  procesa, el canal se llena (barra naranja) y Recepción espera sin perder ningún lote.
* **Canal Recepción → Inventario**: avisos pendientes / capacidad (10), lotes enviados y procesados,
  unidades almacenadas y rechazadas por falta de espacio.

### 3.10 Almacén e inventario
* **Mapa del almacén**: 5 pasillos × 4 niveles × 4 posiciones, 25 unidades por ubicación (2000 en total).
  Cada cuadro es una ubicación; el color indica el tipo de mercancía y la altura de relleno, qué tan llena
  está. Pase el cursor para ver ubicación, SKU y cantidad.
* **Tabla de productos**: en almacén, reservado (por pedidos en proceso), disponible y **ubicaciones**.
  Un mismo producto puede estar repartido en ubicaciones no contiguas.

### 3.11 Comparador de políticas de atención
Ejecuta **todas las políticas con la misma carga** y muestra, por política: llegadas, completados, espera
promedio y máxima, % que cumple su objetivo, pedidos por minuto y la espera promedio por nivel. La
política marcada con ★ es la de mayor cumplimiento. Ver §6.

### 3.12 Bitácora de eventos
Últimas acciones del sistema (llegadas, inicios, finalizaciones, entradas y salidas del almacén,
deadlocks, cambios de política…).

---

## 4. Guiones de demostración

### 4.1 Funcionamiento normal
1. Pulse **Iniciar** en el generador (1 pedido/min) y observe cómo aparecen pedidos en la lista de espera,
   pasan a «en proceso» y se completan; los recursos se ocupan y liberan.
2. Active la **reposición automática** para que el almacén no se agote.
3. Cambie la **velocidad** a 5× para ver el sistema acelerado.

### 4.2 Registrar un pedido manual
1. Cree una cuenta (nivel *Exprés*) e inicie sesión.
2. Elija un producto y una cantidad y pulse **Registrar pedido**.
3. Pulse **Iniciar** el generador con tasa alta (p. ej. 4) para saturar el sistema y vea cómo el pedido
   Exprés se adelanta en la lista según la política.

### 4.3 Contención de recursos
Pulse **Provocar contención**: 3 pedidos *pesados* y 3 *voluminosos* necesitan **montacargas y área de
carga** a la vez. Con adquisición atómica el sistema los serializa: los que no alcanzan pasan a la lista de
espera y avanzan conforme se liberan los recursos. No hay bloqueo.

### 4.4 Conflicto de recursos (deadlock)
1. **Detenga el generador** y espere a que la lista de espera y los pedidos en proceso estén vacíos
   (así el escenario es limpio).
2. Pulse **Provocar deadlock**. Se cambia el modo a *Orden natural* y se crean 3 pedidos pesados y 2
   voluminosos: los pesados toman los 3 montacargas y piden área de carga; los voluminosos toman las 2
   áreas de carga y piden montacargas. En 1 a 3 segundos se forma la **espera circular**.
3. Aparece el **panel rojo** con la explicación, los recursos involucrados y qué pedido espera a cuál.
4. Elija un pedido y pulse **«Continuar este pedido»**: conserva los recursos en disputa y avanza; los
   demás los liberan, regresan a la lista de espera y **no vuelven a competir hasta que el elegido termine**.
   Después todos se completan.
5. Para evitar que ocurra de nuevo cambie la **adquisición de recursos** a *Atómica* u *Orden global*.

### 4.5 Recepción más rápida que Inventario
1. Pulse **Descargar camión** con 40 lotes de 10 unidades.
2. Observe el **canal** llenarse: Recepción espera cuando está lleno; Inventario va procesando a su ritmo.
3. Al terminar, «Recepción envió» e «Inventario procesó» coinciden: **no se perdió ningún lote**.

### 4.6 Pedidos sin existencias
1. Desactive la reposición automática y deje correr el generador hasta que algún producto llegue a 0
   (la tabla de inventario lo marca en rojo). Los pedidos de ese producto pasan a **«Sin existencias»** y no
   consumen recursos.
2. Registre una llegada de ese producto: los pedidos se **reevalúan automáticamente** y continúan.

### 4.7 Comparar políticas
En el panel del comparador deje los valores por defecto (90 min, 4 pedidos/min, semilla 42) y pulse
**Comparar políticas** (tarda unos 10 segundos). Con los mismos datos de entrada compare los resultados:
verá que no existe una política «mejor» en todo, sino compromisos (ver §6).

---

## 5. Modos de adquisición de recursos

| Modo | Cómo pide los recursos | ¿Puede haber deadlock? |
|------|------------------------|:----------------------:|
| **Atómica** (por defecto) | Todos a la vez o ninguno | No |
| **Orden global** | Uno por uno, pero todos siguiendo el mismo orden fijo de recursos | No |
| **Orden natural** | Uno por uno, en el orden propio de cada tipo de mercancía | **Sí** |

En los dos últimos modos hay una pausa de 0.4 s reales entre cada recurso, que representa el trabajo
previo y hace reproducible el entrelazado que provoca el conflicto.

---

## 6. Políticas de atención

| Política | Criterio | Efecto típico |
|----------|----------|---------------|
| **Prioridad con envejecimiento** (por defecto) | Por nivel; cada 10 min de espera el pedido «sube» un nivel | Equilibrada; evita que los niveles bajos esperen indefinidamente |
| **Prioridad estricta** | Siempre primero el nivel más alto | Excelente para Exprés/Prioritario; los niveles bajos pueden esperar mucho |
| **Menor cantidad primero** | Primero los pedidos con menos unidades | Libera recursos rápido: más pedidos por minuto; ignora el nivel de servicio |
| **Orden de llegada (FCFS)** | Por hora de llegada | Línea base: reparte la espera por igual |

Resultado de referencia del comparador (90 min simulados, 4 pedidos/min, semilla 42; los valores pueden
variar ligeramente entre corridas):

| Política | Completados | Espera prom. | Cumple objetivo | Espera por nivel (1·2·3·4·5) |
|----------|:----------:|:------------:|:---------------:|------------------------------|
| Envejecimiento | 175 | 23.3 min | 58 % | 12 · 17 · 21 · 29 · 34 |
| Prioridad estricta | 173 | 23.1 min | 61 % | 2 · 6 · 24 · 35 · 39 |
| Menor cantidad primero | 232 | 16.6 min | 72 % | 17 · 16 · 17 · 12 · 22 |
| FCFS | 176 | 23.2 min | 51 % | 23 · 25 · 22 · 24 · 23 |

---

## 7. Configuración (`src/main/resources/application.yml`)

| Parámetro | Valor por defecto | Descripción |
|-----------|-------------------|-------------|
| `server.port` | 8080 | Puerto HTTP |
| `redxela.simulation.ms-per-sim-minute` | 1000 | Milisegundos reales por minuto simulado a 1× |
| `redxela.simulation.minutes-per-unit` | 0.25 | Minutos de trabajo por unidad de un pedido |
| `redxela.dispatcher.policy` | AGING | Política inicial (`AGING`, `STRICT_PRIORITY`, `SHORTEST_FIRST`, `FCFS`) |
| `redxela.dispatcher.max-in-flight` | 40 | Máximo de pedidos procesándose a la vez |
| `redxela.dispatcher.acquisition-pause-ms` | 400 | Pausa entre recursos en los modos incrementales |
| `redxela.generator.rate-per-sim-minute` | 1.0 | Tasa inicial del generador |
| `redxela.warehouse.*` | 5 × 4 × 4 × 25 | Pasillos, niveles, posiciones y capacidad por ubicación |
| `redxela.inventory.initial-stock-per-product` | 60 | Existencias al arrancar |
| `redxela.reception.queue-capacity` | 10 | Capacidad del canal Recepción → Inventario |
| `redxela.restock.*` | 30 / 100 | Punto de reposición y tamaño del lote |

Cualquier parámetro puede sobrescribirse al ejecutar, por ejemplo:
`java -jar target/redxela-0.1.0-SNAPSHOT.jar --server.port=9090 --redxela.warehouse.slot-capacity=40`

---

## 8. Solución de problemas

| Síntoma | Causa probable | Solución |
|---------|----------------|----------|
| La barra dice «sin conexión» | El servidor no está corriendo o cambió de puerto | Verifique la consola; recargue la página |
| «Port 8080 was already in use» | Otra aplicación usa el puerto | Use `--server.port=9090` |
| «Sesión inválida o expirada» | Las sesiones viven en memoria; se perdieron al reiniciar | Inicie sesión de nuevo (las cuentas sí persisten) |
| El escenario de deadlock no se forma | Había otros pedidos compitiendo por montacargas/áreas | Detenga el generador, espere a que el sistema se vacíe y repita |
| Muchos pedidos «Sin existencias» | El almacén se agotó | Active la reposición automática o registre llegadas |
| «Inventario está saturado» al registrar una llegada | El canal estaba lleno | El lote **no se perdió**: simplemente no fue aceptado; reintente |
| Los cambios en la interfaz no se ven | Caché del navegador | Recargue con Ctrl+Shift+R |

---

## 9. Pruebas automáticas

```bash
mvn test
```

Ejecuta 120 pruebas (exclusión mutua, políticas, almacén, comunicación entre áreas, deadlock,
API y WebSocket). Ver el manual técnico para su descripción.

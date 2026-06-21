# Library App

Sistema de gestión de biblioteca compuesto por dos servicios independientes que se comunican vía HTTP.

---

## Arquitectura

```
┌─────────────────────────────────────────────────────────┐
│                        Cliente                          │
└───────────────────────────┬─────────────────────────────┘
                            │ HTTP (JWT)
                            ▼
┌─────────────────────────────────────────────────────────┐
│              library-service  (Java / Spring Boot 3)    │
│  - Catálogo de libros (CRUD)                            │
│  - Usuarios y autenticación JWT                         │
│  - Orquestador de la saga de préstamos                  │
│  - PostgreSQL (library_db)                              │
└───────────────────────────┬─────────────────────────────┘
                            │ HTTP (API Key interna)
                            ▼
┌─────────────────────────────────────────────────────────┐
│              loans-service  (Go)                        │
│  - Registro y devolución de préstamos                   │
│  - Historial y préstamos activos por usuario            │
│  - PostgreSQL (loans_db)                                │
└─────────────────────────────────────────────────────────┘
```

---

## Inicio rápido

### Requisitos

- Docker y Docker Compose v2+

### Levantar todo el sistema

```bash
# 1. Clonar el repositorio
git clone <repo-url>
cd library-app

# 2. Crear el archivo de variables de entorno
cp .env.example .env
# Editar .env y reemplazar los valores "change_me"

# 3. Levantar ambos servicios y sus bases de datos
docker compose up --build
```

El sistema estará disponible en:
- **library-service**: http://localhost:8080
- **loans-service**: http://localhost:8081

### Verificar que todo está sano

```bash
curl http://localhost:8080/health
curl http://localhost:8081/health
```

---

## Ejemplo de flujo completo

> Ver sección completa con ejemplos curl en el README final.

```
1. Registro:   POST /auth/register
2. Login:      POST /auth/login          → obtener JWT
3. Consultar:  GET  /books?genre=fiction → listar libros disponibles
4. Préstamo:   POST /loans               → registrar préstamo
5. Devolución: POST /loans/{id}/return   → devolver libro
```

---

## Estructura del repositorio

```
library-app/
├── library-service/     # Spring Boot 3 (Java)
├── loans-service/       # Go
├── docker-compose.yml
├── .env.example
└── README.md
```

---

## Decisiones técnicas

> Sección a completar durante la implementación.

---

## Consistencia de datos — Saga orquestada

### El problema

`library-service` y `loans-service` tienen **bases de datos separadas**. Registrar un préstamo requiere modificar las dos: decrementar `available_copies` en `library_db` y crear un registro de préstamo en `loans_db`. Con una sola BD esto sería una transacción SQL. Con dos BDs no existe esa garantía — se necesita una estrategia explícita.

### Por qué library-service reserva primero y loans-service no llama a A para validar

El enunciado describe que loans-service debería validar con library-service la disponibilidad del libro antes de registrar el préstamo. Este diseño invierte deliberadamente ese orden por dos razones concretas:

**1. Elimina la ventana de race condition.**
Si B llamara a A para "validar disponibilidad" (lectura) y luego persistiera el préstamo, existiría una ventana entre ambas operaciones donde otro request podría reservar la misma copia. El diseño actual ejecuta la reserva como una operación atómica en A:

```sql
UPDATE books SET available_copies = available_copies - 1
WHERE id = ? AND available_copies > 0
```

Este `UPDATE` con condición en el `WHERE` garantiza que el decremento y la validación ocurren en la misma instrucción SQL — no hay ventana entre leer y escribir. Si retorna 0 filas afectadas, no hay copias disponibles y loans-service nunca es llamado.

**2. Centraliza la compensación en un solo servicio.**
Al ser library-service quien reserva antes de llamar a loans-service, también es library-service quien libera la copia si loans-service falla. Toda la lógica de compensación vive en `LoanOrchestrationService`. Si loans-service fuera quien llama a A para reservar, la compensación (llamar a `/release`) también debería vivir en loans-service — distribuyendo la lógica de consistencia entre dos servicios y dificultando el razonamiento sobre los casos borde.

**Consecuencia:** loans-service es un servicio de persistencia pura en el flujo de creación — recibe una solicitud de préstamo ya validada y reservada, y simplemente la persiste. La responsabilidad de la invariante de negocio ("no prestar un libro sin copia disponible") queda exclusivamente en library-service, que es quien posee la tabla `books`.

---

### Por qué saga orquestada y no coreografiada

La saga orquestada concentra toda la lógica de compensación en un único lugar (`LoanOrchestrationService`). Con solo dos participantes, un orquestador externo (Kafka, CQRS) sería sobre-ingeniería sin beneficio medible. La saga coreografiada distribuiría la lógica de compensación entre ambos servicios, dificultando el razonamiento sobre los casos borde.

### Flujo completo del préstamo

```
1. library-service recibe POST /loans del cliente
2. Crea LoanRequest en estado PENDING (memoria durable del intento)
3. Ejecuta reserva atómica en su propia BD:
     UPDATE books SET available_copies = available_copies - 1
     WHERE id = ? AND available_copies > 0
   → Si returns 0 filas: devuelve 409 sin llamar a loans-service
4. Llama a loans-service POST /loans con reintentos (3 intentos, backoff 200ms/500ms/1s)
5a. loans-service confirma → estado CONFIRMED, retorna 201 al cliente
5b. Reintentos agotados → verificar con GET /loans/by-request-id/{requestId}
      - Encontrado: el préstamo sí se creó a pesar del error de red → CONFIRMED (no compensar)
      - No encontrado: el préstamo no existe → compensar (COMPENSATING → release → COMPENSATED) → 503
      - Verificación también falla: dejar en PENDING, nunca compensar a ciegas → 503
```

### Por qué cada pieza es necesaria

| Pieza | Problema que resuelve sin ella |
|---|---|
| `LoanRequest` en `PENDING` | Si el proceso muere entre el reserve y la llamada a loans-service, no hay rastro del intento; el libro queda con una copia "fantasma" reservada sin préstamo asociado |
| Reserva atómica (`available_copies > 0` en el WHERE) | Sin ella, dos requests simultáneos sobre la última copia podrían ambos decrementar y ambos crear un préstamo, resultando en `available_copies = -1` |
| Reintentos con backoff | loans-service puede estar momentáneamente sobrecargado o reiniciándose; reintentar con espera resuelve el 90% de los fallos transitorios sin impacto en el usuario |
| Verificación con `GET /loans/by-request-id` antes de compensar | El timeout es **ambiguo**: la solicitud pudo haber llegado y ser procesada antes de que la respuesta se perdiera. Compensar sin verificar liberaría una copia que sí está prestada, desincronizando el inventario |
| Estados `COMPENSATING` → `COMPENSATED` separados | Si el proceso muere durante la compensación, el estado `COMPENSATING` permite saber exactamente qué pasó y retomar sin doble-compensar |
| No compensar si la verificación también falla | Compensar a ciegas cuando no tenemos información es peor que no compensar — podríamos liberar una copia de un préstamo que sí existe en loans-service |

### Flujo de devolución

```
1. library-service recibe POST /loans/{id}/return del cliente
2. Verifica que LoanRequest.status == CONFIRMED y que loanId existe
3. Llama a loans-service POST /loans/{loanId}/return con reintentos
4. loans-service marca el préstamo como RETURNED y llama a /internal/books/{id}/release
   para incrementar available_copies en library-service
```

### Trade-offs aceptados

- **Sin job de recuperación automática**: los `LoanRequest` que quedan en `PENDING` por caída del proceso en mid-saga requieren intervención manual o un scheduled job (`@Scheduled`) que retome la saga. En producción se resolvería con outbox pattern transaccional o el job de reconciliación. Está documentado como mejora futura.
- **Sin circuit breaker**: si loans-service está persistentemente caído, cada solicitud de préstamo esperará `3 × timeout_ms` antes de fallar. En producción se añadiría Resilience4j para abrir el circuito después de N fallos consecutivos.

---

## Trade-offs y mejoras futuras

> Sección a completar durante la implementación.

---

## Lo que no se alcanzó a implementar

> Sección a completar antes de la entrega.

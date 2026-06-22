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
- **Swagger UI**: http://localhost:8080/swagger-ui.html

### Verificar que todo está sano

```bash
curl http://localhost:8080/health
curl http://localhost:8081/health
```

### Credenciales iniciales

La migración `V3__seed_admin.sql` inserta automáticamente un usuario administrador:

| Campo | Valor |
|---|---|
| Email | `admin@library.com` |
| Password | `admin123` |
| Rol | `ADMIN` |

> El usuario administrador es necesario para los endpoints de escritura de libros (`POST/PUT/DELETE /books`) y gestión de usuarios (`GET/PUT/DELETE /users`).
> Los usuarios registrados vía `POST /auth/register` siempre reciben rol `USER`.

---

## Ejemplo de flujo completo

```bash
# 1. Login como admin (usuario creado por la migración V3)
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@library.com","password":"admin123"}'
# → {"token":"eyJ..."}   ← guardar como TOKEN_ADMIN

# 2. Crear un libro (requiere ADMIN)
curl -X POST http://localhost:8080/books \
  -H "Authorization: Bearer $TOKEN_ADMIN" \
  -H "Content-Type: application/json" \
  -d '{"title":"Clean Code","author":"Robert Martin","isbn":"9780132350884","publicationYear":2008,"genre":"Software","totalCopies":3}'
# → {"id":"<BOOK_ID>", ...}   ← guardar como BOOK_ID

# 3. Registrar un usuario normal
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Juan Perez","email":"juan@test.com","password":"password123"}'
# → {"token":"eyJ..."}   ← guardar como TOKEN_USER

# 4. Listar libros disponibles
curl http://localhost:8080/books?available=true \
  -H "Authorization: Bearer $TOKEN_USER"

# 5. Crear préstamo (orquesta la saga: reserva copia + crea registro en loans-service)
curl -X POST http://localhost:8080/loans \
  -H "Authorization: Bearer $TOKEN_USER" \
  -H "Content-Type: application/json" \
  -d '{"bookId":"<BOOK_ID>"}'
# → {"id":"<LOAN_REQUEST_ID>", "status":"CONFIRMED", ...}

# 6. Ver préstamos activos
curl http://localhost:8080/loans/me/active \
  -H "Authorization: Bearer $TOKEN_USER"

# 7. Devolver libro
curl -X POST http://localhost:8080/loans/<LOAN_REQUEST_ID>/return \
  -H "Authorization: Bearer $TOKEN_USER"
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

- **Job de recuperación automática** (`PendingLoanRecoveryJob`): corre cada 5 minutos y resuelve los `LoanRequest` que quedaron en `PENDING`. Para cada registro llama a `GET /loans/by-request-id/{requestId}` en loans-service: si el préstamo existe lo confirma, si no existe libera la copia reservada (COMPENSATED). Si loans-service sigue caído lo deja en PENDING y reintenta en el próximo ciclo. Usa `FOR UPDATE SKIP LOCKED` para ser seguro en entornos multi-instancia (varias réplicas de library-service nunca procesan la misma fila).
- **Sin circuit breaker**: si loans-service está persistentemente caído, cada solicitud de préstamo esperará `3 × timeout_ms` antes de fallar. En producción se añadiría Resilience4j para abrir el circuito después de N fallos consecutivos.


**Resolución manual mientras no existe el job:**

```bash
# 1. Verificar si loans-service llegó a crear el préstamo
curl http://localhost:8081/loans/by-request-id/<REQUEST_ID> \
  -H "X-Internal-Api-Key: <INTERNAL_KEY>"

# Si 404 → el préstamo no existe, liberar la copia manualmente:
docker compose exec library-db psql -U postgres library_db -c \
  "UPDATE loan_requests SET status='COMPENSATED' WHERE status='PENDING' AND request_id='<REQUEST_ID>';"
docker compose exec library-db psql -U postgres library_db -c \
  "UPDATE books SET available_copies = available_copies + 1 WHERE id='<BOOK_ID>';"

# Si 200 → el préstamo sí existe, confirmar manualmente:
docker compose exec library-db psql -U postgres library_db -c \
  "UPDATE loan_requests SET status='CONFIRMED', loan_id='<LOAN_ID>' WHERE request_id='<REQUEST_ID>';"
```

**El job de recovery automático** (`PendingLoanRecoveryJob`) se ejecuta cada 5 minutos, selecciona registros `PENDING` más viejos que 10 minutos, y aplica la misma lógica: confirma si el préstamo existe en loans-service, compensa si no existe, o deja en PENDING si loans-service sigue caído. Los intervalos son configurables con `recovery.pending.stale-minutes` y `recovery.pending.interval-ms`.

---

## Trade-offs y mejoras futuras

> Sección a completar durante la implementación.

---

## Lo que no se alcanzó a implementar

> Sección a completar antes de la entrega.

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

### Reiniciar desde cero (borrar datos)

```bash
# Detener contenedores y eliminar volúmenes de BD (Flyway y DataSeeder corren de nuevo)
docker compose down -v
docker compose up --build

# Si además quieres forzar rebuild completo sin caché de imágenes
docker compose down -v
docker compose build --no-cache
docker compose up
```

### Verificar que todo está sano

```bash
curl http://localhost:8080/health
curl http://localhost:8081/health
```

> El usuario administrador es necesario para los endpoints de escritura de libros (`POST/PUT/DELETE /books`) y gestión de usuarios (`GET/PUT/DELETE /users`).
> Los usuarios registrados vía `POST /auth/register` siempre reciben rol `USER`.

---

## Ejemplo de flujo completo

```bash
# 1. Login como admin (creado por DataSeeder para propósitos de prueba; en producción se usaría un bootstrap seguro vía variables de entorno)
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

## Tests

### Cómo correr los tests

```bash
# library-service (Java / JUnit + Mockito)
cd library-service
mvn test

# loans-service (Go / testify)
cd loans-service
go test ./...
```

### Qué cubre cada suite

**library-service** (3 clases de test):

| Clase | Qué prueba |
|---|---|
| `LoanOrchestrationServiceTest` | Flujo feliz de préstamo, compensación cuando loans-service falla, no-compensar cuando la verificación también falla, devolución exitosa |
| `BookServiceTest` | CRUD de libros, filtros combinados (autor/género/disponibilidad), paginación |
| `JwtServiceTest` | Generación y validación de tokens JWT, expiración |

**loans-service** (4 tests en `service_test.go`):

| Test | Qué prueba |
|---|---|
| `TestCreateLoan_Success` | Registro exitoso — `ReleaseBook` no se llama durante la creación (loans-service no valida con A) |
| `TestReturnLoan_Success` | Devolución exitosa — `MarkReturned` y `ReleaseBook` llamados en orden correcto |
| `TestReturnLoan_LibraryClientFails` | Falla parcial — el préstamo queda `RETURNED` localmente, error propagado como valor sin panic |
| `TestCreateLoan_Idempotent` | Idempotencia — mismo `request_id` dos veces retorna el registro existente sin duplicar |

---

## Decisiones técnicas

> A lo largo de este documento: **Servicio A** = `library-service` (Java / Spring Boot), **Servicio B** = `loans-service` (Go).

### Por qué bases de datos separadas y no tablas separadas en el mismo PostgreSQL

Con tablas separadas en la misma BD, `library-service` tendría acceso físico directo a los datos de préstamos — la separación sería solo organizacional (una convención de código), no estructural. Cualquier desarrollador podría hacer un `JOIN` entre `books` y `loans` en una sola query, acoplando los servicios a nivel de datos. Además, una caída o saturación de la BD afectaría ambos servicios simultáneamente. Dos instancias PostgreSQL en Docker Compose tienen costo cero de complejidad adicional y garantizan que la independencia de los servicios sea real e irreversible.

---

### Responsabilidades por servicio — por qué cada cosa está donde está

**¿Por qué la autenticación JWT vive solo en library-service y no en loans-service?**

loans-service es infraestructura interna — ningún cliente lo llama directamente. Solo recibe llamadas de library-service, autenticadas con una API key interna (`X-Internal-Api-Key`). Añadir JWT a loans-service hubiera sido duplicar una capa de seguridad en un servicio que no tiene superficie pública. La autenticación de usuarios vive donde vive la frontera con el cliente.

**¿Por qué la validación de disponibilidad del libro vive en library-service y no en loans-service?**

El enunciado original propone que loans-service llame a library-service para "validar disponibilidad" antes de crear el préstamo. Este diseño invierte ese orden deliberadamente: library-service ejecuta la reserva atómica (`UPDATE books SET available_copies - 1 WHERE available_copies > 0`) y solo llama a loans-service si la reserva tuvo éxito. La razón: **library-service es el dueño de la tabla `books`**. Darle a loans-service la autoridad para tomar decisiones sobre el inventario de libros crearía un acoplamiento implícito — loans-service tendría que conocer las reglas de negocio de un recurso que no le pertenece. La invariante "no prestar sin copia disponible" vive exclusivamente donde vive la fuente de verdad.

**¿Por qué el orquestador de la saga vive en library-service y no es un tercer servicio?**

Con solo dos participantes, un orquestador externo (proceso separado, Kafka, servicio de coreografía) añadiría infraestructura sin resolver ningún problema en este sistema. El orquestador vive en library-service porque es quien inicia el flujo, quien posee la reserva atómica, y quien tiene el contexto completo para decidir si compensar o confirmar. Si el orquestador viviera en loans-service, la lógica de compensación (`release`) tendría que llamar de vuelta a library-service — distribuyendo la responsabilidad de consistencia entre ambos servicios y haciendo el razonamiento sobre casos borde mucho más difícil.

---

### library-service — decisiones de tecnología (Java / Spring Boot 3)

**`RestClient` y no `WebClient` / `RestTemplate` / Feign**

`RestClient` es el cliente HTTP síncrono moderno de Spring 6.1 — fluent API, sin reactividad innecesaria. `WebClient` está diseñado para stacks reactivos (WebFlux); usarlo en un stack bloqueante (Servlet) solo añade complejidad sin beneficio. `RestTemplate` está en modo mantenimiento desde Spring 5. Feign añade una dependencia extra para algo que `RestClient` resuelve nativamente con menos código.



**Organización por capas técnicas (`controller/`, `service/`, `repository/`)**

Utilizacion de capas técnicas en Java porque es la convención que Spring asume y que cualquier desarrollador Java reconoce al instante. Con tres entidades simples, organizar por feature no añade claridad — solo rompe una convención sin resolver ningún problema. El contraste con loans-service (organizado por feature en internal/loan/) es intencional: demuestra que aplico la convención del ecosistema de cada lenguaje, no que impongo el mismo patrón en los dos.

---

### loans-service — decisiones de tecnología (Go)

**PostgreSQL y no SQLite / MySQL**

loans-service podría haberse simplificado con SQLite (sin servidor, cero configuración), pero SQLite tiene limitaciones de concurrencia bajo escrituras simultáneas que lo hacen inadecuado incluso para pruebas de carga moderada. PostgreSQL ya está en el stack (library-service lo usa), los UUID nativos y los tipos `TIMESTAMP WITH TIME ZONE` son necesarios para el modelo de préstamos, y el costo operativo de una segunda instancia PostgreSQL en Docker Compose es exactamente cero. MySQL también habría funcionado, pero PostgreSQL tiene mejor soporte de tipos en `pgx` y es la elección más común en el ecosistema Go.

**Chi y no Gin / Echo / Fiber**

Chi usa `net/http` estándar sin wrappers propios — cualquier middleware que funcione con `http.Handler` funciona con Chi sin adaptadores. Gin y Echo tienen su propio tipo `Context` que rompe la compatibilidad con la stdlib. Fiber usa `fasthttp` (no compatible con `net/http`). Chi es la opción más idiomática cuando no se necesita micro-optimización de throughput.

**`pgx` directo y no GORM**

GORM oculta el SQL generado, tiene edge cases conocidos con tipos PostgreSQL (UUID, ENUM, arrays), y su manejo de errores requiere inspeccionar `result.Error` en lugar de retornar `error` directamente. `pgx` da control total del SQL, maneja UUID y enums de PostgreSQL nativamente, y su API de error es idiomática en Go.

**Interfaces definidas en el consumidor, no junto al struct concreto**

Convención idiomática de Go: la interfaz `LoanRepository` vive en el paquete `loan/` (quien la usa), no en el paquete que la implementa. Esto invierte la dependencia — el paquete `loan` no importa `repository`, `repository` implementa lo que `loan` necesita. El resultado es que los tests del servicio solo necesitan un mock manual de la interfaz, sin importar la implementación real.

**Organización por feature (`internal/loan/`)**

El modelo, handler, service y repository del dominio `loan` viven juntos en `internal/loan/`. Organizar por capa técnica en Go (`handlers/`, `services/`, `repositories/`)  — el ecosistema Go (incluyendo el tooling oficial) organiza por lo que el código hace, no por su rol técnico.


**Manejo de errores idiomático de Go**

Los errores se retornan como valores en toda la cadena — no hay `panic` ni `log.Fatal` en la lógica de negocio. 

**Estructura del proyecto loans-service**

```
loans-service/
├── cmd/api/main.go              # punto de entrada, wiring de dependencias
├── internal/
│   ├── loan/
│   │   ├── model.go             # tipos, errores centinela, interfaces (LoanRepository, LibraryClient)
│   │   ├── handler.go           # HTTP handlers (Chi router)
│   │   ├── service.go           # lógica de negocio
│   │   ├── repository.go        # implementación pgx de LoanRepository
│   │   └── service_test.go      # tests con mocks manuales
│   ├── httpclient/
│   │   └── library_client.go    # cliente HTTP hacia library-service (/internal/books/{id}/release)
│   └── platform/
│       ├── config/config.go     # lectura de variables de entorno
│       └── middleware/apikey.go # validación de X-Internal-Api-Key
├── migrations/
│   ├── 000001_init.up.sql
│   └── 000001_init.down.sql
├── go.mod
└── Dockerfile
```

La interfaz `LoanRepository` y `LibraryClient` viven en `model.go` (paquete consumidor `loan/`), no junto a su implementación — convención Go de "define la interfaz donde la necesitas". Esto permite que `service_test.go` implemente mocks manuales sin importar los paquetes concretos de pgx o httpclient.

---

### Patrones de diseño implementados

Solo se listan patrones donde se puede completar la frase *"lo usé porque sin él tendría el problema X"* con un problema concreto de este sistema.

| Patrón | Dónde | Problema que resuelve |
|---|---|---|
| **Repository** | `BookRepository`, `LoanRequestRepository` (Java); `LoanRepository` interface (Go) | Desacopla la lógica de negocio del ORM/SQL específico; permite testear `BookService` y `LoanOrchestrationService` sin BD real |
| **Adapter / Client** | `LoanClientImpl` (library-service → loans-service); `LibraryHTTPClient` (loans-service → library-service, solo `/release`) | Encapsula timeouts, reintentos y mapeo de errores HTTP; permite mockear "el otro servicio está caído" en tests unitarios |
| **Saga orquestada** | `LoanOrchestrationService` | Consistencia de datos entre dos BDs independientes sin transacción distribuida (2PC); orquestador embebido en library-service porque solo hay 2 participantes |
| **Strategy** | Filtros de búsqueda de libros (`BookSpecification`, Specifications combinables) | Filtros combinables (título/autor/género/disponibilidad) sin un if-chain de parámetros opcionales en el service |

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

**Trade Off :** El trade-off es que loans-service pierde autonomía — asume que su único caller ya garantizó la disponibilidad. Lo acepté conscientemente porque en este sistema library-service es el único orquestador y es quien posee la fuente de verdad del inventario.`

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

- **Job de recuperación automática** (`PendingLoanRecoveryJob`): corre cada 5 minutos y resuelve los `LoanRequest` que quedaron en `PENDING`. Para cada registro llama a `GET /loans/by-request-id/{requestId}` en loans-service: si el préstamo existe lo confirma, si no existe libera la copia reservada (COMPENSATED). Si loans-service sigue caído lo deja en PENDING y reintenta en el próximo ciclo. Usa `FOR UPDATE SKIP LOCKED` para ser seguro en entornos multi-instancia.

- **Trade-off de incertidumbre en PENDING:** mientras un `LoanRequest` permanece en `PENDING`, la copia del libro está reservada pero el préstamo no está confirmado — el usuario no puede hacer otro préstamo de ese libro si agota las copias disponibles, y el sistema no sabe si el préstamo realmente ocurrió. Este estado de incertidumbre persiste hasta que ambos servicios estén disponibles simultáneamente y el job corra su próximo ciclo. En producción se resolvería con el **outbox pattern transaccional**: guardar el evento en la misma transacción que el `LoanRequest` y procesarlo con un relay dedicado que garantiza entrega eventual.



---

## Bonus implementados

| Bonus | Descripción |
|---|---|
| **Swagger / OpenAPI** | `springdoc-openapi` 2.6.0 — spec en http://localhost:8080/v3/api-docs, UI en http://localhost:8080/swagger-ui.html |
| **Healthcheck endpoints** | `GET /health` en ambos servicios. library-service devuelve `{"status":"UP"}`, loans-service verifica conectividad con la BD |
| **Rate limiting** | 20 req/min por IP en library-service con Bucket4j (token bucket). Responde `429` + `X-RateLimit-Remaining` |
| **CI (GitHub Actions)** | `.github/workflows/ci.yml` — corre `mvn test` (Java 21) y `go test ./...` (Go 1.22) en cada push/PR |
| **Logging estructurado** | library-service: SLF4J/Logback (JSON en producción vía perfil). loans-service: `log/slog` (stdlib de Go 1.21+) con campos estructurados |

---

## Lo que no se alcanzó a implementar

| Ítem | Razón de la exclusión |
|---|---|
| **gRPC** | Evaluado y descartado: la complejidad de protobuf y generación de código en dos lenguajes supera el beneficio para 2 servicios con contratos simples. En producción sí aportaría: HTTP/2 reduce latencia en llamadas frecuentes, el .proto garantiza que cliente y servidor nunca se desincronicen. |


**Por qué NO Kafka / mensajería asíncrona**

La comunicación entre `library-service` y `loans-service` es síncrona por diseño: el cliente necesita saber inmediatamente si el préstamo fue confirmado o falló. Introducir Kafka convertiría una operación request/response en un flujo eventual que complicaría la experiencia del cliente sin resolver ningún problema real con solo 2 participantes. En producción sí aportaría si el sistema creciera: notificaciones de vencimiento de préstamo, auditoría de eventos, o integración con un servicio de multas que no necesita respuesta inmediata son casos donde la asincronía es la herramienta correcta.

**Por qué NO CQRS / Event Sourcing**

Los modelos de lectura y escritura de este sistema son idénticos — no hay consultas analíticas complejas que justifiquen proyecciones separadas. Event Sourcing requiere infraestructura de event store, reconstrucción de estado desde eventos y snapshots. El costo es alto; el beneficio, nulo para este dominio. En producción valdría la pena si el historial de préstamos necesitara auditoría completa (quién reservó qué copia, cuándo y desde qué IP) o si hubiera reportes analíticos que requirieran un modelo de lectura diferente al modelo de escritura.

**Por qué NO Clean Architecture completa**

El desacoplamiento (interfaces) se aplicó solo en las dos fronteras donde el beneficio es real y medible: `LoanRepository` (testear sin BD) y `LoanClient` (testear sin loans-service). Aislar el dominio de `Book` o `LoanRequest` detrás de puertos y adaptadores hubiera sido desproporcionado para ~6 endpoints. En producción valdría la pena si el dominio creciera con reglas de negocio complejas (políticas de préstamo por tipo de usuario, límites dinámicos, integración con sistemas externos) que necesiten ser testeadas completamente aisladas del framework.

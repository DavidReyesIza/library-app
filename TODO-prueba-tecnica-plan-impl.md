# TODO — Prueba Técnica Backend Multi-Lenguaje (Biblioteca: library-service + loans-service)

> Repo único (monorepo). Sin Kafka, sin CQRS/Event Sourcing, sin Clean Architecture completa, sin gRPC — decisiones conscientes, documentadas en el README, no omisiones.

---

## 0. Setup del repositorio

- [ ] Crear repo único en GitHub (público o privado con acceso dado)
- [ ] Estructura de carpetas raíz: `library-service/`, `loans-service/`, `docker-compose.yml`, `.env.example`, `README.md`
- [ ] `.gitignore` (target/, node_modules si aplica, .env, *.log, vendor/ si aplica)
- [ ] `.env.example` con todas las variables necesarias, sin valores reales/secretos

---

## 1. library-service — Java / Spring Boot 3

### Setup base
- [ ] `pom.xml`: spring-boot-starter-web, data-jpa, security, validation, postgresql, flyway, jjwt 0.12.x, lombok, starter-test, h2 (test)
- [ ] Estructura de paquetes (por capa técnica — convención Spring estándar): `controller/`, `service/`, `repository/`, `client/`, `model/`, `dto/`, `security/`, `exception/`, `config/`
- [ ] `application.yml` con variables de entorno (DB, JWT secret, URL de loans-service, API key interna)
- [ ] Migración Flyway `V1__init.sql`: tablas `users`, `books`, `loan_requests`

### Autenticación y usuarios
- [ ] Entidad `User` (id, fullName, email, passwordHash, role)
- [ ] `POST /auth/register`
- [ ] `POST /auth/login` → devuelve JWT
- [ ] `JwtService`: generar/validar token (claims: userId, role, exp)
- [ ] `JwtAuthFilter`: extrae y valida JWT en cada request
- [ ] `SecurityConfig`: rutas públicas vs protegidas, roles ADMIN/USER
- [ ] CRUD de usuarios:
  - [ ] `GET /users` → solo ADMIN, lista todos los usuarios (con paginación)
  - [ ] `GET /users/{id}` → ADMIN puede ver cualquiera; USER solo puede ver el suyo (403 si intenta ver otro)
  - [ ] `PUT /users/{id}` → solo ADMIN (actualiza fullName, email, role)
  - [ ] `DELETE /users/{id}` → solo ADMIN
  - [ ] `GET /users/me` → atajo para USER autenticado ver su propio perfil (extrae id del JWT)

### Libros
- [ ] Entidad `Book` (title, author, isbn UNIQUE, publicationYear, genre, totalCopies, availableCopies)
- [ ] CRUD completo (solo ADMIN escribe, cualquiera autenticado lee)
- [ ] `GET /books` con filtros (autor, género, disponibilidad) + paginación → JPA Specifications combinables (**patrón Strategy**, ver sección 8)
- [ ] Validación de inputs (Bean Validation: @NotBlank, @Min, etc.)

### Endpoints internos (para loans-service)
- [ ] Filtro/middleware que valida API key compartida en header (`X-Internal-Api-Key`) para rutas `/internal/**`
- [ ] `POST /internal/books/{id}/reserve` → UPDATE atómico condicional (`available_copies > 0`), sin cache
- [ ] `POST /internal/books/{id}/release` → revierte reserva/suma copia, sin cache

### Préstamos — orquestación de la saga
- [ ] Entidad/tabla `LoanRequest` (id, requestId UUID, userId, bookId, status, attempts, createdAt, updatedAt)
- [ ] `LoanClient` (interfaz + impl con `RestClient`, timeouts configurados, hacia loans-service) — **patrón Adapter**
- [ ] `LoanOrchestrationService` (**orquestador de la Saga**):
  - [ ] Validaciones locales primero (usuario existe/activo, límite de préstamos si aplica)
  - [ ] Insertar `loan_requests` en `PENDING` con `requestId` nuevo
  - [ ] Ejecutar reserva atómica (paso interno arriba) → 409 si no hay copias, sin llamar a B
  - [ ] Llamar a B con reintentos (2-3 intentos, backoff 200ms/500ms/1s)
  - [ ] Si confirma → `CONFIRMED`, 201 al cliente
  - [ ] Si se agotan reintentos → llamar a `GET /loans/by-request-id/{requestId}` en B antes de decidir
  - [ ] Según verificación: `CONFIRMED` (no compensar) o `COMPENSATING → COMPENSATED` (revertir copia local)
  - [ ] Si la verificación también falla → dejar en estado pendiente, no compensar a ciegas
- [ ] `GET /loans/me` o `GET /loans?userId=` (cliente final) → delega a B, devuelve préstamos del usuario autenticado
- [ ] `POST /loans` (cliente final) → dispara la orquestación
- [ ] `POST /loans/{id}/return` → llama a B, maneja igual los fallos de comunicación
- [ ] **[MEJORA FUTURA — no implementar ahora]** Job de recuperación: `@Scheduled` que busca `loan_requests` en `PENDING`/`COMPENSATING` con más de N minutos y retoma la saga. Documentar el diseño y el trade-off en README ("préstamos huérfanos si el proceso muere a mitad de la saga; en producción se resuelve con este job o con outbox pattern transaccional")

### Errores y transversales
- [ ] `GlobalExceptionHandler` (@ControllerAdvice) con formato consistente `{timestamp, status, error, message, path}`
- [ ] Excepciones custom: `ResourceNotFoundException`, `BadRequestException`, `ServiceUnavailableException`
- [ ] Status codes correctos (400/401/403/404/409/503)
- [ ] Logging estructurado (mínimo logs claros en puntos clave: intento de llamada a B, reintentos, compensación)

### Tests library-service (mínimo 4)
- [ ] Lógica de negocio en `BookService` (ISBN único, validación de copias)
- [ ] `JwtServiceTest` (generación/validación)
- [ ] `LoanOrchestrationServiceTest` — caso éxito (B confirma → `CONFIRMED`, copia decrementada)
- [ ] **`LoanOrchestrationServiceTest` — caso B falla/timeout ⚠️ (PRIORITARIO)**: mockear `LoanClient.createLoan()` para lanzar `ServiceUnavailableException`, mockear `LoanClient.getLoanByRequestId()` también fallando → verificar que se llama `bookRepository.releaseReservation()` y el estado queda `COMPENSATED`
- [ ] (Opcional) Test de controller con MockMvc para status codes

### Infra
- [ ] `Dockerfile` (multi-stage build con Maven)

---

## 2. loans-service — Go (obligatorio)

### Setup base
- [ ] `go.mod` (Go 1.21+)
- [ ] Estructura (**package-by-feature**, no por capa técnica): `cmd/api/main.go`, `internal/loan/`, `internal/httpclient/`, `internal/platform/`
  - `internal/platform/` contiene: `config/` (lectura de variables de entorno al arranque) y `middleware/` (validación de API key interna — usado por el router de Chi)
- [ ] Dependencias: Chi, pgx v5, validator/v10, testify, golang-migrate
- [ ] Config vía variables de entorno (DB, puerto, URL de library-service, API key interna)
- [ ] Migraciones con `golang-migrate` — tabla `loans` (id, request_id UNIQUE, user_id, book_id, status, loaned_at, returned_at); archivos en `migrations/000001_init.up.sql` y `000001_init.down.sql`

### Modelo y persistencia
- [ ] `internal/loan/model.go` — struct `Loan`
- [ ] `internal/loan/repository.go` — `PostgresLoanRepository` (struct concreto, **SIN interfaz aquí**), queries con pgx + `context.Context`
- [ ] `internal/loan/service.go` — define interfaces `LoanRepository` y `LibraryClient` (**el consumidor define el contrato**, no el productor — regla de oro de interfaces idiomáticas en Go)
- [ ] `internal/httpclient/library_client.go` — struct concreto que satisface `LibraryClient`, llama a A **solo** (`/internal/books/{id}/release`), con `context.WithTimeout` — **no llama a `/reserve`** porque A ya ejecutó la reserva atómica antes de llamar a B

### Endpoints (todos protegidos con API key interna)
- [ ] `POST /loans` — recibe `{request_id, userId, bookId}`, idempotente (UNIQUE en `request_id`)
- [ ] `POST /loans/{id}/return`
- [ ] `GET /loans/active?userId=`
- [ ] `GET /loans/history?userId=`
- [ ] `GET /loans/by-request-id/{request_id}` — endpoint de verificación (clave para resolver ambigüedad de timeout)
- [ ] Middleware de validación de API key interna
- [ ] Validación de inputs con validator/v10

### Lógica de negocio
- [ ] `service.go`: registrar préstamo — **B no valida disponibilidad con A aquí**; A ya ejecutó la reserva atómica antes de llamar a B, por lo que B solo persiste el registro del préstamo y retorna confirmación
- [ ] Manejo de errores como valores (no panics), errores tipados/wrapeados con `fmt.Errorf("...: %w", err)`
- [ ] Flujo de devolución: marcar `RETURNED` localmente primero, luego llamar a A (`/internal/books/{id}/release`) vía `LibraryClient` para incrementar copias, con reintentos

### Transversales
- [ ] Middleware de error centralizado → mismo formato JSON que library-service
- [ ] Logging estructurado con `log/slog`
- [ ] Status codes correctos

### Tests loans-service (mínimo 4, con testify + mocks de las interfaces)
- [ ] Registro exitoso de préstamo — A ya reservó, B persiste y retorna 201
- [ ] Devolución exitosa — `LibraryClient.Release()` retorna OK, préstamo queda en estado `RETURNED` en la BD de B
- [ ] **Devolución con falla de LibraryClient ⚠️** — `LibraryClient.Release()` retorna error/timeout → préstamo queda `RETURNED` localmente (el estado local no se revierte), error propagado como valor sin panic, sin llamar a `log.Fatal` ni similar
- [ ] Idempotencia: mismo `request_id` dos veces no duplica (UNIQUE constraint retorna error controlado, no 500)

### Infra
- [ ] `Dockerfile` (multi-stage build)

---

## 3. Integración y consistencia (cross-cutting)

- [ ] Confirmar contrato `request_id` consistente entre A y B (mismo nombre de campo, mismo formato UUID)
- [ ] Probar manualmente: A arriba, B abajo → confirmar compensación correcta
- [ ] Probar manualmente: timeout simulado (ej. apagar B a mitad de la llamada) → confirmar que se usa el endpoint de verificación antes de compensar
- [ ] Probar manualmente: dos requests simultáneos sobre la última copia → confirmar que solo uno gana (UPDATE atómico)
- [ ] Probar manualmente: reintento con mismo `request_id` no duplica préstamo en B

---

## 4. Docker Compose y entorno

- [ ] `docker-compose.yml` en la raíz: postgres-library, loans-service-db, library-service, loans-service
- [ ] Healthcheck en cada servicio + `depends_on: condition: service_healthy`
- [ ] `.env.example` completo y consistente con lo que leen ambos servicios
- [ ] Verificar que `docker compose up` levanta todo en un solo comando, sin pasos manuales extra
- [ ] Probar el flujo completo de punta a punta sobre los contenedores (no solo en local)

---

## 5. README (raíz del repo)

- [ ] Diagrama simple de arquitectura (A ↔ B ↔ DBs independientes)
- [ ] Instrucciones de instalación y ejecución (`docker compose up`, variables de entorno necesarias)
- [ ] Ejemplo de flujo completo: login → consultar libro → préstamo → devolución (con curl o ejemplos de request/response)
- [ ] Por qué cada responsabilidad va en A o en B (bounded context)
- [ ] **Sección "Arquitectura general"** — ver sección 8 de este documento, pegar la tabla de opciones evaluadas + la elegida y por qué
- [ ] **Sección "Patrones de diseño"** — ver sección 8, tabla patrón → dónde → qué problema resuelve (llenar SOLO con patrones que el código realmente implementa, verificar al final, no antes)
- [ ] Decisiones técnicas por servicio, cada una con su "por qué":
  - [ ] Por qué `RestClient` y no `WebClient`/`RestTemplate`/Feign
  - [ ] Por qué Chi y no Gin/Echo/Fiber
  - [ ] Por qué pgx directo y no GORM
  - [ ] Por qué interfaces definidas en el consumidor (Go) y no junto al struct concreto
  - [ ] Por qué NO se usó Redis cache (decisión consciente: la disponibilidad de copias cambia con cada préstamo/devolución, un TTL introduciría inconsistencias; el beneficio de cache no aplica con este volumen)
  - [ ] Por qué NO se usó Kafka (decisión consciente)
  - [ ] Por qué NO se usó CQRS/Event Sourcing
  - [ ] Por qué saga orquestada de 2 pasos y no coreografiada
  - [ ] **Por qué A reserva antes de llamar a B (inversión del enunciado)** — el enunciado dice que B debería validar con A; este diseño invierte el orden deliberadamente: A ejecuta la reserva atómica primero, eliminando la ventana de race condition entre "validar disponibilidad" y "registrar préstamo". B no necesita llamar a A para reservar porque A ya garantizó la copia antes de llamarle. La compensación queda centralizada en A (un solo responsable), evitando lógica de compensación distribuida entre A y B.
  - [ ] Por qué NO se usó Clean Architecture/DDD táctico completo
  - [ ] Por qué BD separada para loans-service y no tabla separada en el mismo PostgreSQL (con el texto: "Con tabla separada en la misma BD, library-service tendría acceso físico directo a los datos de préstamos, haciendo la separación solo organizacional y no real. Una caída o saturación de la BD afectaría ambos servicios simultáneamente. Dos instancias PostgreSQL en Docker Compose tienen costo cero de complejidad adicional y garantizan que la independencia de los servicios sea estructural, no solo una convención de código.")
- [ ] Sección dedicada a consistencia de datos: el flujo completo de la saga, el problema del timeout ambiguo, cómo se resuelve con el endpoint de verificación, qué pasa si A o B caen en cada punto
- [ ] Trade-offs aceptados y evolución de producción a mencionar: outbox pattern transaccional, job de reconciliación/recuperación real para `loan_requests` huérfanas, circuit breaker, Redis cache si el catálogo crece y el volumen lo justifica
- [ ] Lista de lo que no se alcanzó a hacer y por qué

---

## 6. Bonus (opcionales, en orden de prioridad si sobra tiempo)

- [x] Healthcheck endpoints — ya es necesario para el `docker-compose.yml`, hacerlo bien (`/health` en ambos servicios)
- [x] Logging estructurado — ya cubierto (`slog` en Go, logs claros en Java)
- [ ] OpenAPI/Swagger en al menos un servicio (más fácil en A con springdoc)
- [ ] Rate limiting (si sobra tiempo, en A)
- [ ] CI básico con GitHub Actions (job que corre tests de ambos servicios en cada push)
- [ ] gRPC — descartado, no aporta sobre HTTP para este caso de uso, no implementar

---

## 7. Revisión final antes de entregar

- [ ] `docker compose up` desde un clon limpio del repo, sin pasos manuales no documentados
- [ ] Releer el repo completo en voz alta como si lo presentaras — si te trabas explicando algo, repásalo
- [ ] Confirmar que ningún secreto está commiteado (revisar `.env` no esté en el repo, solo `.env.example`)
- [ ] Confirmar que el código Go no "se ve como Java" (revisión específica de idiomaticidad)
- [ ] Confirmar 3-4+ tests corriendo y pasando en cada servicio
- [ ] README completo, sin secciones a medias
- [ ] Commit history razonable (no un solo commit gigante "todo el proyecto")

---

## 8. Referencia — Arquitectura y patrones (contenido a trasladar al README)

### 8.1 Arquitectura general — opciones evaluadas

| Opción | Qué es | Veredicto |
|---|---|---|
| Transaction Script (todo en el controller) | Sin capas, lógica directo en el endpoint | ❌ Descartada — no separa responsabilidades, criterio nombrado explícito en la rúbrica |
| Clean Architecture / Hexagonal completa | Dominio 100% aislado del framework, puertos/adaptadores en cada frontera | ❌ Descartada — sobre-ingeniería para ~6 endpoints/servicio, costo en tiempo sin beneficio medible aquí |
| DDD táctico completo | Agregados, value objects, domain events | ❌ Descartada — el dominio (libro, préstamo) es simple, no hay invariantes que lo justifiquen |
| Service Mesh / API Gateway separado | Gateway delante de A y B | ❌ Descartada — excluida explícitamente por el enunciado ("no necesitas service mesh, Kubernetes, etc.") |
| **Capas pragmáticas + desacoplamiento solo en fronteras de integración** | Controller/Handler → Service → Repository simple, con interfaces solo en persistencia y comunicación HTTP entre servicios | ✅ **Elegida** |

**Por qué la elegida es la correcta:** el desacoplamiento (interfaces) se paga solo donde el beneficio es real y medible — poder testear el `service` sin DB real (Repository) y poder testear "qué pasa si el otro servicio está caído" sin levantarlo de verdad (Adapter/Client). Ambos son criterios de evaluación nombrados explícitamente en el documento. Todo lo demás (modelo de `Book`, `Loan`) queda como código simple atado al framework, sin capa de dominio aislada, porque ningún criterio lo exige y aislarlo no resuelve ningún problema real del tamaño de este sistema.

**Frase de defensa en entrevista:** *"Apliqué inversión de dependencias solo en las dos fronteras que necesitaba aislar para testing y que la prueba evalúa explícitamente. Modelar un dominio completamente aislado del framework hubiera sido desproporcionado para el tamaño del problema — el valor de Clean Architecture aparece con dominio complejo o necesidad real de cambiar de framework, y ninguna de las dos aplica aquí."*

**Diferencia de organización por lenguaje:**
- **Java (library-service):** por capa técnica (`controller/`, `service/`, `repository/`) — convención que Spring asume y que cualquier evaluador de Java espera ver.
- **Go (loans-service):** por feature (`internal/loan/` con handler, service, repository y modelo juntos) — organizar por capa técnica en Go es el "efecto Java"; el ecosistema real (Kubernetes, Docker) agrupa por lo que el código hace.

### 8.2 Patrones de diseño — tabla a completar con el código real (verificar al final, no antes)

| Patrón | Dónde | Problema que resuelve |
|---|---|---|
| Repository | Ambos servicios | Desacopla lógica de negocio del ORM/SQL específico; permite testear sin DB real |
| Strategy | Filtros de búsqueda de libros (Specifications combinables en A) | Filtros combinables (autor/género/disponibilidad) sin un if-chain de parámetros opcionales |
| Adapter / Client | `LoanClient` (library-service → loans-service para crear/verificar préstamo), `LibraryClient` (loans-service → library-service **solo `/release`** en devolución) | Encapsula timeouts/reintentos/mapeo de errores de la llamada HTTP saliente detrás de una interfaz; permite mockear "el otro servicio está caído" en tests. B **no llama a A para reservar** — A orquesta la reserva atómica antes de llamar a B |
| Saga orquestada (2 pasos, con compensación) | `LoanOrchestrationService`, flujo de préstamo A↔B | Consistencia de datos entre dos BDs sin transacción distribuida (2PC); orquestador embebido en A, no servicio externo, porque solo hay 2 participantes |

**Regla aplicada para esta tabla:** solo se incluye un patrón si se puede completar la frase "lo usé porque sin él tendría el problema X" con un problema concreto del propio sistema — no se incluyen patrones decorativos (Singleton, Observer, Decorator, etc.) sin un caso de uso orgánico que los justifique en este proyecto.

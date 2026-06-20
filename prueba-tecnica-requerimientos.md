PRUEBA TÉCNICA - BACKEND (MULTI-LENGUAJE)
Perfil objetivo
Backend Developer Semi-Senior / Senior. La prueba evalúa adaptabilidad a múltiples
lenguajes/stacks.
Contexto
Vas a construir un sistema de gestión de biblioteca compuesto por DOS servicios que se
comunican entre sí. Uno lo harás en Java (Spring Boot) o Node.js (NestJS) —tú eliges— y el
otro OBLIGATORIAMENTE en Go.
Esta prueba evalúa explícitamente tu adaptabilidad a lenguajes diferentes. No buscamos un
experto en Go: buscamos a alguien que pueda moverse entre stacks sin colapsar.
Arquitectura
Dos servicios independientes con la siguiente división de responsabilidades:
Servicio A: Biblioteca (Java/Spring Boot o Node.js/NestJS)
Es el servicio principal, expuesto al cliente. Maneja libros, usuarios y autenticación.
• CRUD de libros: título, autor, ISBN, año, género, cantidad de copias disponibles.
• CRUD de usuarios.
• Listado de libros con filtros (por autor, género, disponibilidad) y paginación.
• Autenticación con JWT.
• Roles: admin (CRUD libros/usuarios) vs usuario normal (lectura y sus préstamos).
• Cuando se registra un préstamo, este servicio LLAMA al Servicio B vía HTTP.
Servicio B: Préstamos (OBLIGATORIO en Go)
Servicio independiente que gestiona los préstamos. Se comunica con el Servicio A vía HTTP.
• Endpoint para registrar un préstamo (recibe userId y bookId).
• Endpoint para registrar devolución.
• Endpoint para listar préstamos activos por usuario.
• Endpoint para listar histórico de préstamos.
• Persiste sus propios datos (BD separada o tabla separada, justificas la decisión).
• Valida con el Servicio A que el libro existe y tiene copias disponibles antes de registrar
el préstamo.
Requisitos técnicos comunes a ambos servicios
• API REST con verbos HTTP correctos y status codes apropiados.
• Validación de inputs.
• Manejo centralizado de errores con respuestas consistentes.
• Variables de entorno (.env, no commitear secretos).
• Docker Compose en la raíz del repo que levante AMBOS servicios y sus BDs con un
solo comando (docker compose up).
• Al menos 3-4 tests significativos por servicio (unitarios o de integración).
• README en la raíz con: instrucciones de levantamiento, arquitectura, decisiones por
servicio, ejemplo de flujo completo (login → consultar libro → préstamo).
Específico del Servicio A (Java/Nest)
• Si eliges Java: Spring Boot 3+, JPA/Hibernate o jOOQ, JUnit + Mockito.
• Si eliges Nest: NestJS última estable, TypeORM o Prisma, Jest.
• Base de datos relacional (PostgreSQL recomendado, MySQL también está bien).
• Migraciones o esquema versionado.
• Cliente HTTP para comunicarse con el Servicio B (RestTemplate/WebClient/Feign en
Java; HttpService/axios en Nest).
Específico del Servicio B (Go - obligatorio)
• Go 1.21+ (idealmente la última estable).
• Framework HTTP a elección: net/http (stdlib), Gin, Echo, Chi, Fiber. Justifica.
• Base de datos: PostgreSQL con database/sql + pgx, o un ORM como GORM. Tú
decides y justificas.
• Manejo de errores idiomático de Go (errores como valores, no panics).
• Tests con el paquete testing nativo o testify.
• Estructura de proyecto idiomática (cmd/, internal/, pkg/ si aplica).
• Manejo de contextos (context.Context) en operaciones de BD y llamadas HTTP.
Bonus (opcional)
• Documentación OpenAPI/Swagger en al menos uno de los servicios.
• Comunicación entre servicios vía gRPC además de HTTP (o solo gRPC, justificando).
• Logging estructurado en ambos.
• Rate limiting.
• Healthcheck endpoints.
• CI básico (GitHub Actions corriendo tests de ambos servicios).
Lo que NO necesitas hacer
• Frontend.
• Deploy en producción real.
• Service mesh, Kubernetes, etc.
Qué evaluaremos específicamente
• Que el código en Go se vea idiomático y no como Java o Node escrito con sintaxis de
Go.
• Decisiones razonadas sobre qué responsabilidad va en cada servicio.
• Manejo de la comunicación entre servicios: errores, timeouts, qué pasa si el otro está
caído.
• Consistencia de datos entre los dos servicios (transacciones distribuidas, eventual
consistency, etc.).
• README claro que explique por qué cada decisión, especialmente las que tomaste por
primera vez en Go.
Instrucciones generales
• Plazo de entrega: 3 días corridos a partir de la recepción de este documento.
• Entrega: enviar repositorio público de GitHub (o privado dándonos acceso) con el
código y un README claro.
• El README debe incluir: instrucciones de instalación y ejecución, decisiones técnicas
tomadas, trade-offs, lista de lo que no alcanzó a hacer y por qué.
• Se valora más calidad que cantidad. Si no alcanzas a hacer todo, prioriza terminar bien
lo que sí entregues.
• Puedes usar las librerías que consideres apropiadas, justificando la decisión en el
README.
• No copies código de internet sin entenderlo. En la entrevista te pediremos explicar tus
decisiones.
Criterios de evaluación
• Funcionalidad: que cumpla los requisitos y funcione.
• Calidad de código: legibilidad, nombres, estructura.
• Arquitectura: patrones, separación de responsabilidades, escalabilidad.
• Testing: tests unitarios o de integración sobre la lógica crítica.
• Manejo de errores: validaciones, casos borde.
• Documentación: README y decisiones técnica
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

## Trade-offs y mejoras futuras

> Sección a completar durante la implementación.

---

## Lo que no se alcanzó a implementar

> Sección a completar antes de la entrega.

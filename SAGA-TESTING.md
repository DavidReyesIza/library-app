# Guía de pruebas de la Saga de Préstamos

Este documento describe cómo la saga orquestada en `library-service` resuelve los distintos escenarios de fallo distribuido, y cómo reproducirlos manualmente.

---

## Estados de la Saga

```
PENDING → CONFIRMED → RETURNED
   │
   └──→ COMPENSATING → COMPENSATED   (compensación exitosa)
   └──→ PENDING                      (verificación también falló — recovery manual)
   └──→ FAILED                       (error inesperado no recuperable)
```

| Estado       | Significado                                                                 |
|-------------|-----------------------------------------------------------------------------|
| `PENDING`    | La reserva de copia se hizo en library-service; loans-service aún no confirmó |
| `CONFIRMED`  | loans-service registró el préstamo exitosamente                             |
| `COMPENSATING` | loans-service no respondió; se está intentando compensar                  |
| `COMPENSATED` | La copia fue liberada después de que loans-service falló                   |
| `RETURNED`   | El usuario devolvió el libro; préstamo cerrado                              |
| `FAILED`     | Error inesperado no relacionado a disponibilidad del servicio               |

---

## Prerrequisitos

- Sistema corriendo con `docker compose up -d`
- Token JWT del usuario de prueba (ver "Obtener token" abajo)
- `jq` instalado (opcional, para formatear respuestas)

### Obtener token JWT

```bash
# Registro (primera vez)
curl -s -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Test User","email":"test@test.com","password":"password123"}' | jq .

# Login
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@test.com","password":"password123"}' | jq -r .token)

echo "TOKEN: $TOKEN"
```

### Obtener un bookId disponible

```bash
curl -s http://localhost:8080/books | jq '.[0].id'
```

---

## Caso 1: Flujo feliz — préstamo y devolución exitosa

**Ambos servicios arriba, flujo normal.**

```bash
BOOK_ID="<id del libro>"

# 1. Crear préstamo
LOAN=$(curl -s -X POST http://localhost:8080/loans \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"bookId\":\"$BOOK_ID\"}")

echo $LOAN | jq .
# Esperar: status=CONFIRMED, loanId con valor

LOAN_REQUEST_ID=$(echo $LOAN | jq -r .id)

# 2. Ver préstamo activo
curl -s http://localhost:8080/loans/me/active \
  -H "Authorization: Bearer $TOKEN" | jq .

# 3. Devolver el libro
curl -s -X POST "http://localhost:8080/loans/$LOAN_REQUEST_ID/return" \
  -H "Authorization: Bearer $TOKEN" | jq .
# Esperar: status=RETURNED

# 4. Verificar historial
curl -s http://localhost:8080/loans/me/history \
  -H "Authorization: Bearer $TOKEN" | jq .
# Esperar: status=RETURNED en el registro
```

**Resultado esperado en logs:**
```
LoanRequest created: id=..., requestId=...
Book reserved: bookId=...
Calling loans-service: POST /loans, requestId=...
Loan confirmed: loanId=...
LoanRequest status updated to RETURNED
```

---

## Caso 2: loans-service caído — compensación exitosa

**library-service arriba, loans-service detenido.**

La saga detecta que no puede contactar loans-service, verifica si el préstamo llegó igual (no llegó), y libera la copia reservada.

```bash
# 1. Detener loans-service
docker compose stop loans-service

# 2. Intentar crear un préstamo
curl -s -X POST http://localhost:8080/loans \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"bookId\":\"$BOOK_ID\"}" | jq .
# Esperar: HTTP 503, mensaje "Loan request cancelled and copy released"

# 3. Verificar que el libro recuperó su copia (available_copies incrementó)
curl -s "http://localhost:8080/books/$BOOK_ID" | jq .availableCopies

# 4. Verificar historial — debe aparecer con status=COMPENSATED
curl -s http://localhost:8080/loans/me/history \
  -H "Authorization: Bearer $TOKEN" | jq .
```

**Resultado esperado en logs:**
```
loans-service call failed, attempt 1/3: loans-service unreachable
loans-service call failed, attempt 2/3: loans-service unreachable
loans-service call failed, attempt 3/3: loans-service unreachable
Retries exhausted for requestId=..., verifying with loans-service...
Loan not found in loans-service, compensating: requestId=...
Book copy released: bookId=... (compensation)
LoanRequest status: COMPENSATED
```

```bash
# 5. Volver a levantar loans-service
docker compose start loans-service
```

---

## Caso 3: loans-service caído Y verificación también falla — PENDING manual

**Cuando ni crear el préstamo ni verificar funciona, la saga lo deja en PENDING para recovery manual.**

> Este es el caso más conservador: es peor compensar a ciegas (liberar una copia que puede estar prestada) que dejar el registro en PENDING para revisión humana.

```bash
# 1. Detener loans-service (mismo que el caso 2)
docker compose stop loans-service

# 2. Intentar crear un préstamo
curl -s -X POST http://localhost:8080/loans \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"bookId\":\"$BOOK_ID\"}" | jq .
# Esperar: HTTP 503, mensaje "loans-service unavailable and could not verify. Request left in PENDING for recovery."

# 3. El historial debe mostrar la entrada con status=PENDING
curl -s http://localhost:8080/loans/me/history \
  -H "Authorization: Bearer $TOKEN" | jq .
```

**Resultado esperado en logs:**
```
Verification also failed for requestId=... Leaving in PENDING for manual recovery.
```

**¿Por qué este comportamiento?**
Si compensáramos sin saber si el préstamo fue creado, podríamos liberar una copia que loans-service sí registró, dejando el sistema en estado inconsistente (libro disponible pero prestado en loans_db). El registro `PENDING` sirve como alerta para recovery manual o para un job de recuperación futuro.

```bash
# 4. Restaurar loans-service
docker compose start loans-service
```

---

## Caso 4: Idempotencia — mismo request_id no duplica préstamo

**Simula un retry del cliente con el mismo requestId.**

loans-service usa el campo `request_id` como clave de idempotencia: si ya existe un préstamo con ese ID, devuelve el existente en lugar de crear uno nuevo.

```bash
# Crear un préstamo normal y capturar el requestId
LOAN=$(curl -s -X POST http://localhost:8080/loans \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"bookId\":\"$BOOK_ID\"}")

REQUEST_ID=$(echo $LOAN | jq -r .requestId)

# Llamar directamente a loans-service con el mismo requestId (simula retry interno)
curl -s -X POST http://localhost:8081/loans \
  -H "X-Internal-Api-Key: $INTERNAL_API_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"request_id\":\"$REQUEST_ID\",\"user_id\":\"...\",\"book_id\":\"$BOOK_ID\"}" | jq .
# Esperar: mismo loanId, no se crea un segundo registro
```

---

## Caso 5: Reserva atómica — dos requests simultáneos sobre la última copia

**Verifica que solo uno de los dos requests gane cuando hay exactamente 1 copia disponible.**

```bash
# Asegurarse de que el libro tiene exactamente 1 copia disponible
# (devolver préstamos previos o usar un libro con availableCopies=1)

# Lanzar dos requests en paralelo
curl -s -X POST http://localhost:8080/loans \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"bookId\":\"$BOOK_ID\"}" &

curl -s -X POST http://localhost:8080/loans \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"bookId\":\"$BOOK_ID\"}" &

wait

# Verificar el estado del libro — debe tener availableCopies=0
curl -s "http://localhost:8080/books/$BOOK_ID" | jq .availableCopies
# Esperar: 0, no -1

# Un request debe haber sido exitoso (CONFIRMED) y el otro rechazado (400 "No copies available")
curl -s http://localhost:8080/loans/me/active \
  -H "Authorization: Bearer $TOKEN" | jq 'length'
# Esperar: 1 (solo un préstamo activo)
```

**¿Por qué no hay race condition?**
El SQL de reserva usa un `UPDATE` atómico con condición `WHERE available_copies > 0`, que garantiza que solo una transacción puede decrementar a 0. Si el update devuelve 0 filas afectadas, se rechaza el préstamo sin llegar a llamar a loans-service.

---

## Referencia rápida de comandos

| Acción                              | Comando                                                                                     |
|-------------------------------------|---------------------------------------------------------------------------------------------|
| Ver estado de todos los servicios   | `docker compose ps`                                                                         |
| Detener loans-service               | `docker compose stop loans-service`                                                         |
| Levantar loans-service              | `docker compose start loans-service`                                                        |
| Ver logs de library-service en vivo | `docker compose logs -f library-service`                                                    |
| Ver logs de loans-service en vivo   | `docker compose logs -f loans-service`                                                      |
| Ver loans en la BD de library       | `docker compose exec library-db psql -U postgres library_db -c "SELECT id, status, loan_id FROM loan_requests ORDER BY created_at DESC LIMIT 10;"` |
| Ver loans en la BD de loans         | `docker compose exec loans-db psql -U postgres loans_db -c "SELECT id, request_id, status FROM loans ORDER BY created_at DESC LIMIT 10;"` |
| Reiniciar todo desde cero           | `docker compose down -v && docker compose up --build -d`                                   |

---

## Script de pruebas automatizado

El script `scripts/test-saga.sh` ejecuta los tres casos de uso principales de forma automatizada, levantando y deteniendo servicios según el escenario que toca probar.

### Cómo correrlo

```bash
# Desde la raíz del proyecto, con docker compose up -d corriendo
cd ..
./scripts/test-saga.sh
```

### Qué hace el script paso a paso

**Fase 0 — Reset automático**
Antes de cualquier test, el script resetea el estado de la BD: restaura `available_copies = total_copies` en todos los libros y marca como `COMPENSATED` cualquier `PENDING` de un run anterior. Esto garantiza que el test siempre parte desde un estado conocido y no depende del estado que haya dejado una ejecución previa.

**Caso 1 — Flujo feliz (ambos servicios arriba)**
```
1. POST /loans                          → espera status=CONFIRMED y loanId con valor
2. GET /loans/me/active                 → espera al menos 1 préstamo activo
3. POST /loans/{loanRequestId}/return   → espera HTTP 204
4. GET /loans/me/history                → espera status=RETURNED en el registro
```
Verifica el ciclo completo: creación → activo → devolución → historial.

**Caso 2 — loans-service caído (ambigüedad de fallo)**
```
1. docker compose stop loans-service
2. POST /loans                          → espera HTTP 503
3. GET /loans/me/history                → verifica que el LoanRequest quedó en PENDING
   (la saga no compensó a ciegas porque la verificación también falló)
4. docker compose start loans-service
5. El script simula el recovery job:    → marca el PENDING como COMPENSATED en BD
   restaura available_copies manualmente para que el Caso 3 pueda continuar
```
Demuestra que la saga **nunca pierde el registro** de un intento fallido y que no compensa sin evidencia de que el préstamo no existe.

**Caso 3 — Reserva atómica (race condition)**
```
1. Se verifica que el libro tiene exactamente 1 copia disponible
2. Se lanzan DOS requests POST /loans en paralelo con &
3. Se espera con `wait` a que ambos terminen
4. Se verifica:
   - Uno respondió CONFIRMED, el otro 409 Conflict
   - availableCopies >= 0 (nunca llegó a -1)
   - Solo 1 préstamo activo en GET /loans/me/active
```
Demuestra que el `UPDATE books SET available_copies = available_copies - 1 WHERE available_copies > 0` es atómico: solo una de las dos transacciones concurrentes puede decrementar a 0.

### Por qué el Caso 2 restaura manualmente en lugar de esperar el recovery job

El recovery job corre cada 60 segundos y solo procesa registros con más de 2 minutos de antigüedad (configurable). Esperar eso en el script haría el test demasiado lento. La restauración manual simula exactamente lo que haría el job y permite que el Caso 3 tenga copias disponibles para probar la concurrencia.

### Salida esperada

```
══════════════════════════════════════════
  Setup
══════════════════════════════════════════
  → Obteniendo token JWT (admin)...
  ✓ Token obtenido
  ✓ Libro: <uuid> (availableCopies=1)

══════════════════════════════════════════
  Caso 1: Flujo feliz (CONFIRMED → RETURNED)
══════════════════════════════════════════
  ✓ Préstamo creado: status=CONFIRMED, loanId=<uuid>
  ✓ GET /loans/me/active devuelve 1 préstamo(s) activo(s)
  ✓ Devolución exitosa (HTTP 204)
  ✓ LoanRequest.status = RETURNED en historial

══════════════════════════════════════════
  Caso 2: loans-service caído → saga gestiona el fallo
══════════════════════════════════════════
  ✓ HTTP 503 recibido correctamente (loans-service caído)
  ✓ LoanRequest guardado con status=PENDING
  ✓ Restauración manual aplicada (simula lo que haría el recovery job)
  ✓ Caso 2 completado

══════════════════════════════════════════
  Caso 3: Reserva atómica — dos requests simultáneos
══════════════════════════════════════════
  → Request 1: status=CONFIRMED
  → Request 2: status=409
  ✓ availableCopies >= 0 (no llegó a negativo: 0)

══════════════════════════════════════════
  Resumen
══════════════════════════════════════════
  Todos los tests pasaron ✓
```

---

## Tabla de decisiones de la Saga

| Escenario                                              | Acción de la saga                     | Estado final        |
|--------------------------------------------------------|---------------------------------------|---------------------|
| loans-service responde OK                              | Confirma préstamo                     | `CONFIRMED`         |
| loans-service falla → verificación OK (préstamo existe) | Confirma sin duplicar               | `CONFIRMED`         |
| loans-service falla → verificación OK (no existe)     | Libera copia reservada                | `COMPENSATED`       |
| loans-service falla → verificación también falla      | No compensa, deja para recovery       | `PENDING`           |
| Usuario devuelve libro exitosamente                    | Cierra el préstamo                    | `RETURNED`          |
| No hay copias disponibles al crear                     | Rechaza antes de llamar loans-service | 400 (no se crea)    |

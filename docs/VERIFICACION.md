# Evidencia local — 2026-09-27

## Resultados reales

| Verificación | Resultado |
|---|---|
| Java | Temurin 21.0.12.1; compilación release 21 |
| Maven verify | BUILD SUCCESS; 11 tests, 0 fallos, 0 errores, 0 omitidos |
| Integración | 7 tests: alta/response vacío, duplicados, concurrencia, entrada inválida, 401/403, JWT inválidos, ownership/aislamiento |
| Guard de tests | 4 tests: base válida, rechazo base compartida/parámetros, rechazo admin/conexiones alternativas y bloqueo antes de crear singletons |
| Compose histórico | config --quiet correcto; proyecto siga-local y volúmenes históricos identificados antes de arrancar |
| Dependencias | PostgreSQL 17.11 healthy; Redis PONG; RabbitMQ Ping succeeded |
| Catalog | readiness UP en 127.0.0.1:8082; empaquetado ejecutable en .local/runtime |
| Identity histórico | readiness UP en 127.0.0.1:9081; runtime/claves existentes |
| HTTP con Identity real | JWKS 200, login 200, MFA 200; Catalog sin token 401, alta 201, duplicado 409, request inválido 400 |
| Destino del smoke | Catalog temporal en 18082, base siga_catalog_local_test; proceso temporal terminado |
| Migraciones | V1/V2/V3 success en catalog, tanto siga como base aislada |
| Tablas | category, supplier, unit_measure, product, unit_conversion, outbox_event + historial técnico Flyway |
| Ownership | siga.catalog y todas sus tablas pertenecen a siga_catalog; iam continúa con siga_iam |
| Permisos | Catalog sin CREATE sobre siga; solo CREATE en catalog; cero tablas ajenas con INSERT/UPDATE/DELETE/TRUNCATE |
| Aislamiento | siga_catalog_test sin CONNECT sobre siga; sin superuser/createdb/createrole |
| FK | cero FK de catalog hacia otro esquema |
| IAM | pg_dump --schema-only antes/después idéntico, excluyendo tokens aleatorios restrict/unrestrict de pg_dump |
| Canónico | bloque completo Catalog de physical_model.sql idéntico a V2; OpenAPI local idéntico normalizando CRLF/LF |
| Preservación | MinIO y los cuatro contenedores repro-20260926 permanecen detenidos; ningún volumen nuevo |
| Repetición Init | Hash de .local/catalog.json idéntico antes/después; roles y credenciales conservados |
| Scripts | StartDependencies comprobado; ambos scripts PowerShell sin errores de sintaxis |
| Git | Identity/Infrastructure/Gateway sin cambios; Documentation conserva únicamente su AGENTS.md previo; diff --check sin errores en Catalog |

La comprobación IAM es de definición del schema; el login/MFA real sí crea/actualiza estado de autenticación normal mediante Identity. Catalog no tiene acceso de escritura a esas tablas.

## Fallos encontrados y corregidos

- Docker Desktop estaba detenido: se inició el motor y se inspeccionaron contenedores antes de usar start.
- V1 original exigía CREATE DATABASE-schema privilege: fallo transaccional en pruebas, sin aplicación exitosa. Se reemplazó por validación de ownership antes de la primera migración exitosa; se mantiene el privilegio mínimo.
- Un argumento JSON Maven perdió comillas bajo PowerShell: retirado. DEBUG se establece por entorno sin JSON.
- PowerShell trataba una advertencia stderr de Mockito como error terminante: se maneja la salida nativa y se comprueba el exit code de Maven. La ejecución final pasó.

## Límites

No se ha probado Gateway, otros endpoints, publicador/consumidores RabbitMQ, MinIO, instalación desde Windows limpio ni CI remoto. La suite usa dependencias Maven cacheadas y servicios externos Compose. La respuesta 403 se verifica con firma/JWKS real de prueba y permiso insuficiente; el smoke con Identity real usa el permiso PRODUCT_WRITE del usuario demo existente.

Archivos locales de evidencia (ignorados): `.local/test-run.log`, `.local/test-build/surefire-reports`, `.local/start-run.log`, `.local/catalog.*.log`, `.local/identity.*.log`, `.local/smoke-catalog.log`. Contienen diagnóstico local y no deben publicarse sin revisión.

## Incremento POST /api/v1/units — 2026-09-27

Se implementó únicamente `POST /api/v1/units`: REST → `CreateUnitOfMeasureUseCase` → `CreateUnitOfMeasureService` → `UnitOfMeasureStore` → `PostgresUnitOfMeasureStore` → `catalog.unit_measure`. El endpoint conserva autenticación JWT RS256/JWKS y exige `PRODUCT_WRITE`. La respuesta contractual observada es `201 Created` sin cuerpo.

| Verificación | Resultado |
|---|---|
| Maven verify | BUILD SUCCESS; 15 pruebas, 0 fallos, 0 errores, 0 omitidas |
| Nuevas pruebas de unidades | 4: alta/persistencia/respuesta vacía; código duplicado; obligatoriedad y límites 20/80/20/40; 401/403 sin escritura |
| Smoke con Identity real | JWKS 200, login 200, MFA 200; `POST /units`: 401 sin token, 201 con `PRODUCT_WRITE`, 409 duplicado, 400 request vacío |
| Datos del smoke | Solo `siga_catalog_local_test`; Catalog temporal en 18082 detenido por el script |
| Servicios tras el incremento | Catalog readiness UP en 127.0.0.1:8082; Identity readiness UP en 127.0.0.1:9081; PostgreSQL/Redis/RabbitMQ reutilizados |
| IAM | pg_dump schema-only antes/después idéntico, salvo tokens `restrict/unrestrict` no deterministas de pg_dump |
| Migraciones/tablas | V1–V3 y las seis tablas existentes sin cambios; no se creó tabla ni migración |

El contrato de POST no define un cuerpo de respuesta, por lo que se conserva 201 vacío. `GET /units` sigue sin implementarse porque su respuesta no está definida. CUS-23 menciona PATCH y ProductUpdated para conversiones, mientras OpenAPI solo define GET/POST de units: PATCH, conversiones y eventos permanecen pendientes de contrato aprobado. No hay regla canónica de normalización de código ni de catálogo/compatibilidad de dimensiones para una unidad maestra; no se implementaron.

### Corrección de detalles 400 de unidades

Los `400 VALIDATION_ERROR` de `/api/v1/units` ahora conservan el mismo Problem Details y código, pero `detail` identifica la unidad y el campo: `code`, `name`, `symbol` o `dimension`. Los errores de categorías no cambiaron y continúan con `Revisa los campos de la categoría`.

La suite aislada volvió a pasar: 15 pruebas, 0 fallos, 0 errores. Las pruebas de unidades comprueban los cuatro límites físicos y sus detalles; las de categorías comprueban el detalle legado en sus requests inválidos. El caso de obligatoriedad usa un request con solo `code` ausente, porque un cuerpo vacío tiene cuatro errores y Spring no define cuál debe aparecer primero.

## Incremento GET /api/v1/categories y GET /api/v1/units — 2026-09-27

Se aprobó y documentó primero el contrato canónico: ambos GET exigen PRODUCT_WRITE, responden 200 application/json con un arreglo directo, incluyen activos e inactivos y ordenan code ASC, sin filtros ni paginación. Categorías devuelve {id,code,name,categoryType,active}; unidades devuelve {id,code,name,symbol,dimension,active}.

| Verificación | Resultado |
|---|---|
| Maven verify | BUILD SUCCESS; 21 pruebas, 0 fallos, 0 errores, 0 omitidas |
| Categorías GET | Forma exacta, orden code ASC, registro inactivo visible, arreglo vacío, 401 y 403; POST preservado |
| Unidades GET | Forma exacta, orden code ASC, registro inactivo visible, arreglo vacío, 401 y 403; POST preservado |
| Base de pruebas | Flyway validó V1–V3 y no aplicó migraciones nuevas |
| Smoke real | Pendiente de ejecutar tras arrancar el JAR actualizado; consulta JWKS/Login/MFA y ambos GET con Identity real |

La regla aprobada para Product quedó documentada, pero no implementada: sus selectores mostrarán solo categorías y UoM activos, y el futuro backend deberá rechazar categoryId, storageUnitId o baseUnitId inactivos.

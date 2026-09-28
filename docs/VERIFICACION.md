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

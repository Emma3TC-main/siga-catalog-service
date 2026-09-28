# Catalog en la instancia histórica siga-local

Requisitos: Windows/PowerShell, Docker Desktop con motor Linux iniciado, Java 21, repositorios hermanos Catalog/Infrastructure/Identity/Documentation. Maven Wrapper incluido. Python 3 solo para el smoke HTTP con Identity. Ejecutar desde `siga-catalog-service`.

Este procedimiento incorpora Catalog a la instancia **existente** `siga-local`. No crea otro stack ni sirve para reemplazar una instancia perdida. Infrastructure conserva `compose/compose.local.yml` y `compose/.env.local`; Identity conserva `.env`, sus claves y su runtime histórico. Si falta alguno, recuperar la configuración correspondiente. No ejecutar Setup-Local ni inicializadores de instancias nuevas para recuperar volúmenes históricos.

## Instalación inicial de Catalog

```powershell
java --version
docker compose version
./scripts/Local.ps1 -Action StartDependencies
./scripts/Local.ps1 -Action Init
./scripts/Local.ps1 -Action Test
./scripts/Local.ps1 -Action Start
./scripts/Identity-Local.ps1 -Action Start
```

Si la política de PowerShell lo exige, usar `Set-ExecutionPolicy -Scope Process RemoteSigned`; no cambiar la política permanente. Docker y Maven necesitan acceso al motor local y a la caché del usuario. En una máquina sin dependencias Maven cacheadas puede ser necesaria conexión a Maven Central.

Init verifica el proyecto existente, genera **solo** secretos nuevos de Catalog en `.local/catalog.json` y provisiona `siga_catalog`, `siga_catalog_test`, schema `catalog` y base `siga_catalog_local_test`. Si encuentra datos/roles sin ese archivo, falla; no rota contraseñas. Repetirlo conserva los secretos. No compartir ni versionar `.local`.

`catalog` en `siga` pertenece a `siga_catalog`; en la base de pruebas pertenece a `siga_catalog_test`. Ninguno es superusuario ni puede crear roles/bases. No se concede CREATE sobre la base ni permisos sobre IAM. Init revoca CONNECT de PUBLIC sobre `siga` y lo concede explícitamente a los roles operativos existentes `siga_iam` y `siga_catalog`: así el login de pruebas no puede conectar a siga. Si se incorporan otros servicios, Infrastructure deberá conceder CONNECT explícito a sus roles.

Flyway de Catalog aplica V1 (verificación de ownership), V2 (seis tablas canónicas), V3 (demo ya existente en el repositorio). No ejecuta el SQL consolidado. Las migraciones son exclusivas de este repositorio; no editar versiones ya aplicadas. `flyway_schema_history` es una tabla técnica adicional a las seis de negocio.

## Arranque habitual

```powershell
./scripts/Local.ps1 -Action StartDependencies
./scripts/Identity-Local.ps1 -Action Start
./scripts/Local.ps1 -Action Start
./scripts/Local.ps1 -Action Status
Invoke-RestMethod http://127.0.0.1:8082/actuator/health/readiness
```

StartDependencies usa `docker compose ... start postgres redis rabbitmq`: no crea/recrea contenedores. MinIO no participa. Los antiguos Redis/RabbitMQ no tienen healthcheck Docker; el script usa `redis-cli ping` y `rabbitmq-diagnostics ping`, además de `pg_isready`.

Catalog escucha solo en `127.0.0.1:8082`; Identity API en 8081 y management en 9081; PostgreSQL en 15432, Redis en 6379, RabbitMQ en 5672/15672. El puerto PostgreSQL de Catalog se registra a partir del contenedor inspeccionado. El endpoint público de descubrimiento es `http://127.0.0.1:8081/.well-known/jwks.json`. Defaults de issuer/audience: `siga-identity`/`siga-api`, como el runtime histórico.

Start compila en `.local/build`, copia el JAR por SHA-256 a `.local/runtime`, registra PID/ruta y espera readiness. Los logs son `.local/catalog.stdout.log` y `.local/catalog.stderr.log`. No sobrescribe `target` versionado. Falla si el PID registrado está vivo o el puerto ocupado. La salud valida la DB; JWKS se obtiene al validar el token, por lo que readiness no implica que Identity esté disponible.

Identity-Local reutiliza `.local/siga-local-runtime/target/siga-identity-service-0.0.1-SNAPSHOT.jar` del repo Identity y su importador de entorno sin `-Instance`. No compila ni modifica código/claves IAM. Registra el proceso iniciado en **Catalog** `.local/identity.*`; si Identity ya estaba saludable, lo conserva. Para otra máquina sin ese runtime, seguir `siga-identity-service/docs/LOCAL.md` para preparar el runtime de su entorno histórico antes de ejecutar este helper. No usar `-Instance repro-20260926`.

## Pruebas

```powershell
./scripts/Local.ps1 -Action Test
python ./scripts/smoke_identity.py
```

La suite Java usa PostgreSQL externo del mismo Compose, base `siga_catalog_local_test`, login `siga_catalog_test`; no es Testcontainers. El inicializador del classpath de tests comprueba URL, usuario, schema y conexiones alternativas antes de crear DataSource/Flyway. Los fixtures eliminan solo categorías que crean con códigos UUID; nunca truncan IAM ni usan la base `siga`. Reportes en `.local/test-build/surefire-reports`; JaCoCo en `.local/test-build/site/jacoco`.

El smoke necesita Identity activo, su usuario demo ya enrolado y `.local/demo-mfa.json` existente en Identity. No enrola usuarios ni genera claves. Inicia temporalmente el mismo JAR de Catalog en `127.0.0.1:18082`, **con el rol/base aislados**, obtiene token real por login/MFA, verifica 401/201/409/400 y termina ese proceso en finally. Deja una categoría SMOKE de evidencia en la base de pruebas; no en siga. No imprime contraseñas, OTP ni tokens. No ejecutar repetidamente dentro de una ventana TOTP o ante bloqueo de usuario; seguir la política de Identity.

Los JWT artificiales de la suite se firman con una clave efímera de prueba y un servidor JWKS en loopback; no se reutiliza ni copia la clave privada IAM. El smoke sí consulta el JWKS real.

Para uso manual autorizado: POST `http://127.0.0.1:8082/api/v1/categories` con `Authorization: Bearer <accessToken>` y JSON `{"code":"CAT-01","name":"Categoría","categoryType":"MATERIAL"}`. Es una escritura real en siga; reservar las comprobaciones automáticas para la base aislada. Responde 201 vacío. GET/PATCH de categorías aún no se implementan.

## Parada sin borrar datos

```powershell
./scripts/Local.ps1 -Action Stop
./scripts/Identity-Local.ps1 -Action Stop
./scripts/Local.ps1 -Action StopDependencies
```

Los helpers comprueban PID y JAR antes de parar procesos. El helper Identity solo administra el PID que él inició. Si Identity fue iniciado por otra terminal, detenerlo allí antes de parar dependencias compartidas. No se ejecuta down, down -v, prune ni borrado de volúmenes.

## Alcance pendiente

Gateway no contiene aplicación funcional; la prueba realizada es Identity → Catalog directo en loopback. Contratos pendientes de categorías/unidades, actualización y eventos: ver [REVISION.md](REVISION.md). No hay publicador RabbitMQ, cliente Redis ni integración MinIO en esta slice.

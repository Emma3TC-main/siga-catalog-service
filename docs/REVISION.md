# Revisión previa — 2026-09-27

Fuente: `../../siga-documentation/SIGA_Documentacion_Tecnica_Final_v1.2/` (rutas siguientes relativas a esa raíz). No se usan `diagramas_render` ni `out`.

## Estado encontrado

Catalog limpio, rama `chore/configuracion-entorno`, sin Java ni tests; pom Java 21 / Boot 3.5.16, configuraciones vacías salvo local con credenciales hardcodeadas y clave pública por archivo. `target` está versionado: los builds nuevos van a `.local`. V1 originalmente creaba catalog; V2 ya contiene exactamente las seis tablas canónicas; V3 incluye demo de proveedor, categorías, unidades, productos y conversiones. V2/V3 se conservan sin editar. La copia `docs/openapi.yaml` coincide con el original al normalizar finales de línea; no lo sustituye.

V1 no estaba aplicada en la instancia histórica (catalog no existía). En pruebas falló y se revirtió porque PostgreSQL exige CREATE sobre la base aun con IF NOT EXISTS. Antes de su primera aplicación exitosa se cambió a una comprobación de schema/owner preaprovisionado. Esto evita conceder CREATE sobre siga; no se modificó ninguna migración aplicada.

Infrastructure e Identity limpios. Documentation contiene un AGENTS.md no versionado que se conserva. Gateway solo tiene recursos, sin Java funcional: integración por Gateway pendiente.

## Fuentes y decisiones

- `Manual_Tecnico_SIGA_Final.md` §3, línea 474 (errores), §7.1 y línea 491 (JWT/RBAC), adenda proveedores; ADR-002/004/006/007/011/018: seis servicios, schema propio, sin FK externas, RS256, eventos selectivos y outbox local.
- `database/logical_model.md` §3.2; `database/physical_model.sql` bloque CATALOG; `database/dictionary.md` secciones catalog.*; `diagramas/datos/DER_05_Fisico_Catalog.puml`: ownership y seis tablas coincidentes.
- `diagramas/uml/UML_03_Clases_Catalog.puml`, `UML_07_Paquetes_Backend.puml`; `diagramas/c4/C4_02_Contenedores_Docker_GCP.puml`; `diagramas/secuencia/SEQ_03_Crear_Producto.puml`: dominio independiente, puertos/adaptadores, Redis opcional, almacenamiento de archivos fuera de Catalog.
- `api/catalog-openapi.yaml`; `especificaciones/CUS_Detallados.md` CUS-04/05/23/31 y anexo conversiones; `trazabilidad/requirements.md` RF-04/05/23/35 y `testing-matrix.md` TC-AUTH-002: autorización y dependencias.

## Modelo

Todas las PK son UUID con gen_random_uuid(). Category: code único (50), name (120), tipo MATERIAL/INSUMO/REPUESTO/MAQUINARIA. Supplier: code y tax_id únicos; razón social obligatoria; contacto opcional. Unit_measure: code único, name/symbol/dimension obligatorios. Product: SKU único, FK locales a category y dos unit_measure; min_stock >= 0, flags de trazabilidad, technical_attributes JSONB. Unit_conversion: FK locales a producto/unidades; factor > 0, unidades distintas, UNIQUE(product_id,from_unit_id,to_unit_id). Las cinco entidades tienen active, version y timestamps. Outbox_event: estado PENDING/PUBLISHED/FAILED, schema_version > 0, attempts >= 0, payload JSONB e índice parcial pendiente. Flyway añade únicamente su tabla técnica de historial, no una séptima entidad de dominio.

## Contratos y permisos

OpenAPI: GET/POST categories y units; GET/POST products y suppliers; GET/PUT products/{id} y suppliers/{id}. GET categories responde arreglo directo `{id,code,name,categoryType,active}` y GET units `{id,code,name,symbol,dimension,active}`; ambos requieren PRODUCT_WRITE, incluyen activos/inactivos, ordenan `code ASC` y no admiten filtros/paginación. CUS-04/05/23 usa PRODUCT_WRITE; CUS-31 SUPPLIER_MANAGE. No se deduce un permiso de lectura distinto. Eventos nombrados: ProductCreated, ProductUpdated (incluye conversiones), SupplierCreated/Updated/Disabled; consumidores Inventory y Reporting. No se define un evento obligatorio de alta de unidad independiente.

Primera slice: POST /api/v1/categories, request code/name/categoryType y respuesta 201 sin cuerpo como declara OpenAPI. No hay dependencia de producto, unidad, broker ni MinIO para esta operación. Se valida contra límites del SQL de mayor prioridad; no se inventan normalización de códigos, unicidad case-insensitive ni campos de respuesta. Errores 400/401/403/409/503 siguen el manual, aunque OpenAPI no los enumera en esta operación.

## Contradicciones y decisiones pendientes

1. `especificaciones/CUS_Detallados.md` CUS-04/05/23 declara PATCH; `api/catalog-openapi.yaml` no define PATCH, define PUT solo para productos/proveedores. No implementar actualizaciones ambiguas.
2. Resuelto: GET categories/units define arreglo directo, campos, activos/inactivos y `code ASC`, sin filtros ni paginación. POST sigue con 201 vacío por contrato.
3. CategoryRequest no declara maxLength, pero `database/physical_model.sql` y diccionario sí (50/120). Se aplican límites físicos por ADR-018. No hay minLength/no-blank: no añadir esas reglas sin aprobación.
4. CUS-04 menciona “Product/Category metadata event selectivo” sin nombre, disparador ni payload. UML-03/ADR-007 solo concretan eventos Product/Supplier. Esta slice persiste categoría sin publicar un evento inventado; integración de categoría pendiente de definición.
5. ProductRequest/SupplierRequest de actualización no incluyen versión esperada aunque responses/modelo incluyen version. Política de concurrencia pendiente. Product deberá validar en backend categoría/UoM activas; selectores muestran solo maestros activos. Dimensiones compatibles, flags contradictorios y validación tributaria parametrizable necesitan reglas concretas antes de implementarse.
6. ADR-018 conserva conteo histórico 40 y adenda v1.2 agrega proveedores; Catalog sí tiene seis tablas coherentes en SQL/DER/diccionario.
7. Identity valida además estado/sesión con su propio schema. Catalog sigue manual §7.1: JWKS público, RS256, issuer/audience/exp y claim permissions; no accede a IAM. Revocación inmediata distribuida no definida; no se copia lógica IAM.

Orden recomendado: completar contratos de categorías → unidades maestras → productos con conversiones y outbox → proveedores con outbox. Proveedores no dependen de productos y pueden adelantarse cuando esté aprobado su contrato de eventos.

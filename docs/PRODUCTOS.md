# Avance: consulta y actualización de productos — 2026-09-29

Base: `feat/estructura-catalogo`, commit `b8f85a1`. Se conservan sus paquetes
`application/port`, `application/usecase`, `domain/model`, `infrastructure` e
`interfaces/rest`; son también compatibles con UML-07. No se renombra ni reestructura
el avance de Emmanuel. Se mantienen sin cambios OpenAPI, migraciones, configuración e infraestructura.

## Fuentes revisadas

Documentación canónica de `siga-documentation`, commit
`ada75e03d71cd65e8e6056d606db17844ddd4de9`:

- `api/catalog-openapi.yaml` y contrato del servicio `docs/openapi.yaml`.
- `database/physical_model.sql`, `database/logical_model.md`, DER-05 y ADR-018.
- UML-03, UML-07, SEQ-03, SEQ-11, STATE-04 y C4-02.
- CUS-05/CUS-23 y su anexo de conversiones; manual técnico, secciones de permisos y Outbox.

STATE-02 describe activos serializados de Inventory, no un nuevo estado de Product.
No se derivan reglas de imágenes ni de `diagramas_render`.

## Operaciones

| Método y ruta | Comportamiento |
|---|---|
| GET /api/v1/products | Productos activos e inactivos, ordenados por SKU e ID, con conversiones |
| GET /api/v1/products/{id} | Producto y conversiones; 404 si no existe |
| PUT /api/v1/products/{id} | Sustitución de los campos de ProductRequest y de la colección de conversiones; 200 |

Las tres rutas conservan el permiso `PRODUCT_WRITE` y la validación JWT del servicio.
No se agregan PATCH, DELETE ni campos ajenos al contrato.
POST sigue disponible con su respuesta 201 y evento ProductCreated.

GET usa el mismo ProductResponse que POST/PUT, con id, active y version.
Los UUID inválidos producen 400; los productos inexistentes, 404; los SKU duplicados,
409. Se conserva Problem Details y X-Correlation-ID.

## Listado y límites

El contrato describe ProductPage, pero no define parámetros de filtro o paginación
para GET /products. Para no inventarlos, esta entrega devuelve una página completa:
`content` contiene el catálogo, `page=0`, `size=content.length` y
`totalElements=content.length`.

No se afirma filtrado ni paginación configurable. El listado completo es una
limitación para catálogos grandes. El equipo deberá definir los parámetros y
límites en la fuente canónica antes de incorporar paginación o filtros.

## Actualización y concurrencia

Se implementa PUT /products/{id}, definido por OpenAPI. No se implementa el PATCH
mencionado de forma genérica en CUS. La petición reutiliza las reglas y valores
por defecto de creación: los campos opcionales omitidos vuelven a esos valores.
No se interpreta PUT como actualización parcial.

La aplicación bloquea la fila de producto con FOR UPDATE. En una única transacción:

1. Comprueba existencia y valida categoría, unidades y conversiones con las reglas de POST.
2. Actualiza el producto, incrementa version y conserva active, id y created_at.
3. Sustituye conversiones: mantiene IDs de pares from/to existentes, actualiza factor/active
   e incrementa su version; inserta pares nuevos y elimina los omitidos.
4. Guarda ProductUpdated v1 PENDING en catalog.outbox_event, con correlación,
   versión actual y el conjunto completo de conversiones.

Si falla cualquiera de esos pasos, la transacción revierte. No se modifica
inventario ni sus snapshots históricos. Si almacenamiento y base difieren,
debe seguir existiendo la conversión directa activa que exige el dominio.

ProductRequest no incluye active: PUT no activa/desactiva el producto.
Tampoco incluye versión esperada ni If-Match. El bloqueo evita mezclar escrituras
simultáneas, pero no detecta que un usuario envíe una edición basada en una lectura
antigua: la última escritura confirmada prevalece. El control de edición obsoleta
requiere una decisión contractual del equipo; no se añadió un campo obligatorio.

El evento usa la misma forma de payload de ProductCreated del avance base.
La publicación RabbitMQ y las proyecciones Inventory/Reporting no se implementan
ni se verifican en esta entrega: el evento permanece pendiente en Outbox.

## Verificación realizada

- Compilación correcta con Java 21: `./mvnw.cmd -B -Dmaven.test.skip=true compile`.
- Revisión estática de rutas, permisos, transacción y diferencias.
- OpenAPI, migraciones y archivos de pruebas conservados.
- No se ejecutaron pruebas, consultas contra PostgreSQL, llamadas HTTP ni servicios locales.

Pendiente con la infraestructura del equipo: comprobar GET, PUT, validación 400,
autorización 401/403, inexistentes 404, SKU duplicado 409, rollback, concurrencia,
conversiones y consumo de ProductUpdated. La compilación no demuestra esos comportamientos.

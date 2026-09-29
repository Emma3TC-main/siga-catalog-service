# siga-catalog-service

Operaciones implementadas: `POST/GET /api/v1/categories`, `POST/GET /api/v1/units`,
`POST/GET /api/v1/products` y `GET/PUT /api/v1/products/{id}`.
Todas requieren JWT RS256/JWKS de Identity y permiso `PRODUCT_WRITE`; la persistencia es PostgreSQL.
Los GET de categorías/unidades devuelven arreglos directos ordenados por código e incluyen activos e inactivos.
El listado de productos devuelve el objeto `ProductPage` del contrato.

- [Arranque y pruebas en PowerShell](docs/LOCAL.md)
- [Revisión canónica y decisiones pendientes](docs/REVISION.md)
- [Resultados comprobados](docs/VERIFICACION.md)
- [Avance de productos y límites del contrato](docs/PRODUCTOS.md)

REST → casos de uso → puertos → adaptadores PostgreSQL. Se conserva la estructura de
`feat/estructura-catalogo` y las invariantes de producto existentes.
Las escrituras de producto/conversiones y su evento Outbox son transaccionales.
Los proveedores y la publicación/consumo de eventos quedan para avances posteriores.

Este avance se comprobó mediante compilación con Java 21, sin ejecutar pruebas ni infraestructura.
Los resultados históricos de `docs/VERIFICACION.md` no validan los endpoints incorporados en este avance.

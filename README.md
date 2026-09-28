# siga-catalog-service

Slices disponibles: `POST/GET /api/v1/categories` y `POST/GET /api/v1/units`, JWT RS256/JWKS de Identity, permiso `PRODUCT_WRITE` y PostgreSQL. Los GET devuelven arreglos directos ordenados por código e incluyen registros activos e inactivos.

- [Arranque y pruebas en PowerShell](docs/LOCAL.md)
- [Revisión canónica y decisiones pendientes](docs/REVISION.md)
- [Resultados comprobados](docs/VERIFICACION.md)

REST → casos de uso → puertos → adaptadores PostgreSQL → `catalog.category`/`catalog.unit_measure`. Dominio y aplicación independientes de Spring. No se implementan todavía productos, proveedores, conversiones ni sus eventos.

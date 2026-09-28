# siga-catalog-service

Primera slice: `POST /api/v1/categories`, JWT RS256/JWKS de Identity, permiso `PRODUCT_WRITE`, PostgreSQL y respuesta `201` sin cuerpo.

- [Arranque y pruebas en PowerShell](docs/LOCAL.md)
- [Revisión canónica y decisiones pendientes](docs/REVISION.md)
- [Resultados comprobados](docs/VERIFICACION.md)

REST → `CreateCategoryUseCase` → `CreateCategoryService` → `CategoryStore` → `PostgresCategoryStore` → `catalog.category`. Dominio y aplicación independientes de Spring. No se implementan todavía los demás endpoints.

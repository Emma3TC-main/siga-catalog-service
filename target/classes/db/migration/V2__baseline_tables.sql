-- TODO: Pegar aquí los CREATE TABLE correspondientes al esquema 'catalog'.
-- REGLA: 0 FK hacia otros esquemas. Relaciones inter-dominio solo por ID.
-- ============================================================
-- CATALOG / catalog-service
-- ============================================================
CREATE TABLE catalog.category (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(50) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    category_type varchar(40) NOT NULL CHECK (category_type IN ('MATERIAL','INSUMO','REPUESTO','MAQUINARIA')),
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE catalog.supplier (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(50) NOT NULL UNIQUE,
    tax_id varchar(20) NOT NULL UNIQUE,
    business_name varchar(200) NOT NULL,
    trade_name varchar(200),
    contact_name varchar(160),
    phone varchar(40),
    email varchar(254),
    address varchar(300),
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_supplier_business_name ON catalog.supplier(business_name);

CREATE TABLE catalog.unit_measure (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(20) NOT NULL UNIQUE,
    name varchar(80) NOT NULL,
    symbol varchar(20) NOT NULL,
    dimension varchar(40) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE catalog.product (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sku varchar(80) NOT NULL UNIQUE,
    name varchar(200) NOT NULL,
    category_id uuid NOT NULL REFERENCES catalog.category(id),
    product_type varchar(40) NOT NULL CHECK (product_type IN ('MATERIAL','INSUMO','REPUESTO','MAQUINARIA')),
    storage_unit_id uuid NOT NULL REFERENCES catalog.unit_measure(id),
    base_unit_id uuid NOT NULL REFERENCES catalog.unit_measure(id),
    min_stock numeric(18,6) NOT NULL DEFAULT 0 CHECK (min_stock >= 0),
    requires_lot boolean NOT NULL DEFAULT false,
    requires_heat_number boolean NOT NULL DEFAULT false,
    requires_expiry boolean NOT NULL DEFAULT false,
    requires_serial boolean NOT NULL DEFAULT false,
    technical_attributes jsonb NOT NULL DEFAULT '{}'::jsonb,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_product_category ON catalog.product(category_id);
CREATE INDEX idx_product_technical_attributes ON catalog.product USING gin(technical_attributes);

CREATE TABLE catalog.unit_conversion (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES catalog.product(id),
    from_unit_id uuid NOT NULL REFERENCES catalog.unit_measure(id),
    to_unit_id uuid NOT NULL REFERENCES catalog.unit_measure(id),
    factor numeric(18,6) NOT NULL CHECK (factor > 0),
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (product_id, from_unit_id, to_unit_id),
    CHECK (from_unit_id <> to_unit_id)
);
CREATE INDEX idx_unit_conversion_product ON catalog.unit_conversion(product_id);

CREATE TABLE catalog.outbox_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type varchar(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(120) NOT NULL,
    schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    correlation_id uuid,
    causation_id uuid,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PUBLISHED','FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
CREATE INDEX idx_catalog_outbox_pending ON catalog.outbox_event(status, next_attempt_at, occurred_at) WHERE status='PENDING';

-- TODO: Pegar aquí los INSERT iniciales del esquema 'catalog'.

-- Proveedor demo para recepción externa (RF-35 / CUS-31)
INSERT INTO catalog.supplier (id, code, tax_id, business_name, trade_name, contact_name, phone, email, address) VALUES
('70000000-0000-0000-0000-000000000001','PRV-001','20123456789','Suministros Industriales Andinos S.A.C.','SIA','María Quispe','+51 999 111 222','ventas@sia.example','Lima, Perú')
ON CONFLICT DO NOTHING;

INSERT INTO catalog.category(id,code,name,category_type) VALUES
('20000000-0000-0000-0000-000000000001','MAT-ACERO','Aceros y aleaciones','MATERIAL'),
('20000000-0000-0000-0000-000000000002','REP-ROD','Rodamientos y retenes','REPUESTO'),
('20000000-0000-0000-0000-000000000003','INS-LUB','Lubricantes industriales','INSUMO'),
('20000000-0000-0000-0000-000000000004','MAQ-TIERRA','Movimiento de tierras','MAQUINARIA');

INSERT INTO catalog.unit_measure(id,code,name,symbol,dimension) VALUES
('21000000-0000-0000-0000-000000000001','KG','Kilogramo','kg','MASS'),
('21000000-0000-0000-0000-000000000002','T','Tonelada','t','MASS'),
('21000000-0000-0000-0000-000000000003','PZA','Pieza','pza','COUNT'),
('21000000-0000-0000-0000-000000000004','CAJA','Caja','caja','LOGISTIC'),
('21000000-0000-0000-0000-000000000005','L','Litro','L','VOLUME');

INSERT INTO catalog.product(id,sku,name,category_id,product_type,storage_unit_id,base_unit_id,min_stock,
 requires_lot,requires_heat_number,requires_expiry,requires_serial,technical_attributes) VALUES
('22000000-0000-0000-0000-000000000001','A36-PL-10','Plancha acero A36 10 mm','20000000-0000-0000-0000-000000000001','MATERIAL',
 '21000000-0000-0000-0000-000000000002','21000000-0000-0000-0000-000000000001',500,true,true,false,false,'{"grade":"A36","thickness_mm":10}'),
('22000000-0000-0000-0000-000000000002','ROD-6205','Rodamiento 6205','20000000-0000-0000-0000-000000000002','REPUESTO',
 '21000000-0000-0000-0000-000000000004','21000000-0000-0000-0000-000000000003',20,true,false,false,false,'{"designation":"6205"}'),
('22000000-0000-0000-0000-000000000003','LUB-H46','Aceite hidráulico ISO VG 46','20000000-0000-0000-0000-000000000003','INSUMO',
 '21000000-0000-0000-0000-000000000005','21000000-0000-0000-0000-000000000005',100,true,false,true,false,'{"viscosity":"ISO VG 46"}'),
('22000000-0000-0000-0000-000000000004','EXC-320','Excavadora 320 demo','20000000-0000-0000-0000-000000000004','MAQUINARIA',
 '21000000-0000-0000-0000-000000000003','21000000-0000-0000-0000-000000000003',0,false,false,false,true,'{"class":"excavator"}');

INSERT INTO catalog.unit_conversion(id,product_id,from_unit_id,to_unit_id,factor) VALUES
('23000000-0000-0000-0000-000000000001','22000000-0000-0000-0000-000000000001','21000000-0000-0000-0000-000000000002','21000000-0000-0000-0000-000000000001',1000),
('23000000-0000-0000-0000-000000000002','22000000-0000-0000-0000-000000000002','21000000-0000-0000-0000-000000000004','21000000-0000-0000-0000-000000000003',10);

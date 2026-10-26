-- Datos semilla: tipos de mercancía y catálogo de productos (idempotente con MERGE).
-- Los recursos van en el orden natural de adquisición de cada tipo. FRAGIL/PESADO/VOLUMINOSO/
-- ELECTRONICO/DOCUMENTOS/PERECEDERO usan combinaciones distintas. PESADO y VOLUMINOSO comparten
-- MONTACARGAS y AREA_CARGA en sentido inverso (base del escenario de deadlock); ELECTRONICO y
-- DOCUMENTOS comparten ESCANER, que solo tiene capacidad 1.

MERGE INTO merchandise_type (code, name, description, resources) KEY (code) VALUES
    ('FRAGIL', 'Frágil', 'Vidrio, cerámica y objetos que requieren manejo cuidadoso y revisión', 'ENCARGADO,EMPAQUE,CONTROL_CALIDAD'),
    ('PESADO', 'Pesado', 'Maquinaria y artículos de alto peso que requieren montacargas', 'ENCARGADO,MONTACARGAS,AREA_CARGA'),
    ('VOLUMINOSO', 'Voluminoso', 'Muebles y artículos de gran tamaño que ocupan área de carga', 'EMPAQUE,AREA_CARGA,MONTACARGAS'),
    ('ELECTRONICO', 'Electrónico', 'Equipos de alto valor que se escanean y se verifican antes de empacar', 'ESCANER,CONTROL_CALIDAD,EMPAQUE'),
    ('DOCUMENTOS', 'Documentos', 'Paquetes pequeños y documentos de manejo ligero', 'ENCARGADO,ESCANER'),
    ('PERECEDERO', 'Perecedero', 'Alimentos y medicinas con inspección sanitaria y despacho inmediato', 'CONTROL_CALIDAD,ENCARGADO,AREA_CARGA');

MERGE INTO product (sku, name, merchandise_type) KEY (sku) VALUES
    ('FRA-001', 'Juego de copas de cristal', 'FRAGIL'),
    ('FRA-002', 'Espejo decorativo', 'FRAGIL'),
    ('FRA-003', 'Vajilla de cerámica', 'FRAGIL'),
    ('PES-001', 'Motor industrial', 'PESADO'),
    ('PES-002', 'Caja de herramientas pesadas', 'PESADO'),
    ('PES-003', 'Banco de pesas', 'PESADO'),
    ('VOL-001', 'Sofá modular', 'VOLUMINOSO'),
    ('VOL-002', 'Refrigeradora', 'VOLUMINOSO'),
    ('VOL-003', 'Escritorio de oficina', 'VOLUMINOSO'),
    ('ELE-001', 'Laptop', 'ELECTRONICO'),
    ('ELE-002', 'Teléfono móvil', 'ELECTRONICO'),
    ('ELE-003', 'Monitor 27 pulgadas', 'ELECTRONICO'),
    ('DOC-001', 'Libro de texto', 'DOCUMENTOS'),
    ('DOC-002', 'Sobre de documentos', 'DOCUMENTOS'),
    ('PER-001', 'Caja de medicinas', 'PERECEDERO'),
    ('PER-002', 'Canasta de frutas', 'PERECEDERO');

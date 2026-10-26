package com.redxela.domain;

/**
 * Producto del catálogo predefinido.
 *
 * @param id              identificador en base de datos
 * @param sku             código del producto
 * @param name            nombre
 * @param merchandiseType tipo de mercancía (determina los recursos del pedido)
 */
public record Product(long id, String sku, String name, MerchandiseType merchandiseType) {
}

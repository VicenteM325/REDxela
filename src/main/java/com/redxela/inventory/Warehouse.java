package com.redxela.inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * Almacén físico: una cuadrícula de pasillos × niveles × posiciones. Cada ubicación guarda
 * unidades de un solo producto y tiene una capacidad fija.
 *
 * <p>Un lote puede ocupar varias ubicaciones que <strong>no son contiguas</strong>: primero se
 * completan las ubicaciones parcialmente llenas del mismo producto, esté donde esté, y luego se usan
 * las vacías en orden (primer ajuste). Por eso con el tiempo un mismo producto queda repartido en
 * distintos pasillos y niveles, como describe el enunciado.
 *
 * <p>Los métodos son sincronizados: la estructura la modifica solo el área de Inventario, pero la
 * interfaz la consulta desde otros hilos.
 */
public class Warehouse {

    /** Posición física (índices desde 0; {@link #label()} los muestra desde 1). */
    public record Location(int aisle, int level, int position) {
        public String label() {
            return "P" + (aisle + 1) + "-N" + (level + 1) + "-U" + (position + 1);
        }
    }

    /** Cantidad de unidades de un producto guardadas (o retiradas) en una ubicación. */
    public record Allocation(Location location, int quantity) {
    }

    /** Resultado de guardar un lote: dónde quedó y cuántas unidades no cupieron. */
    public record StoreResult(List<Allocation> stored, int rejected) {
    }

    /** Contenido de una ubicación ocupada. */
    public record SlotView(Location location, long productId, int quantity) {
    }

    private static final long EMPTY = -1;

    private final int aisles;
    private final int levels;
    private final int positions;
    private final int slotCapacity;
    private final long[] slotProduct;
    private final int[] slotQuantity;

    public Warehouse(int aisles, int levels, int positions, int slotCapacity) {
        if (aisles < 1 || levels < 1 || positions < 1 || slotCapacity < 1) {
            throw new IllegalArgumentException("Las dimensiones del almacén deben ser positivas");
        }
        this.aisles = aisles;
        this.levels = levels;
        this.positions = positions;
        this.slotCapacity = slotCapacity;
        int n = aisles * levels * positions;
        this.slotProduct = new long[n];
        this.slotQuantity = new int[n];
        java.util.Arrays.fill(slotProduct, EMPTY);
    }

    /**
     * Guarda un lote. No falla si falta espacio: guarda lo que cabe e informa cuántas unidades
     * quedaron fuera para que el llamador decida (Inventario lo registra como rechazo).
     */
    public synchronized StoreResult store(long productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser positiva");
        }
        List<Allocation> stored = new ArrayList<>();
        int remaining = quantity;
        // 1) Completar ubicaciones del mismo producto que aún tienen espacio
        for (int i = 0; i < slotProduct.length && remaining > 0; i++) {
            if (slotProduct[i] == productId && slotQuantity[i] < slotCapacity) {
                remaining -= put(i, productId, remaining, stored);
            }
        }
        // 2) Usar ubicaciones vacías en orden de pasillo, nivel, posición
        for (int i = 0; i < slotProduct.length && remaining > 0; i++) {
            if (slotProduct[i] == EMPTY) {
                remaining -= put(i, productId, remaining, stored);
            }
        }
        return new StoreResult(stored, remaining);
    }

    /**
     * Retira unidades de un producto, vaciando primero las ubicaciones con menos stock para
     * liberar ubicaciones y reducir la fragmentación.
     *
     * @throws IllegalStateException si no hay suficientes unidades (no se retira nada)
     */
    public synchronized List<Allocation> remove(long productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser positiva");
        }
        if (unitsOf(productId) < quantity) {
            throw new IllegalStateException("No hay " + quantity + " unidades del producto " + productId);
        }
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < slotProduct.length; i++) {
            if (slotProduct[i] == productId) {
                candidates.add(i);
            }
        }
        candidates.sort(java.util.Comparator.<Integer>comparingInt(i -> slotQuantity[i]).thenComparingInt(i -> i));
        List<Allocation> removed = new ArrayList<>();
        int remaining = quantity;
        for (int i : candidates) {
            if (remaining == 0) {
                break;
            }
            int take = Math.min(remaining, slotQuantity[i]);
            slotQuantity[i] -= take;
            remaining -= take;
            removed.add(new Allocation(locationOf(i), take));
            if (slotQuantity[i] == 0) {
                slotProduct[i] = EMPTY;
            }
        }
        return removed;
    }

    /** Unidades totales de un producto en todo el almacén. */
    public synchronized int unitsOf(long productId) {
        int total = 0;
        for (int i = 0; i < slotProduct.length; i++) {
            if (slotProduct[i] == productId) {
                total += slotQuantity[i];
            }
        }
        return total;
    }

    /** Ubicaciones que contienen el producto, en orden físico. */
    public synchronized List<Allocation> locationsOf(long productId) {
        List<Allocation> out = new ArrayList<>();
        for (int i = 0; i < slotProduct.length; i++) {
            if (slotProduct[i] == productId) {
                out.add(new Allocation(locationOf(i), slotQuantity[i]));
            }
        }
        return out;
    }

    /** Todas las ubicaciones ocupadas. */
    public synchronized List<SlotView> occupiedSlots() {
        List<SlotView> out = new ArrayList<>();
        for (int i = 0; i < slotProduct.length; i++) {
            if (slotProduct[i] != EMPTY) {
                out.add(new SlotView(locationOf(i), slotProduct[i], slotQuantity[i]));
            }
        }
        return out;
    }

    /** Unidades guardadas en total. */
    public synchronized int usedUnits() {
        int used = 0;
        for (int q : slotQuantity) {
            used += q;
        }
        return used;
    }

    public int capacityUnits() {
        return slotProduct.length * slotCapacity;
    }

    public int aisles() { return aisles; }
    public int levels() { return levels; }
    public int positions() { return positions; }
    public int slotCapacity() { return slotCapacity; }

    private int put(int index, long productId, int quantity, List<Allocation> stored) {
        int space = slotCapacity - slotQuantity[index];
        int amount = Math.min(space, quantity);
        slotProduct[index] = productId;
        slotQuantity[index] += amount;
        stored.add(new Allocation(locationOf(index), amount));
        return amount;
    }

    private Location locationOf(int index) {
        int position = index % positions;
        int level = (index / positions) % levels;
        int aisle = index / (positions * levels);
        return new Location(aisle, level, position);
    }
}

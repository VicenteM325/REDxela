package com.redxela.api;

import com.redxela.domain.Product;
import com.redxela.inventory.InventoryArea;
import com.redxela.inventory.Warehouse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Existencias por producto y mapa físico del almacén. */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryArea inventory;

    public InventoryController(InventoryArea inventory) {
        this.inventory = inventory;
    }

    @GetMapping
    public InventoryDto inventory() {
        List<InventoryArea.ProductStock> stock = inventory.stock();
        Map<Long, Product> byId = stock.stream().map(InventoryArea.ProductStock::product)
                .collect(Collectors.toMap(Product::id, Function.identity()));
        Warehouse w = inventory.warehouse();
        List<SlotDto> slots = w.occupiedSlots().stream().map(s -> {
            Product p = byId.get(s.productId());
            return new SlotDto(s.location().aisle() + 1, s.location().level() + 1, s.location().position() + 1,
                    s.location().label(), s.productId(), p == null ? "?" : p.sku(), s.quantity());
        }).toList();
        List<ProductStockDto> products = stock.stream().map(s -> new ProductStockDto(
                s.product().id(), s.product().sku(), s.product().name(), s.product().merchandiseType().code(),
                s.onHand(), s.reserved(), s.available(),
                s.locations().stream().map(a -> new LocationDto(a.location().label(), a.quantity())).toList())).toList();
        return new InventoryDto(
                new WarehouseDto(w.aisles(), w.levels(), w.positions(), w.slotCapacity(), w.capacityUnits(),
                        w.usedUnits(), slots),
                products, inventory.queueSize(), inventory.processedMessages(), inventory.storedUnits(),
                inventory.rejectedUnits());
    }

    public record InventoryDto(WarehouseDto warehouse, List<ProductStockDto> products, int pendingMessages,
                               long processedMessages, long storedUnits, long rejectedUnits) {
    }

    public record WarehouseDto(int aisles, int levels, int positions, int slotCapacity, int capacityUnits,
                               int usedUnits, List<SlotDto> occupiedSlots) {
    }

    public record SlotDto(int aisle, int level, int position, String label, long productId, String sku, int quantity) {
    }

    public record ProductStockDto(long id, String sku, String name, String merchandiseType, int onHand,
                                  int reserved, int available, List<LocationDto> locations) {
    }

    public record LocationDto(String location, int quantity) {
    }
}

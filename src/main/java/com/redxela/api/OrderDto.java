package com.redxela.api;

import com.redxela.domain.Order;
import com.redxela.domain.OrderItem;
import com.redxela.domain.ResourceType;

import java.util.List;

/**
 * Vista de un pedido para la interfaz. Los tiempos están en minutos simulados.
 *
 * @param waitedMinutes tiempo que lleva esperando (o que esperó antes de iniciar)
 * @param waitingFor    recurso que bloquea al pedido en este instante, si alguno
 */
public record OrderDto(long id, String customer, LevelDto serviceLevel, List<ItemDto> items, int totalUnits,
                       String status, double createdAt, double waitedMinutes, double workMinutes,
                       double progress, double remainingMinutes, String waitingFor,
                       List<String> requiredResources, List<String> merchandiseTypes, int requeues) {

    public record LevelDto(int level, String name, int maxWaitMinutes) {
    }

    public record ItemDto(long productId, String sku, String name, String merchandiseType, int quantity) {
    }

    public static OrderDto of(Order o, double now) {
        double started = o.firstStartedAt();
        double waited = (Double.isNaN(started) ? now : started) - o.createdAt();
        ResourceType waitingFor = o.waitingFor();
        return new OrderDto(
                o.id(),
                o.customer().name(),
                new LevelDto(o.serviceLevel().level(), o.serviceLevel().displayName(), o.serviceLevel().maxWaitMinutes()),
                o.items().stream().map(OrderDto::item).toList(),
                o.totalUnits(),
                o.status().name(),
                o.createdAt(),
                waited,
                o.workMinutes(),
                o.progress(),
                o.remainingMinutes(),
                waitingFor == null ? null : waitingFor.name(),
                o.requiredResources().stream().map(Enum::name).toList(),
                List.copyOf(o.merchandiseTypeCodes()),
                o.requeues());
    }

    private static ItemDto item(OrderItem i) {
        return new ItemDto(i.product().id(), i.product().sku(), i.product().name(),
                i.product().merchandiseType().code(), i.quantity());
    }
}

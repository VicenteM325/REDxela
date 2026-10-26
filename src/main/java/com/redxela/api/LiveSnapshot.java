package com.redxela.api;

import com.redxela.simulation.EventLog;
import com.redxela.stats.StatsCollector;

import java.util.List;

/**
 * Todo lo que la interfaz necesita para pintarse, en un solo mensaje: se emite por WebSocket cada
 * medio segundo, de modo que la UI refleja el sistema en tiempo real sin hacer polling.
 */
public record LiveSnapshot(StateDto state, StatsCollector.Snapshot stats, DeadlockController.DeadlockStatus deadlock,
                           ReceptionController.ReceptionStatus reception, InventoryController.InventoryDto inventory,
                           List<EventLog.Event> events) {
}

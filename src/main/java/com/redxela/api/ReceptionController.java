package com.redxela.api;

import com.redxela.reception.AutoRestocker;
import com.redxela.reception.ReceptionArea;
import com.redxela.reception.ReceptionMessage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Registro manual de mercadería que llega y control de camiones y reposición automática. */
@RestController
@RequestMapping("/api/reception")
public class ReceptionController {

    private final ReceptionArea reception;
    private final AutoRestocker restocker;

    public ReceptionController(ReceptionArea reception, AutoRestocker restocker) {
        this.reception = reception;
        this.restocker = restocker;
    }

    /** Registra un lote: solo se elige un producto del catálogo y la cantidad recibida. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReceptionMessage receive(@Valid @RequestBody LotRequest req) throws InterruptedException {
        return reception.receive(req.productId(), req.quantity());
    }

    /** Camión con varios lotes seguidos (prueba la contrapresión hacia Inventario). */
    @PostMapping("/truck")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReceptionStatus truck(@Valid @RequestBody TruckRequest req) {
        if (!reception.startBurst(req.lots(), req.unitsPerLot())) {
            throw new ConflictException("Ya hay un camión descargando");
        }
        return status();
    }

    @GetMapping
    public ReceptionStatus status() {
        return new ReceptionStatus(reception.queueSize(), reception.queueCapacity(), reception.sentLots(),
                reception.burstRemaining(), restocker.isRunning());
    }

    @PutMapping("/auto-restock")
    public ReceptionStatus autoRestock(@RequestBody AutoRestockRequest req) {
        if (req.enabled()) {
            restocker.start();
        } else {
            restocker.stop();
        }
        return status();
    }

    public record LotRequest(@Min(1) long productId, @Min(1) @Max(10_000) int quantity) {
    }

    public record TruckRequest(@Min(1) @Max(500) int lots, @Min(1) @Max(10_000) int unitsPerLot) {
    }

    public record AutoRestockRequest(boolean enabled) {
    }

    /**
     * @param queueSize         avisos esperando a ser procesados por Inventario
     * @param truckLotsPending  lotes del camión actual que aún no se envían
     */
    public record ReceptionStatus(int queueSize, int queueCapacity, long sentLots, int truckLotsPending,
                                  boolean autoRestock) {
    }
}

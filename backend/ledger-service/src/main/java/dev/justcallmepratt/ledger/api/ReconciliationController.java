package dev.justcallmepratt.ledger.api;

import dev.justcallmepratt.ledger.app.ReconciliationReport;
import dev.justcallmepratt.ledger.app.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reconciliation")
@Tag(name = "Reconciliation")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Recompute counters from transfers and check every ledger balances")
    public ReconciliationReport reconcile() {
        return service.run();
    }
}

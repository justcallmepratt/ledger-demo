package dev.justcallmepratt.ledger.api;

import dev.justcallmepratt.ledger.api.dto.LedgerResponse;
import dev.justcallmepratt.ledger.api.dto.Views;
import dev.justcallmepratt.ledger.app.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ledgers")
@Tag(name = "Ledgers")
public class LedgerController {

    private final AccountService accounts;

    public LedgerController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    @Operation(summary = "List ledgers", description = "A ledger is one asset; its id is the ISO 4217 numeric code.")
    public List<LedgerResponse> list() {
        return accounts.ledgers().stream().map(Views::ledger).toList();
    }
}

package dev.justcallmepratt.ledger.api;

import dev.justcallmepratt.ledger.api.dto.AccountResponse;
import dev.justcallmepratt.ledger.api.dto.BalanceEntryResponse;
import dev.justcallmepratt.ledger.api.dto.CreateAccountRequest;
import dev.justcallmepratt.ledger.api.dto.PageResponse;
import dev.justcallmepratt.ledger.api.dto.TransferResponse;
import dev.justcallmepratt.ledger.api.dto.Views;
import dev.justcallmepratt.ledger.app.AccountService;
import dev.justcallmepratt.ledger.app.Cursors;
import dev.justcallmepratt.ledger.app.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create an account (idempotent)",
            description = "201 when created, 200 for an identical replay, 409 exists_with_different_fields otherwise.")
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountService.CreateResult result = service.create(request.toDomain());
        AccountResponse body = Views.account(result.account());
        if (result.created()) {
            return ResponseEntity.created(URI.create("/api/v1/accounts/" + body.id())).body(body);
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping
    @Operation(summary = "List accounts, newest first (keyset pagination)")
    public PageResponse<AccountResponse> list(
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        Page<dev.justcallmepratt.ledger.domain.Account> page = service.list(limit, cursor);
        return new PageResponse<>(page.items().stream().map(Views::account).toList(), page.nextCursor());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an account")
    public AccountResponse get(@PathVariable("id") UUID id) {
        return Views.account(service.get(id));
    }

    @GetMapping("/{id}/transfers")
    @Operation(summary = "Transfers touching the account, newest first")
    public PageResponse<TransferResponse> transfers(
            @PathVariable("id") UUID id,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        Page<dev.justcallmepratt.ledger.domain.Transfer> page = service.transfers(id, limit, cursor);
        return new PageResponse<>(page.items().stream().map(Views::transfer).toList(), page.nextCursor());
    }

    @GetMapping("/{id}/balances")
    @Operation(summary = "Balance history (accounts with the history flag only)")
    public PageResponse<BalanceEntryResponse> balances(
            @PathVariable("id") UUID id,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        Page<dev.justcallmepratt.ledger.persistence.BalanceHistoryEntry> page = service.balances(id, limit, cursor);
        return new PageResponse<>(page.items().stream().map(Views::balanceEntry).toList(), page.nextCursor());
    }
}

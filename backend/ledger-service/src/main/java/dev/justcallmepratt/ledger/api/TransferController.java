package dev.justcallmepratt.ledger.api;

import dev.justcallmepratt.ledger.api.dto.BatchItemResponse;
import dev.justcallmepratt.ledger.api.dto.BatchRequest;
import dev.justcallmepratt.ledger.api.dto.BatchResponse;
import dev.justcallmepratt.ledger.api.dto.PageResponse;
import dev.justcallmepratt.ledger.api.dto.TransferRequest;
import dev.justcallmepratt.ledger.api.dto.TransferResponse;
import dev.justcallmepratt.ledger.api.dto.Views;
import dev.justcallmepratt.ledger.app.ApiException;
import dev.justcallmepratt.ledger.app.FieldIssue;
import dev.justcallmepratt.ledger.app.Page;
import dev.justcallmepratt.ledger.app.TransferService;
import dev.justcallmepratt.ledger.domain.PendingStatus;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers")
public class TransferController {

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Submit one transfer (idempotent by id)",
            description = "201 created, 200 identical replay; rejections are problem+json with the result code.")
    public ResponseEntity<TransferResponse> create(@Valid @RequestBody TransferRequest request) {
        TransferService.ItemResult result = service.submit(request.toDomain());
        if (result.code() == TransferResult.CREATED) {
            TransferResponse body = Views.transfer(result.transfer());
            return ResponseEntity.created(URI.create("/api/v1/transfers/" + body.id())).body(body);
        }
        if (result.code() == TransferResult.EXISTS) {
            return ResponseEntity.ok(Views.transfer(result.transfer()));
        }
        throw ApiException.fromResult(result.code(), result.detail());
    }

    @PostMapping("/batch")
    @Operation(summary = "Submit 1-100 transfers; linked runs are atomic chains",
            description = "Always 200 with one result per transfer.")
    public BatchResponse batch(@Valid @RequestBody BatchRequest request) {
        List<Transfer> transfers = new ArrayList<>(request.transfers().size());
        for (int i = 0; i < request.transfers().size(); i++) {
            try {
                transfers.add(request.transfers().get(i).toDomain());
            } catch (ApiException e) {
                String prefix = "transfers[" + i + "].";
                List<FieldIssue> errors = e.errors().stream()
                        .map(f -> new FieldIssue(prefix + f.field(), f.message())).toList();
                throw new ApiException(e.status(), e.code(), e.getMessage(), errors);
            }
        }
        List<BatchItemResponse> results = service.submitBatch(transfers).stream()
                .map(r -> new BatchItemResponse(r.index(), r.id(), r.code().code(),
                        r.transfer() == null ? null : Views.transfer(r.transfer()), r.detail()))
                .toList();
        return new BatchResponse(results);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a transfer")
    public TransferResponse get(@PathVariable("id") UUID id) {
        return Views.transfer(service.get(id));
    }

    @GetMapping
    @Operation(summary = "List transfers newest first, optionally only with a pending status")
    public PageResponse<TransferResponse> list(
            @RequestParam(name = "pendingStatus", required = false) PendingStatus pendingStatus,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        Page<Transfer> page = service.list(pendingStatus, limit, cursor);
        return new PageResponse<>(page.items().stream().map(Views::transfer).toList(), page.nextCursor());
    }
}

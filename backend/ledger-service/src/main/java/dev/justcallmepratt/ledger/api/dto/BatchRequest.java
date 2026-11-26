package dev.justcallmepratt.ledger.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BatchRequest(@NotNull @Size(min = 1, max = 100) @Valid List<TransferRequest> transfers) {
}

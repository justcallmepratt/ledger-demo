package dev.justcallmepratt.ledger.api.dto;

public record TransferFlagsRequest(Boolean linked, Boolean pending, Boolean postPendingTransfer,
                                   Boolean voidPendingTransfer) {
}

package dev.justcallmepratt.ledger.api.dto;

public record TransferFlagsView(boolean linked, boolean pending, boolean postPendingTransfer,
                                boolean voidPendingTransfer) {
}

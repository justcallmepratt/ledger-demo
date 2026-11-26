package dev.justcallmepratt.ledger.api.dto;

import java.util.UUID;

/** Result of one transfer of a batch. {@code detail} explains conflicts and is otherwise null. */
public record BatchItemResponse(int index, UUID id, String code, TransferResponse transfer, String detail) {
}

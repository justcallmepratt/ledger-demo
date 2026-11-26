package dev.justcallmepratt.ledger.api.dto;

import java.util.List;

public record BatchResponse(List<BatchItemResponse> results) {
}

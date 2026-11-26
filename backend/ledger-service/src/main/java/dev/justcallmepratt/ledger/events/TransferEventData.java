package dev.justcallmepratt.ledger.events;

import dev.justcallmepratt.ledger.api.dto.AccountBalanceView;
import dev.justcallmepratt.ledger.api.dto.TransferResponse;

/** Payload of transfer events: the transfer and both accounts' counters after it. */
public record TransferEventData(TransferResponse transfer, AccountBalanceView debitAccount,
                                AccountBalanceView creditAccount) {
}

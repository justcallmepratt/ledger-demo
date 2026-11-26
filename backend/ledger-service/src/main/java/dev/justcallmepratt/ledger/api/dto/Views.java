package dev.justcallmepratt.ledger.api.dto;

import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.Ledger;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.persistence.ActivityEntry;
import dev.justcallmepratt.ledger.persistence.BalanceHistoryEntry;

/** Domain to wire-format mapping. */
public final class Views {

    private Views() {
    }

    public static AccountResponse account(Account a) {
        return new AccountResponse(a.id(), a.ledger(), a.code(), a.name(),
                new AccountFlagsView(a.flags().debitsMustNotExceedCredits(), a.flags().creditsMustNotExceedDebits(),
                        a.flags().history(), a.flags().closed()),
                Long.toString(a.debitsPending()), Long.toString(a.debitsPosted()),
                Long.toString(a.creditsPending()), Long.toString(a.creditsPosted()), a.createdAt());
    }

    public static AccountBalanceView balance(Account a) {
        return new AccountBalanceView(a.id(), Long.toString(a.debitsPending()), Long.toString(a.debitsPosted()),
                Long.toString(a.creditsPending()), Long.toString(a.creditsPosted()));
    }

    public static TransferResponse transfer(Transfer t) {
        return new TransferResponse(t.id(), t.debitAccountId(), t.creditAccountId(), Long.toString(t.amount()),
                t.ledger(), t.code(),
                new TransferFlagsView(t.flags().linked(), t.flags().pending(), t.flags().postPendingTransfer(),
                        t.flags().voidPendingTransfer()),
                t.pendingId(), t.timeoutSeconds(), t.reference(), Long.toString(t.seq()), t.timestamp(),
                t.pendingStatus() == null ? null : t.pendingStatus().name(), t.expiresAt());
    }

    public static BalanceEntryResponse balanceEntry(BalanceHistoryEntry e) {
        return new BalanceEntryResponse(e.transferId(), Long.toString(e.seq()), e.timestamp(),
                Long.toString(e.debitsPending()), Long.toString(e.debitsPosted()),
                Long.toString(e.creditsPending()), Long.toString(e.creditsPosted()));
    }

    public static LedgerResponse ledger(Ledger l) {
        return new LedgerResponse(l.id(), l.currency(), l.assetScale());
    }

    public static ActivityItemResponse activity(ActivityEntry e) {
        return new ActivityItemResponse(e.eventId(), e.type(), e.seq() == null ? null : Long.toString(e.seq()),
                e.transferId(), e.accountId(), e.amount() == null ? null : Long.toString(e.amount()), e.ledger(),
                e.occurredAt(), e.summary());
    }
}

package dev.justcallmepratt.ledger.domain;

import java.util.Locale;

/**
 * Result codes of a transfer, exactly the codes in docs/API.md. Business outcomes are
 * values, never exceptions. {@link Category} says how an API layer should classify a code.
 */
public enum TransferResult {
    CREATED(Category.SUCCESS),
    EXISTS(Category.SUCCESS),
    EXISTS_WITH_DIFFERENT_FIELDS(Category.CONFLICT),

    ID_MUST_NOT_BE_NIL(Category.INVALID),
    FLAGS_ARE_MUTUALLY_EXCLUSIVE(Category.INVALID),
    AMOUNT_MUST_BE_POSITIVE(Category.INVALID),
    ACCOUNTS_MUST_BE_DIFFERENT(Category.INVALID),
    PENDING_ID_REQUIRED(Category.INVALID),
    PENDING_ID_MUST_BE_NULL(Category.INVALID),
    TIMEOUT_RESERVED_FOR_PENDING(Category.INVALID),
    LINKED_NOT_ALLOWED(Category.INVALID),

    DEBIT_ACCOUNT_NOT_FOUND(Category.NOT_FOUND),
    CREDIT_ACCOUNT_NOT_FOUND(Category.NOT_FOUND),
    PENDING_TRANSFER_NOT_FOUND(Category.NOT_FOUND),

    LEDGER_MUST_MATCH(Category.UNPROCESSABLE),
    TRANSFER_MUST_HAVE_SAME_LEDGER_AS_ACCOUNTS(Category.UNPROCESSABLE),
    PENDING_TRANSFER_NOT_PENDING(Category.UNPROCESSABLE),
    PENDING_TRANSFER_ALREADY_POSTED(Category.UNPROCESSABLE),
    PENDING_TRANSFER_ALREADY_VOIDED(Category.UNPROCESSABLE),
    PENDING_TRANSFER_EXPIRED(Category.UNPROCESSABLE),
    PENDING_TRANSFER_HAS_DIFFERENT_DEBIT_ACCOUNT_ID(Category.UNPROCESSABLE),
    PENDING_TRANSFER_HAS_DIFFERENT_CREDIT_ACCOUNT_ID(Category.UNPROCESSABLE),
    PENDING_TRANSFER_HAS_DIFFERENT_LEDGER(Category.UNPROCESSABLE),
    PENDING_TRANSFER_HAS_DIFFERENT_CODE(Category.UNPROCESSABLE),
    PENDING_TRANSFER_HAS_DIFFERENT_AMOUNT(Category.UNPROCESSABLE),
    EXCEEDS_PENDING_TRANSFER_AMOUNT(Category.UNPROCESSABLE),
    DEBIT_ACCOUNT_CLOSED(Category.UNPROCESSABLE),
    CREDIT_ACCOUNT_CLOSED(Category.UNPROCESSABLE),
    EXCEEDS_CREDITS(Category.UNPROCESSABLE),
    EXCEEDS_DEBITS(Category.UNPROCESSABLE),
    OVERFLOWS_BALANCE(Category.UNPROCESSABLE),

    LINKED_EVENT_FAILED(Category.BATCH_ONLY),
    LINKED_EVENT_CHAIN_OPEN(Category.BATCH_ONLY);

    /** Transport-neutral classification; the API layer maps it to an HTTP status. */
    public enum Category { SUCCESS, INVALID, NOT_FOUND, UNPROCESSABLE, CONFLICT, BATCH_ONLY }

    private final Category category;

    TransferResult(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }

    public boolean isSuccess() {
        return category == Category.SUCCESS;
    }

    /** The snake_case wire code, e.g. {@code exceeds_credits}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}

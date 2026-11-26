package dev.justcallmepratt.ledger.app;

import dev.justcallmepratt.ledger.domain.TransferResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionTest {

    @ParameterizedTest
    @CsvSource({
            "ID_MUST_NOT_BE_NIL,400",
            "FLAGS_ARE_MUTUALLY_EXCLUSIVE,400",
            "AMOUNT_MUST_BE_POSITIVE,400",
            "ACCOUNTS_MUST_BE_DIFFERENT,400",
            "PENDING_ID_REQUIRED,400",
            "PENDING_ID_MUST_BE_NULL,400",
            "TIMEOUT_RESERVED_FOR_PENDING,400",
            "LINKED_NOT_ALLOWED,400",
            "DEBIT_ACCOUNT_NOT_FOUND,404",
            "CREDIT_ACCOUNT_NOT_FOUND,404",
            "PENDING_TRANSFER_NOT_FOUND,404",
            "LEDGER_MUST_MATCH,422",
            "TRANSFER_MUST_HAVE_SAME_LEDGER_AS_ACCOUNTS,422",
            "PENDING_TRANSFER_NOT_PENDING,422",
            "PENDING_TRANSFER_ALREADY_POSTED,422",
            "PENDING_TRANSFER_ALREADY_VOIDED,422",
            "PENDING_TRANSFER_EXPIRED,422",
            "PENDING_TRANSFER_HAS_DIFFERENT_DEBIT_ACCOUNT_ID,422",
            "PENDING_TRANSFER_HAS_DIFFERENT_CREDIT_ACCOUNT_ID,422",
            "PENDING_TRANSFER_HAS_DIFFERENT_LEDGER,422",
            "PENDING_TRANSFER_HAS_DIFFERENT_CODE,422",
            "PENDING_TRANSFER_HAS_DIFFERENT_AMOUNT,422",
            "EXCEEDS_PENDING_TRANSFER_AMOUNT,422",
            "DEBIT_ACCOUNT_CLOSED,422",
            "CREDIT_ACCOUNT_CLOSED,422",
            "EXCEEDS_CREDITS,422",
            "EXCEEDS_DEBITS,422",
            "OVERFLOWS_BALANCE,422",
            "EXISTS_WITH_DIFFERENT_FIELDS,409"
    })
    void everyRejectionMapsToTheDocumentedStatus(TransferResult result, int status) {
        ApiException e = ApiException.fromResult(result, null);

        assertThat(e.status().value()).isEqualTo(status);
        assertThat(e.code()).isEqualTo(result.code());
        assertThat(e.getMessage()).contains(result.code());
    }

    @Test
    void detailOverridesTheDefaultMessage() {
        assertThat(ApiException.fromResult(TransferResult.EXISTS_WITH_DIFFERENT_FIELDS, "fields differ: amount")
                .getMessage()).isEqualTo("fields differ: amount");
    }

    @Test
    void batchOnlyCodesAreNotExpectedOnTheSingleEndpointButStayDefined() {
        assertThat(ApiException.fromResult(TransferResult.LINKED_EVENT_FAILED, null).status().value()).isEqualTo(422);
    }

    @Test
    void factories() {
        UUID id = UUID.randomUUID();
        assertThat(ApiException.accountNotFound(id).code()).isEqualTo("account_not_found");
        assertThat(ApiException.transferNotFound(id).status().value()).isEqualTo(404);
        assertThat(ApiException.historyNotEnabled(id).status().value()).isEqualTo(422);
        assertThat(ApiException.ledgerNotFound(1).code()).isEqualTo("ledger_not_found");
        assertThat(ApiException.invalid("bad").errors()).isEmpty();
        assertThat(ApiException.invalid("f", "m").errors()).containsExactly(new FieldIssue("f", "m"));
        assertThat(ApiException.accountDiffers("name").status().value()).isEqualTo(409);
    }
}

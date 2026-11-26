package dev.justcallmepratt.ledger.persistence;

import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImmutabilityIntegrationTest extends IntegrationTestBase {

    private UUID storedTransferId() {
        Account a = newAccount();
        Account b = newAccount();
        Transfer t = single(a, b, 10);
        transferService.submit(t);
        return t.id();
    }

    @Test
    void transfersCannotBeUpdated() {
        UUID id = storedTransferId();

        assertThatThrownBy(() -> jdbc.sql("UPDATE transfers SET amount = 1 WHERE id = :id").param("id", id).update())
                .hasMessageContaining("append-only");
        assertThat(jdbc.sql("SELECT amount FROM transfers WHERE id = :id").param("id", id).query(Long.class).single())
                .isEqualTo(10L);
    }

    @Test
    void transfersCannotBeDeletedOrTruncated() {
        UUID id = storedTransferId();

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM transfers WHERE id = :id").param("id", id).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE transfers CASCADE").update())
                .hasMessageContaining("append-only");
        assertThat(count("transfers")).isEqualTo(1);
    }

    @Test
    void balanceHistoryIsAppendOnlyToo() {
        Account tracked = newAccount(new dev.justcallmepratt.ledger.domain.AccountFlags(false, false, true, false));
        transferService.submit(single(newAccount(), tracked, 5));

        assertThatThrownBy(() -> jdbc.sql("UPDATE account_balance_history SET debits_posted = 9").update())
                .hasMessageContaining("append-only");
    }

    @Test
    void checkConstraintsRejectNegativeCountersAndSelfTransfers() {
        UUID id = storedTransferId();
        UUID account = jdbc.sql("SELECT debit_account_id FROM transfers WHERE id = :id").param("id", id)
                .query(UUID.class).single();

        assertThatThrownBy(() -> jdbc.sql("UPDATE accounts SET debits_posted = -1 WHERE id = :id")
                .param("id", account).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO transfers (id, debit_account_id, credit_account_id, amount, "
                        + "ledger, code, created_at) VALUES (:id, :a, :a, 1, 840, 1, now())")
                .param("id", UUID.randomUUID()).param("a", account).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO transfers (id, debit_account_id, credit_account_id, amount, "
                        + "ledger, code, post_pending, created_at) VALUES (:id, :a, :b, 1, 840, 1, TRUE, now())")
                .param("id", UUID.randomUUID()).param("a", account).param("b", UUID.randomUUID()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSeededLedgerExists() {
        assertThat(jdbc.sql("SELECT currency FROM ledgers WHERE id = 840").query(String.class).single().trim())
                .isEqualTo("USD");
        assertThat(jdbc.sql("SELECT asset_scale FROM ledgers WHERE id = 840").query(Integer.class).single())
                .isEqualTo(2);
    }
}

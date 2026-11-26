package dev.justcallmepratt.ledger.jobs;

import dev.justcallmepratt.ledger.app.ReconciliationReport;
import dev.justcallmepratt.ledger.app.ReconciliationService;
import dev.justcallmepratt.ledger.app.TransferService.ItemResult;
import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.support.IntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReconciliationIntegrationTest extends IntegrationTestBase {

    @Autowired ReconciliationService reconciliation;
    @Autowired ReconciliationJob reconciliationJob;
    @Autowired PendingExpiryJob expiryJob;
    @Autowired MeterRegistry meters;

    private double gauge() {
        return meters.get("ledger.reconciliation.mismatches").gauge().value();
    }

    @Test
    void anEmptyLedgerIsBalanced() {
        ReconciliationReport report = reconciliation.run();

        assertThat(report.balanced()).isTrue();
        assertThat(report.accountMismatches()).isEmpty();
        assertThat(report.ledgers()).anySatisfy(l -> {
            assertThat(l.ledger()).isEqualTo(840);
            assertThat(l.balanced()).isTrue();
            assertThat(l.debitsPosted()).isEqualTo("0");
        });
        assertThat(gauge()).isZero();
    }

    @Test
    void everyKindOfTransferKeepsTheBooksBalanced() {
        Account a = newAccount();
        Account b = newAccount();
        transferService.submit(single(a, b, 100));
        ItemResult toPost = transferService.submit(pending(a, b, 50, 0));
        ItemResult toVoid = transferService.submit(pending(b, a, 30, 0));
        ItemResult toExpire = transferService.submit(pending(a, b, 20, 10));
        ItemResult stays = transferService.submit(pending(b, a, 7, 0));
        transferService.submit(postPending(toPost.transfer().id(), 40));
        transferService.submit(voidOf(toVoid.transfer().id()));
        clock.advance(Duration.ofSeconds(11));
        expiryJob.expireDue();

        ReconciliationReport report = reconciliation.run();

        assertThat(report.accountMismatches()).isEmpty();
        assertThat(report.balanced()).isTrue();
        assertThat(report.ledgers().stream().filter(l -> l.ledger() == 840).findFirst().orElseThrow()
                .debitsPosted()).isEqualTo("140");
        assertThat(reload(a).debitsPending()).isEqualTo(0);
        assertThat(reload(b).debitsPending()).isEqualTo(7);
        assertThat(stays.transfer()).isNotNull();
        assertThat(toExpire.transfer()).isNotNull();
    }

    @Test
    void aHandCorruptedCounterIsDetected() throws Exception {
        Account a = newAccount();
        Account b = newAccount();
        transferService.submit(single(a, b, 100));
        assertThat(reconciliation.run().balanced()).isTrue();

        jdbc.sql("UPDATE accounts SET debits_posted = debits_posted + 5 WHERE id = :id").param("id", a.id()).update();

        ReconciliationReport report = reconciliation.run();

        assertThat(report.balanced()).isFalse();
        assertThat(report.accountMismatches()).hasSize(1);
        ReconciliationReport.AccountMismatch mismatch = report.accountMismatches().get(0);
        assertThat(mismatch.accountId()).isEqualTo(a.id());
        assertThat(mismatch.field()).isEqualTo("debitsPosted");
        assertThat(mismatch.stored()).isEqualTo("105");
        assertThat(mismatch.recomputed()).isEqualTo("100");
        assertThat(report.ledgers().stream().filter(l -> l.ledger() == 840).findFirst().orElseThrow().balanced())
                .isFalse();
        assertThat(gauge()).isEqualTo(2.0);   // one account mismatch + one unbalanced ledger

        mockMvc.perform(get("/api/v1/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(false))
                .andExpect(jsonPath("$.accountMismatches[0].field").value("debitsPosted"))
                .andExpect(jsonPath("$.accountMismatches[0].stored").value("105"))
                .andExpect(jsonPath("$.accountMismatches[0].recomputed").value("100"));

        reconciliationJob.tick();   // logs at ERROR, must not throw

        jdbc.sql("UPDATE accounts SET debits_posted = debits_posted - 5 WHERE id = :id").param("id", a.id()).update();
        assertThat(reconciliation.run().balanced()).isTrue();
        assertThat(gauge()).isZero();
    }

    @Test
    void aCorruptedPendingCounterIsDetectedToo() {
        Account a = newAccount();
        Account b = newAccount();
        transferService.submit(pending(a, b, 25, 0));

        jdbc.sql("UPDATE accounts SET credits_pending = 0 WHERE id = :id").param("id", b.id()).update();

        ReconciliationReport report = reconciliation.run();
        assertThat(report.balanced()).isFalse();
        assertThat(report.accountMismatches()).extracting(ReconciliationReport.AccountMismatch::field)
                .containsExactly("creditsPending");
    }
}

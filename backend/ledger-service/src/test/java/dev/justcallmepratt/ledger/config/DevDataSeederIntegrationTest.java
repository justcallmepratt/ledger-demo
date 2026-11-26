package dev.justcallmepratt.ledger.config;

import dev.justcallmepratt.ledger.app.ReconciliationService;
import dev.justcallmepratt.ledger.domain.PendingStatus;
import dev.justcallmepratt.ledger.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("dev")
class DevDataSeederIntegrationTest extends IntegrationTestBase {

    @Autowired DevDataSeeder seeder;
    @Autowired ReconciliationService reconciliation;

    @Test
    void seedingIsIdempotentAndLeavesBalancedBooks() {
        seeder.run(null);
        long accounts = count("accounts");
        long transfers = count("transfers");
        long outbox = count("outbox");

        seeder.run(null);

        assertThat(accounts).isEqualTo(5);
        assertThat(count("accounts")).isEqualTo(accounts);
        assertThat(count("transfers")).isEqualTo(transfers);
        assertThat(count("outbox")).isEqualTo(outbox);
        assertThat(reconciliation.run().balanced()).isTrue();
        assertThat(transferService.list(PendingStatus.PENDING, 50, null).items()).hasSize(1);
        assertThat(transferService.list(PendingStatus.POSTED, 50, null).items()).hasSize(1);
        assertThat(accountService.get(DevDataSeeder.id("alice")).debitsPosted()).isEqualTo(2_599 + 1_200);
        assertThat(accountService.get(DevDataSeeder.id("merchant")).creditsPosted()).isEqualTo(2_599 + 1_200 + 500);
    }
}

package dev.justcallmepratt.ledger.rules;

import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.AccountFlags;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferFlags;
import dev.justcallmepratt.ledger.domain.TransferResult;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Property tests over random transfer sequences on a small set of random accounts. */
class LedgerInvariantsPropertyTest {

    private static final Instant START = Instant.parse("2026-11-26T08:00:00Z");
    private static final int ACCOUNTS = 5;

    /** kind: 0 single, 1 pending, 2 post, 3 void, 4 replay an earlier request, 5 advance clock + expire. */
    record Step(int kind, int debit, int credit, long amount, int pick, int timeout, int ledger) {
    }

    @Provide
    Arbitrary<List<Step>> scripts() {
        Arbitrary<Step> step = Combinators.combine(
                Arbitraries.integers().between(0, 5),
                Arbitraries.integers().between(0, ACCOUNTS - 1),
                Arbitraries.integers().between(0, ACCOUNTS - 1),
                Arbitraries.longs().between(0, 1_000),
                Arbitraries.integers().between(0, 1_000),
                Arbitraries.integers().between(0, 120),
                Arbitraries.integers().between(0, 9))
                .as(Step::new);
        return step.list().ofMinSize(1).ofMaxSize(80);
    }

    @Provide
    Arbitrary<List<Integer>> accountFlagKinds() {
        // 0 none, 1 debit-limited, 2 credit-limited
        return Arbitraries.integers().between(0, 2).list().ofSize(ACCOUNTS);
    }

    private static InMemoryLedger newLedger(List<Integer> flagKinds) {
        InMemoryLedger ledger = new InMemoryLedger();
        for (int i = 0; i < ACCOUNTS; i++) {
            int kind = flagKinds.get(i);
            AccountFlags flags = kind == 1 ? Fixtures.debitLimited() : kind == 2 ? Fixtures.creditLimited()
                    : AccountFlags.NONE;
            // account 0 is on another ledger to exercise the ledger rules
            int ledgerId = i == 0 ? 978 : Fixtures.USD;
            ledger.add(Account.open(Fixtures.uuid(i + 1), ledgerId, 1, "a" + i, flags, START));
        }
        return ledger;
    }

    private static void assertInvariants(InMemoryLedger ledger) {
        for (int ledgerId : new int[]{978, Fixtures.USD}) {
            assertThat(ledger.sum(ledgerId, Account::debitsPosted))
                    .as("sum debitsPosted == sum creditsPosted on %d", ledgerId)
                    .isEqualTo(ledger.sum(ledgerId, Account::creditsPosted));
            assertThat(ledger.sum(ledgerId, Account::debitsPending))
                    .as("sum debitsPending == sum creditsPending on %d", ledgerId)
                    .isEqualTo(ledger.sum(ledgerId, Account::creditsPending));
        }
        for (Account a : ledger.accounts.values()) {
            if (a.flags().debitsMustNotExceedCredits()) {
                assertThat(a.debitsPending() + a.debitsPosted()).isLessThanOrEqualTo(a.creditsPosted());
            }
            if (a.flags().creditsMustNotExceedDebits()) {
                assertThat(a.creditsPending() + a.creditsPosted()).isLessThanOrEqualTo(a.debitsPosted());
            }
        }
    }

    /** Runs a script; returns the successfully submitted requests. */
    private static List<Transfer> run(InMemoryLedger ledger, List<Step> steps) {
        List<Transfer> submitted = new ArrayList<>();
        Instant now = START;
        long nextId = 100;
        for (Step s : steps) {
            Account d = ledger.accounts.get(Fixtures.uuid(s.debit() + 1));
            Account c = ledger.accounts.get(Fixtures.uuid(s.credit() + 1));
            // fund by single-phase from account index 1..: amounts are small, so limits get hit often
            Transfer request = null;
            switch (s.kind()) {
                case 0 -> request = Fixtures.single(nextId++, d, c, s.amount());
                case 1 -> request = Fixtures.pending(nextId++, d, c, s.amount(), s.timeout() % 40);
                case 2, 3 -> {
                    List<Transfer> open = ledger.pendingOpen();
                    UUID target = open.isEmpty() ? Fixtures.uuid(s.pick() + 5000)
                            : open.get(s.pick() % open.size()).id();
                    long idLow = nextId++;
                    request = s.kind() == 2
                            ? Transfer.request(Fixtures.uuid(idLow), null, null, s.amount() % 3 == 0 ? 0 : s.amount(),
                            0, 0, TransferFlags.POST, target, 0, null)
                            : Transfer.request(Fixtures.uuid(idLow), null, null, 0, 0, 0, TransferFlags.VOID,
                            target, 0, null);
                }
                case 4 -> {
                    if (!submitted.isEmpty()) {
                        Transfer again = submitted.get(s.pick() % submitted.size());
                        Transfer before = ledger.transfers.get(again.id());
                        long seqBefore = ledger.transfers.size();
                        assertThat(ledger.submit(again, now)).isEqualTo(TransferResult.EXISTS);
                        assertThat(ledger.transfers.size()).isEqualTo(seqBefore);
                        assertThat(ledger.transfers.get(again.id())).isEqualTo(before);
                    }
                }
                default -> {
                    now = now.plusSeconds(s.timeout() + 1L);
                    ledger.expireDue(now);
                }
            }
            if (request != null) {
                TransferResult result = ledger.submit(request, now);
                if (result == TransferResult.CREATED) {
                    submitted.add(request);
                }
            }
            assertInvariants(ledger);
        }
        return submitted;
    }

    @Property(tries = 300)
    void doubleEntryAndLimitsHoldForAnyRandomSequence(@ForAll("scripts") List<Step> steps,
                                                      @ForAll("accountFlagKinds") List<Integer> flagKinds) {
        InMemoryLedger ledger = newLedger(flagKinds);
        // seed credits so debit-limited accounts can move money
        for (int i = 1; i < ACCOUNTS; i++) {
            Account treasury = Account.open(Fixtures.uuid(100 + i), Fixtures.USD, 1, "t" + i, AccountFlags.NONE, START);
            ledger.add(treasury);
            ledger.submit(Fixtures.single(10 + i, treasury, ledger.accounts.get(Fixtures.uuid(i + 1)), 500), START);
        }
        run(ledger, steps);
        assertInvariants(ledger);
    }

    @Property(tries = 200)
    void replayingAnyAcceptedTransferIsANoOp(@ForAll("scripts") List<Step> steps,
                                             @ForAll("accountFlagKinds") List<Integer> flagKinds) {
        InMemoryLedger ledger = newLedger(flagKinds);
        List<Transfer> submitted = run(ledger, steps);
        List<Account> accountsBefore = new ArrayList<>(ledger.accounts.values());
        int transfersBefore = ledger.transfers.size();
        for (Transfer t : submitted) {
            assertThat(ledger.submit(t, START.plusSeconds(100_000))).isEqualTo(TransferResult.EXISTS);
        }
        assertThat(new ArrayList<>(ledger.accounts.values())).isEqualTo(accountsBefore);
        assertThat(ledger.transfers).hasSize(transfersBefore);
    }

    @Property(tries = 200)
    void replayWithDifferentAmountIsRejectedWithoutEffect(@ForAll("scripts") List<Step> steps,
                                                          @ForAll("accountFlagKinds") List<Integer> flagKinds) {
        InMemoryLedger ledger = newLedger(flagKinds);
        List<Transfer> submitted = run(ledger, steps);
        List<Account> accountsBefore = new ArrayList<>(ledger.accounts.values());
        for (Transfer t : submitted) {
            if (t.flags().resolves()) {
                continue;
            }
            Transfer changed = Transfer.request(t.id(), t.debitAccountId(), t.creditAccountId(), t.amount() + 1,
                    t.ledger(), t.code(), t.flags(), t.pendingId(), t.timeoutSeconds(), t.reference());
            assertThat(ledger.submit(changed, START)).isEqualTo(TransferResult.EXISTS_WITH_DIFFERENT_FIELDS);
        }
        assertThat(new ArrayList<>(ledger.accounts.values())).isEqualTo(accountsBefore);
    }
}

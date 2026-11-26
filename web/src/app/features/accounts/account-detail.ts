import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AccountsApi } from '../../api/accounts.api';
import { ApiProblem } from '../../api/api-problem';
import { LedgerStore } from '../../api/ledger-store';
import { Account, BalanceSnapshot, Transfer } from '../../api/models';
import { Paginator } from '../../api/paginator';
import { shortId } from '../../core/ids';
import { netMinor } from '../../core/money';
import { formatTimestamp } from '../../core/time';
import { FlagChips } from '../../shared/flag-chips';
import { HudPanel } from '../../shared/hud-panel';
import { ProblemText } from '../../shared/problem-text';

const PAGE_SIZE = 25;

export type Direction = 'debit' | 'credit';

export interface TransferRow {
  transfer: Transfer;
  direction: Direction;
  counterpartyId: string;
  kind: string;
}

/** Direction of a transfer relative to `accountId`: it is the debit side or the credit side. */
export function directionFor(transfer: Transfer, accountId: string): Direction {
  return transfer.debitAccountId === accountId ? 'debit' : 'credit';
}

export function kindOf(t: Transfer): string {
  if (t.flags.postPendingTransfer) return 'Post';
  if (t.flags.voidPendingTransfer) return 'Void';
  if (t.flags.pending) return 'Pending';
  return 'Transfer';
}

@Component({
  selector: 'app-account-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, HudPanel, FlagChips, ProblemText],
  template: `
    <div class="page-head">
      <div>
        <h1 class="glitch" [attr.data-text]="title()">{{ title() }}</h1>
        <p class="page-head__sub">
          // account <span class="mono">{{ id() }}</span>
        </p>
      </div>
      <div class="row">
        <a class="cp-btn cp-btn--teal" routerLink="/">All accounts</a>
        <a class="cp-btn cp-btn--solid" routerLink="/transfers/new">New transfer</a>
      </div>
    </div>

    @if (loading()) {
      <p class="state" role="status">Loading account…</p>
    } @else if (problem(); as p) {
      <div class="state state--error" role="alert">
        <p>
          <strong>Error:</strong> this account could not be loaded.
          <app-problem-text [problem]="p" />
        </p>
        <a class="cp-btn cp-btn--sm" routerLink="/">Back to the dashboard</a>
      </div>
    } @else if (account(); as a) {
      <app-hud-panel title="Balances">
        <div class="stack">
          <div class="row">
            <app-flag-chips [flags]="a.flags" />
            <span class="chip chip--muted">Ledger {{ a.ledger }}</span>
            <span class="chip chip--muted">Code {{ a.code }}</span>
            <span class="hint">Created {{ time(a.createdAt) }}</span>
          </div>
          <dl class="stats">
            <div>
              <dt>Debits posted</dt>
              <dd>{{ money(a.debitsPosted) }}</dd>
            </div>
            <div>
              <dt>Debits pending</dt>
              <dd>{{ money(a.debitsPending) }}</dd>
            </div>
            <div>
              <dt>Credits posted</dt>
              <dd>{{ money(a.creditsPosted) }}</dd>
            </div>
            <div>
              <dt>Credits pending</dt>
              <dd>{{ money(a.creditsPending) }}</dd>
            </div>
            <div>
              <dt>Net (posted)</dt>
              <dd [class.amount--neg]="net().startsWith('-')">{{ money(net()) }}</dd>
            </div>
            <div>
              <dt>Net (pending)</dt>
              <dd>{{ money(netPending()) }}</dd>
            </div>
          </dl>
          <p class="hint">
            Net = credits − debits. The account is the debit side of a transfer when money leaves it
            (debits grow) and the credit side when money arrives (credits grow).
          </p>
        </div>
      </app-hud-panel>

      <app-hud-panel title="Transfers">
        <div class="stack">
          @switch (transfers.status()) {
            @case ('loading') {
              <p class="state" role="status">Loading transfers…</p>
            }
            @case ('error') {
              <div class="state state--error" role="alert">
                <p>
                  <strong>Error:</strong> transfers could not be loaded.
                  @if (transfers.error(); as e) {
                    <app-problem-text [problem]="e" />
                  }
                </p>
                <button type="button" class="cp-btn cp-btn--sm" (click)="transfers.refresh()">
                  Try again
                </button>
              </div>
            }
            @default {
              @if (transfers.isEmpty()) {
                <p class="state">No transfers touch this account yet.</p>
              } @else if (transfers.status() === 'loaded') {
                <div
                  class="table-wrap"
                  tabindex="0"
                  role="region"
                  aria-label="Transfers table, scrollable"
                >
                  <table class="cp-table">
                    <caption>
                      Transfers touching this account, newest first. Direction is relative to this
                      account.
                    </caption>
                    <thead>
                      <tr>
                        <th scope="col">Direction</th>
                        <th scope="col">Counterparty</th>
                        <th scope="col" class="num">Amount</th>
                        <th scope="col">Kind</th>
                        <th scope="col">Reference</th>
                        <th scope="col">Time</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (r of rows(); track r.transfer.id) {
                        <tr>
                          <th scope="row">
                            <span class="chip" [class.chip--yellow]="r.direction === 'debit'">
                              {{ r.direction === 'debit' ? 'Debit (out)' : 'Credit (in)' }}
                            </span>
                          </th>
                          <td>
                            <a class="mono" [routerLink]="['/accounts', r.counterpartyId]">{{
                              short(r.counterpartyId)
                            }}</a>
                          </td>
                          <td class="num">
                            <span class="sr-only"
                              >{{ r.direction === 'debit' ? 'minus' : 'plus' }} </span>{{
                              r.direction === 'debit' ? '−' : '+'
                            }}{{ money(r.transfer.amount) }}
                          </td>
                          <td>
                            {{ r.kind }}
                            @if (r.transfer.pendingStatus) {
                              <span class="chip chip--muted">{{ r.transfer.pendingStatus }}</span>
                            }
                          </td>
                          <td>{{ r.transfer.reference ?? '—' }}</td>
                          <td class="mono">{{ time(r.transfer.timestamp) }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
                @if (transfers.hasMore()) {
                  <div class="form-actions">
                    <button
                      type="button"
                      class="cp-btn"
                      (click)="transfers.loadMore()"
                      [disabled]="transfers.loadingMore()"
                      [attr.aria-busy]="transfers.loadingMore()"
                    >
                      {{ transfers.loadingMore() ? 'Loading…' : 'Load more transfers' }}
                    </button>
                  </div>
                }
              }
            }
          }
        </div>
      </app-hud-panel>

      @if (a.flags.history) {
        <app-hud-panel title="Balance history" accent="teal">
          <div class="stack">
            @switch (history.status()) {
              @case ('loading') {
                <p class="state" role="status">Loading balance history…</p>
              }
              @case ('error') {
                <div class="state state--error" role="alert">
                  <p>
                    <strong>Error:</strong> balance history could not be loaded.
                    @if (history.error(); as e) {
                      <app-problem-text [problem]="e" />
                    }
                  </p>
                  <button type="button" class="cp-btn cp-btn--sm" (click)="history.refresh()">
                    Try again
                  </button>
                </div>
              }
              @default {
                @if (history.isEmpty()) {
                  <p class="state">No balance snapshots yet.</p>
                } @else if (history.status() === 'loaded') {
                  <div
                    class="table-wrap"
                    tabindex="0"
                    role="region"
                    aria-label="Balance history table, scrollable"
                  >
                    <table class="cp-table">
                      <caption>
                        Balance after each transfer, newest first
                      </caption>
                      <thead>
                        <tr>
                          <th scope="col">Seq</th>
                          <th scope="col">Time</th>
                          <th scope="col" class="num">Debits posted</th>
                          <th scope="col" class="num">Debits pending</th>
                          <th scope="col" class="num">Credits posted</th>
                          <th scope="col" class="num">Credits pending</th>
                          <th scope="col" class="num">Net (posted)</th>
                        </tr>
                      </thead>
                      <tbody>
                        @for (s of history.items(); track s.transferId + s.seq) {
                          <tr>
                            <th scope="row" class="mono">{{ s.seq }}</th>
                            <td class="mono">{{ time(s.timestamp) }}</td>
                            <td class="num">{{ money(s.debitsPosted) }}</td>
                            <td class="num">{{ money(s.debitsPending) }}</td>
                            <td class="num">{{ money(s.creditsPosted) }}</td>
                            <td class="num">{{ money(s.creditsPending) }}</td>
                            <td class="num">{{ money(snapshotNet(s)) }}</td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  </div>
                  @if (history.hasMore()) {
                    <div class="form-actions">
                      <button
                        type="button"
                        class="cp-btn"
                        (click)="history.loadMore()"
                        [disabled]="history.loadingMore()"
                        [attr.aria-busy]="history.loadingMore()"
                      >
                        {{ history.loadingMore() ? 'Loading…' : 'Load more history' }}
                      </button>
                    </div>
                  }
                }
              }
            }
          </div>
        </app-hud-panel>
      }
    }
  `,
})
export class AccountDetail {
  /** Route parameter `:id` (component input binding). */
  readonly id = input.required<string>();

  private readonly api = inject(AccountsApi);
  private readonly ledgers = inject(LedgerStore);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly account = signal<Account | null>(null);
  protected readonly loading = signal(true);
  protected readonly problem = signal<ApiProblem | null>(null);

  protected readonly transfers = new Paginator<Transfer>(
    (cursor) => this.api.transfers(this.id(), { limit: PAGE_SIZE, cursor }),
    this.destroyRef,
  );
  protected readonly history = new Paginator<BalanceSnapshot>(
    (cursor) => this.api.balances(this.id(), { limit: PAGE_SIZE, cursor }),
    this.destroyRef,
  );

  protected readonly title = computed(() => this.account()?.name ?? 'Account');
  protected readonly net = computed(() => {
    const a = this.account();
    return a ? netMinor(a.creditsPosted, a.debitsPosted) : '0';
  });
  protected readonly netPending = computed(() => {
    const a = this.account();
    return a ? netMinor(a.creditsPending, a.debitsPending) : '0';
  });
  protected readonly rows = computed<TransferRow[]>(() =>
    this.transfers.items().map((t) => {
      const direction = directionFor(t, this.id());
      return {
        transfer: t,
        direction,
        counterpartyId: direction === 'debit' ? t.creditAccountId : t.debitAccountId,
        kind: kindOf(t),
      };
    }),
  );

  constructor() {
    this.ledgers.ensureLoaded();
    effect(() => {
      const id = this.id();
      this.load(id);
    });
  }

  private load(id: string): void {
    this.loading.set(true);
    this.problem.set(null);
    this.account.set(null);
    this.api
      .get(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (a) => {
          if (id !== this.id()) return;
          this.account.set(a);
          this.loading.set(false);
          this.transfers.refresh();
          if (a.flags.history) this.history.refresh();
        },
        error: (e: unknown) => {
          this.problem.set(ApiProblem.from(e));
          this.loading.set(false);
        },
      });
  }

  protected money(minor: string): string {
    const a = this.account();
    return a ? this.ledgers.format(minor, a.ledger) : minor;
  }

  protected snapshotNet(s: BalanceSnapshot): string {
    return netMinor(s.creditsPosted, s.debitsPosted);
  }

  protected short(id: string): string {
    return shortId(id);
  }

  protected time(iso: string): string {
    return formatTimestamp(iso);
  }
}

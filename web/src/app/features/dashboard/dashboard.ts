import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AccountsApi } from '../../api/accounts.api';
import { ApiProblem } from '../../api/api-problem';
import { LedgerStore } from '../../api/ledger-store';
import { Account, Reconciliation } from '../../api/models';
import { Paginator } from '../../api/paginator';
import { ReconciliationApi } from '../../api/reconciliation.api';
import { ActivityFeed } from '../../core/activity-feed';
import { netMinor } from '../../core/money';
import { formatTimestamp } from '../../core/time';
import { shortId } from '../../core/ids';
import { FlagChips } from '../../shared/flag-chips';
import { HudPanel } from '../../shared/hud-panel';
import { ProblemText } from '../../shared/problem-text';
import { NewAccountDialog } from './new-account-dialog';

const ACCOUNTS_PAGE_SIZE = 50;
const TICKER_SIZE = 6;

interface AccountRow {
  account: Account;
  debitsPosted: string;
  debitsPending: string;
  creditsPosted: string;
  creditsPending: string;
  net: string;
  netNegative: boolean;
  netPending: string;
}

@Component({
  selector: 'app-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ActivityFeed],
  imports: [RouterLink, HudPanel, FlagChips, ProblemText, NewAccountDialog],
  template: `
    <div class="page-head">
      <div>
        <h1 class="glitch" data-text="Ledger dashboard">Ledger dashboard</h1>
        <p class="page-head__sub">// double-entry · exact integer money</p>
      </div>
      <div class="row">
        <a class="cp-btn cp-btn--teal" routerLink="/transfers/new">New transfer</a>
        <button type="button" class="cp-btn cp-btn--solid" (click)="dialogOpen.set(true)">
          New account
        </button>
      </div>
    </div>

    <div class="grid grid--2">
      <app-hud-panel title="Ledger health" [accent]="healthAccent()">
        <button
          panelActions
          type="button"
          class="cp-btn cp-btn--sm"
          (click)="checkHealth()"
          [disabled]="healthLoading()"
        >
          Re-check<span class="sr-only"> ledger health</span>
        </button>
        <div aria-live="polite" aria-atomic="false">
          @if (healthLoading() && !health()) {
            <p class="state" role="status">Checking the books…</p>
          } @else if (healthError(); as e) {
            <div class="state state--error">
              <p>
                <strong>Warning:</strong> the reconciliation check failed.
                <app-problem-text [problem]="e" />
              </p>
            </div>
          } @else if (health(); as h) {
            @if (h.balanced) {
              <p class="status-line">
                <span class="chip">&#10003; Balanced</span> Total debits equal total credits in
                every ledger.
              </p>
            } @else {
              <p class="status-line">
                <span class="chip chip--yellow">&#9888; Not balanced</span> The books do not add up.
                Investigate below.
              </p>
            }
            <p class="hint">Checked {{ checkedAt() }}</p>
            <div class="table-wrap" tabindex="0" role="region" aria-label="Per-ledger totals">
              <table class="cp-table">
                <caption>
                  Posted and pending totals per ledger
                </caption>
                <thead>
                  <tr>
                    <th scope="col">Ledger</th>
                    <th scope="col" class="num">Debits posted</th>
                    <th scope="col" class="num">Credits posted</th>
                    <th scope="col" class="num">Debits pending</th>
                    <th scope="col" class="num">Credits pending</th>
                    <th scope="col">Status</th>
                  </tr>
                </thead>
                <tbody>
                  @for (l of h.ledgers; track l.ledger) {
                    <tr>
                      <th scope="row">{{ l.ledger }}</th>
                      <td class="num">{{ ledgers.format(l.debitsPosted, l.ledger) }}</td>
                      <td class="num">{{ ledgers.format(l.creditsPosted, l.ledger) }}</td>
                      <td class="num">{{ ledgers.format(l.debitsPending, l.ledger) }}</td>
                      <td class="num">{{ ledgers.format(l.creditsPending, l.ledger) }}</td>
                      <td>
                        @if (l.balanced) {
                          <span class="chip">&#10003; Balanced</span>
                        } @else {
                          <span class="chip chip--yellow">&#9888; Mismatch</span>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            @if (h.accountMismatches.length > 0) {
              <div class="table-wrap" tabindex="0" role="region" aria-label="Account mismatches">
                <table class="cp-table">
                  <caption>
                    Accounts whose stored counters differ from the recomputed ones
                  </caption>
                  <thead>
                    <tr>
                      <th scope="col">Account</th>
                      <th scope="col">Counter</th>
                      <th scope="col" class="num">Stored</th>
                      <th scope="col" class="num">Recomputed</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (m of h.accountMismatches; track $index) {
                      <tr>
                        <th scope="row">
                          <a [routerLink]="['/accounts', m.accountId]" class="mono">{{
                            short(m.accountId)
                          }}</a>
                        </th>
                        <td class="mono">{{ m.field }}</td>
                        <td class="num">{{ m.stored }}</td>
                        <td class="num">{{ m.recomputed }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          }
        </div>
      </app-hud-panel>

      <app-hud-panel title="Live ticker" accent="teal">
        <a panelActions class="cp-btn cp-btn--sm cp-btn--teal" routerLink="/activity">Full feed</a>
        <p class="status-line" role="status">
          <span
            class="dot"
            [class.dot--live]="feed.status() === 'live'"
            [class.dot--wait]="feed.status() !== 'live'"
            aria-hidden="true"
          ></span>
          <span>{{ statusLabel() }}</span>
        </p>
        <ul class="feed" aria-live="polite" aria-relevant="additions" aria-label="Latest activity">
          @for (item of ticker(); track item.eventId) {
            <li class="feed__item">
              <span class="chip chip--muted">{{ item.type }}</span>
              <span>{{ item.summary }}</span>
              <span class="feed__time">{{ time(item.occurredAt) }}</span>
            </li>
          }
        </ul>
        @if (feed.loaded() && ticker().length === 0) {
          <p class="state">No activity yet. Make a transfer and it appears here.</p>
        }
      </app-hud-panel>
    </div>

    <app-hud-panel title="Accounts">
      <button panelActions type="button" class="cp-btn cp-btn--sm" (click)="reloadAccounts()">
        Refresh<span class="sr-only"> accounts</span>
      </button>
      <div class="stack">
        <p class="hint">
          <strong>Net = credits posted − debits posted.</strong> The debit side of a transfer raises
          the debit account's <em>debits</em> counter and the credit side raises the other account's
          <em>credits</em> counter, so an account that has mostly received money has a positive net.
          Pending amounts are holds from two-phase transfers: they are shown separately and are not
          part of net until posted.
        </p>
        @switch (accounts.status()) {
          @case ('loading') {
            <p class="state" role="status">Loading accounts…</p>
          }
          @case ('error') {
            <div class="state state--error" role="alert">
              <p>
                <strong>Error:</strong> accounts could not be loaded.
                @if (accounts.error(); as e) {
                  <app-problem-text [problem]="e" />
                }
              </p>
              <button type="button" class="cp-btn cp-btn--sm" (click)="reloadAccounts()">
                Try again
              </button>
            </div>
          }
          @default {
            @if (accounts.isEmpty()) {
              <p class="state">No accounts yet. Create the first one with “New account”.</p>
            } @else if (accounts.status() === 'loaded') {
              <div
                class="table-wrap"
                tabindex="0"
                role="region"
                aria-label="Accounts table, scrollable"
              >
                <table class="cp-table">
                  <caption>
                    Accounts. Amounts in each account's own currency.
                  </caption>
                  <thead>
                    <tr>
                      <th scope="col">Name</th>
                      <th scope="col">Code</th>
                      <th scope="col">Flags</th>
                      <th scope="col" class="num">Debits posted</th>
                      <th scope="col" class="num">Debits pending</th>
                      <th scope="col" class="num">Credits posted</th>
                      <th scope="col" class="num">Credits pending</th>
                      <th scope="col" class="num">Net (posted)</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (r of rows(); track r.account.id) {
                      <tr>
                        <th scope="row">
                          <a [routerLink]="['/accounts', r.account.id]">{{ r.account.name }}</a>
                          <span class="hint mono"> ledger {{ r.account.ledger }}</span>
                        </th>
                        <td class="mono">{{ r.account.code }}</td>
                        <td><app-flag-chips [flags]="r.account.flags" /></td>
                        <td class="num">{{ r.debitsPosted }}</td>
                        <td class="num">{{ r.debitsPending }}</td>
                        <td class="num">{{ r.creditsPosted }}</td>
                        <td class="num">{{ r.creditsPending }}</td>
                        <td class="num">
                          <span [class.amount--neg]="r.netNegative">{{ r.net }}</span>
                          <div class="hint">pending {{ r.netPending }}</div>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
              @if (accounts.hasMore()) {
                <div class="form-actions">
                  <button
                    type="button"
                    class="cp-btn"
                    (click)="accounts.loadMore()"
                    [disabled]="accounts.loadingMore()"
                    [attr.aria-busy]="accounts.loadingMore()"
                  >
                    {{ accounts.loadingMore() ? 'Loading…' : 'Load more' }}
                  </button>
                </div>
              }
              @if (accounts.error(); as e) {
                <p class="field__error" role="alert"><app-problem-text [problem]="e" /></p>
              }
            }
          }
        }
      </div>
    </app-hud-panel>

    @if (dialogOpen()) {
      <app-new-account-dialog (closed)="dialogOpen.set(false)" (created)="onCreated()" />
    }
  `,
})
export class Dashboard implements OnInit {
  protected readonly ledgers = inject(LedgerStore);
  protected readonly feed = inject(ActivityFeed);
  private readonly accountsApi = inject(AccountsApi);
  private readonly reconciliation = inject(ReconciliationApi);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly accounts = new Paginator<Account>(
    (cursor) => this.accountsApi.list({ limit: ACCOUNTS_PAGE_SIZE, cursor }),
    this.destroyRef,
  );

  protected readonly health = signal<Reconciliation | null>(null);
  protected readonly healthLoading = signal(false);
  protected readonly healthError = signal<ApiProblem | null>(null);
  protected readonly dialogOpen = signal(false);

  protected readonly healthAccent = computed<'teal' | 'warn' | 'red'>(() => {
    const h = this.health();
    return h === null ? 'red' : h.balanced && h.accountMismatches.length === 0 ? 'teal' : 'warn';
  });
  protected readonly checkedAt = computed(() => {
    const h = this.health();
    return h ? formatTimestamp(h.checkedAt) : '';
  });
  protected readonly ticker = computed(() => this.feed.items().slice(0, TICKER_SIZE));
  protected readonly statusLabel = computed(() => {
    switch (this.feed.status()) {
      case 'live':
        return 'Live';
      case 'reconnecting':
        return 'Reconnecting…';
      case 'closed':
        return 'Disconnected';
      default:
        return 'Connecting…';
    }
  });

  protected readonly rows = computed<AccountRow[]>(() =>
    this.accounts.items().map((a) => {
      const net = netMinor(a.creditsPosted, a.debitsPosted);
      const f = (m: string) => this.ledgers.format(m, a.ledger);
      return {
        account: a,
        debitsPosted: f(a.debitsPosted),
        debitsPending: f(a.debitsPending),
        creditsPosted: f(a.creditsPosted),
        creditsPending: f(a.creditsPending),
        net: f(net),
        netNegative: net.startsWith('-'),
        netPending: f(netMinor(a.creditsPending, a.debitsPending)),
      };
    }),
  );

  ngOnInit(): void {
    this.ledgers.ensureLoaded();
    this.checkHealth();
    this.accounts.refresh();
    this.feed.start();
  }

  protected short(id: string): string {
    return shortId(id);
  }

  protected time(iso: string): string {
    return formatTimestamp(iso);
  }

  protected checkHealth(): void {
    this.healthLoading.set(true);
    this.healthError.set(null);
    this.reconciliation
      .check()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (h) => {
          this.health.set(h);
          this.healthLoading.set(false);
        },
        error: (e: unknown) => {
          this.healthError.set(ApiProblem.from(e));
          this.healthLoading.set(false);
        },
      });
  }

  protected reloadAccounts(): void {
    this.accounts.refresh();
  }

  protected onCreated(): void {
    this.dialogOpen.set(false);
    this.accounts.refresh();
    this.checkHealth();
  }
}

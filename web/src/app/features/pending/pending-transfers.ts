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
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiProblem } from '../../api/api-problem';
import { LedgerStore } from '../../api/ledger-store';
import { Transfer } from '../../api/models';
import { Paginator } from '../../api/paginator';
import { TransfersApi } from '../../api/transfers.api';
import { NEW_UUID, shortId } from '../../core/ids';
import { MoneyParseError, compareMinor, parseMajor } from '../../core/money';
import { NoticeService } from '../../core/notice.service';
import { formatRemaining, formatTimestamp } from '../../core/time';
import { Dialog } from '../../shared/dialog';
import { Field } from '../../shared/field';
import { describedBy } from '../../shared/form-errors';
import { HudPanel } from '../../shared/hud-panel';
import { ProblemText } from '../../shared/problem-text';

const PAGE_SIZE = 50;

export type PendingAction = 'post' | 'void';

interface ActiveAction {
  action: PendingAction;
  transfer: Transfer;
}

/** The `Post`/`Void` request body for a pending transfer (accounts, ledger and code inherited). */
export function buildResolution(
  action: PendingAction,
  id: string,
  pendingId: string,
  amountMinor: string,
) {
  return {
    id,
    pendingId,
    amount: amountMinor,
    flags: { postPendingTransfer: action === 'post', voidPendingTransfer: action === 'void' },
  };
}

@Component({
  selector: 'app-pending-transfers',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink, HudPanel, Dialog, Field, ProblemText],
  template: `
    <div class="page-head">
      <div>
        <h1 class="glitch" data-text="Pending transfers">Pending transfers</h1>
        <p class="page-head__sub">// holds waiting to be posted or voided</p>
      </div>
      <div class="row">
        <a class="cp-btn cp-btn--teal" routerLink="/transfers/new">New transfer</a>
      </div>
    </div>

    <app-hud-panel title="Open holds" accent="teal">
      <button panelActions type="button" class="cp-btn cp-btn--sm" (click)="pager.refresh()">
        Refresh<span class="sr-only"> pending transfers</span>
      </button>
      <div class="stack">
        <p class="hint">
          <strong>Post</strong> moves the hold into the balances (the full amount, or a smaller
          part; the rest is released). <strong>Void</strong> releases the hold. Each action is sent
          as a new transfer with its own id.
        </p>
        @switch (pager.status()) {
          @case ('loading') {
            <p class="state" role="status">Loading pending transfers…</p>
          }
          @case ('error') {
            <div class="state state--error" role="alert">
              <p>
                <strong>Error:</strong> pending transfers could not be loaded.
                @if (pager.error(); as e) {
                  <app-problem-text [problem]="e" />
                }
              </p>
              <button type="button" class="cp-btn cp-btn--sm" (click)="pager.refresh()">
                Try again
              </button>
            </div>
          }
          @default {
            @if (pager.isEmpty()) {
              <p class="state">Nothing is pending. Create a two-phase transfer to see it here.</p>
            } @else if (pager.status() === 'loaded') {
              <div
                class="table-wrap"
                tabindex="0"
                role="region"
                aria-label="Pending transfers table, scrollable"
              >
                <table class="cp-table">
                  <caption>
                    Pending transfers, newest first
                  </caption>
                  <thead>
                    <tr>
                      <th scope="col">Transfer</th>
                      <th scope="col">From → to</th>
                      <th scope="col" class="num">Amount</th>
                      <th scope="col">Expires in</th>
                      <th scope="col">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (t of pager.items(); track t.id) {
                      <tr>
                        <th scope="row">
                          <span class="mono">{{ short(t.id) }}</span>
                          @if (t.reference) {
                            <br /><span class="hint">{{ t.reference }}</span>
                          }
                        </th>
                        <td>
                          <a class="mono" [routerLink]="['/accounts', t.debitAccountId]">{{
                            short(t.debitAccountId)
                          }}</a>
                          <span aria-label="to"> → </span>
                          <a class="mono" [routerLink]="['/accounts', t.creditAccountId]">{{
                            short(t.creditAccountId)
                          }}</a>
                        </td>
                        <td class="num">{{ ledgers.format(t.amount, t.ledger) }}</td>
                        <td>
                          @if (t.expiresAt; as at) {
                            @if (remaining(at) > 0) {
                              <span
                                class="countdown"
                                [class.countdown--soon]="remaining(at) < 60000"
                                >{{ countdown(at) }}</span
                              >
                              <br /><span class="hint">{{ time(at) }}</span>
                            } @else {
                              <span class="chip chip--yellow">Expired</span>
                              <br /><span class="hint">Awaiting release by the server</span>
                            }
                          } @else {
                            <span class="muted">No expiry</span>
                          }
                        </td>
                        <td>
                          <div class="row">
                            <button
                              type="button"
                              class="cp-btn cp-btn--sm cp-btn--teal"
                              [disabled]="isExpired(t)"
                              (click)="open('post', t)"
                            >
                              Post<span class="sr-only"> transfer {{ short(t.id) }}</span>
                            </button>
                            <button
                              type="button"
                              class="cp-btn cp-btn--sm cp-btn--yellow"
                              [disabled]="isExpired(t)"
                              (click)="open('void', t)"
                            >
                              Void<span class="sr-only"> transfer {{ short(t.id) }}</span>
                            </button>
                          </div>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
              @if (pager.hasMore()) {
                <div class="form-actions">
                  <button
                    type="button"
                    class="cp-btn"
                    (click)="pager.loadMore()"
                    [disabled]="pager.loadingMore()"
                    [attr.aria-busy]="pager.loadingMore()"
                  >
                    {{ pager.loadingMore() ? 'Loading…' : 'Load more' }}
                  </button>
                </div>
              }
            }
          }
        }
      </div>
    </app-hud-panel>

    @if (active(); as a) {
      <app-dialog
        [title]="a.action === 'post' ? 'Post pending transfer' : 'Void pending transfer'"
        (closed)="close()"
      >
        <form class="stack" (submit)="$event.preventDefault(); confirm()" novalidate>
          <p>
            Transfer <code class="code">{{ short(a.transfer.id) }}</code> holds
            <span class="amount">{{ ledgers.format(a.transfer.amount, a.transfer.ledger) }}</span>.
          </p>
          @if (a.action === 'post') {
            <app-field
              fieldId="pa-amount"
              label="Amount to post (optional)"
              [hint]="'Leave empty to post the full amount. A smaller amount posts that part and releases the rest.'"
              [error]="amountError()"
            >
              <input
                id="pa-amount"
                class="cp-input"
                type="text"
                inputmode="decimal"
                autocomplete="off"
                [formControl]="amount"
                [attr.aria-invalid]="amountError() ? 'true' : null"
                [attr.aria-describedby]="describe(amountError())"
              />
            </app-field>
          } @else {
            <p class="hint">
              The held amount is released back to the debit account. This cannot be undone.
            </p>
          }
          <p class="hint">
            Action id (idempotency key): <code class="code">{{ currentId() }}</code>
          </p>

          <div aria-live="polite" aria-atomic="true">
            @if (problem(); as p) {
              <div class="result result--error">
                <p class="result__title">{{ a.action === 'post' ? 'Not posted' : 'Not voided' }}</p>
                <p><app-problem-text [problem]="p" /></p>
                @if (p.retryable) {
                  <p class="hint">
                    Safe to retry: the same id is sent, so it is applied at most once.
                  </p>
                }
              </div>
            }
          </div>

          <div class="form-actions">
            <button
              type="submit"
              class="cp-btn cp-btn--solid"
              [disabled]="busy()"
              [attr.aria-busy]="busy()"
            >
              {{ busy() ? 'Sending…' : a.action === 'post' ? 'Post transfer' : 'Void transfer' }}
            </button>
            <button type="button" class="cp-btn" (click)="close()">Cancel</button>
          </div>
        </form>
      </app-dialog>
    }
  `,
})
export class PendingTransfers implements OnInit {
  protected readonly ledgers = inject(LedgerStore);
  private readonly api = inject(TransfersApi);
  private readonly notices = inject(NoticeService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly uuid = inject(NEW_UUID);

  protected readonly pager = new Paginator<Transfer>(
    (cursor) => this.api.list({ pendingStatus: 'PENDING', limit: PAGE_SIZE, cursor }),
    this.destroyRef,
  );

  protected readonly now = signal(Date.now());
  protected readonly active = signal<ActiveAction | null>(null);
  protected readonly busy = signal(false);
  protected readonly problem = signal<ApiProblem | null>(null);
  protected readonly amount = new FormControl('', { nonNullable: true });
  protected readonly amountError = signal<string | null>(null);

  /** One id per (pending transfer, action, amount): a retry of the same input reuses its id. */
  private readonly ids = new Map<string, string>();
  protected readonly currentId = computed(() => {
    const a = this.active();
    return a ? this.idFor(a.action, a.transfer.id, this.amountKey()) : '';
  });
  private readonly amountKey = signal('');

  constructor() {
    this.ledgers.ensureLoaded();
    const timer = setInterval(() => this.now.set(Date.now()), 1000);
    this.destroyRef.onDestroy(() => clearInterval(timer));
    this.amount.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((v) => {
      this.amountError.set(null);
      this.amountKey.set(v.trim());
    });
  }

  ngOnInit(): void {
    this.pager.refresh();
  }

  protected short(id: string): string {
    return shortId(id);
  }

  protected time(iso: string): string {
    return formatTimestamp(iso);
  }

  protected remaining(expiresAt: string): number {
    return Date.parse(expiresAt) - this.now();
  }

  protected countdown(expiresAt: string): string {
    return formatRemaining(this.remaining(expiresAt));
  }

  protected isExpired(t: Transfer): boolean {
    return t.expiresAt !== null && this.remaining(t.expiresAt) <= 0;
  }

  protected describe(error: string | null): string | null {
    return describedBy('pa-amount', true, error !== null);
  }

  protected open(action: PendingAction, transfer: Transfer): void {
    this.amount.setValue('');
    this.amountError.set(null);
    this.problem.set(null);
    this.busy.set(false);
    this.active.set({ action, transfer });
  }

  protected close(): void {
    this.active.set(null);
  }

  private idFor(action: PendingAction, pendingId: string, amountKey: string): string {
    const key = `${action}:${pendingId}:${amountKey}`;
    let id = this.ids.get(key);
    if (id === undefined) {
      id = this.uuid();
      this.ids.set(key, id);
    }
    return id;
  }

  protected confirm(): void {
    const a = this.active();
    if (!a || this.busy()) return;
    const { transfer, action } = a;

    // "0" means the full amount for a post and is what a void carries (API.md: amount > 0 except post/void).
    let amountMinor = '0';
    if (action === 'post' && this.amount.value.trim() !== '') {
      const scale = this.ledgers.get(transfer.ledger)?.assetScale;
      if (scale === undefined) {
        this.amountError.set('The ledger is not loaded yet. Try again in a moment.');
        return;
      }
      try {
        amountMinor = parseMajor(this.amount.value, scale);
      } catch (e) {
        if (e instanceof MoneyParseError) {
          this.amountError.set(e.message);
          return;
        }
        throw e;
      }
      if (compareMinor(amountMinor, transfer.amount) > 0) {
        this.amountError.set(
          `The amount is more than the ${this.ledgers.format(transfer.amount, transfer.ledger)} held.`,
        );
        return;
      }
    }

    const id = this.idFor(action, transfer.id, this.amount.value.trim());
    this.busy.set(true);
    this.problem.set(null);
    this.api
      .create(buildResolution(action, id, transfer.id, amountMinor))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ replay }) => {
          this.busy.set(false);
          this.ids.delete(`${action}:${transfer.id}:${this.amount.value.trim()}`);
          this.pager.remove((t) => t.id === transfer.id);
          this.active.set(null);
          const verb = action === 'post' ? 'posted' : 'voided';
          this.notices.success(
            replay
              ? `Transfer ${shortId(transfer.id)} was already ${verb}.`
              : `Transfer ${shortId(transfer.id)} ${verb}.`,
            replay ? 'exists' : 'created',
          );
        },
        error: (e: unknown) => {
          const p = ApiProblem.from(e);
          this.busy.set(false);
          this.problem.set(p);
          // A rejection that says the hold is already resolved or gone makes the row stale.
          if (/^pending_transfer_(already_|expired|not_)/.test(p.code)) this.pager.refresh();
        },
      });
  }
}

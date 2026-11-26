import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  Signal,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { RouterLink } from '@angular/router';
import { startWith } from 'rxjs';
import { AccountsApi } from '../../api/accounts.api';
import { ApiProblem } from '../../api/api-problem';
import { LedgerStore } from '../../api/ledger-store';
import { Account, Transfer } from '../../api/models';
import { messageForCode } from '../../api/result-codes';
import { TransfersApi } from '../../api/transfers.api';
import { NEW_UUID, shortId } from '../../core/ids';
import { MoneyParseError, parseMajor } from '../../core/money';
import { NoticeService } from '../../core/notice.service';
import { Field } from '../../shared/field';
import { describedBy, errorText } from '../../shared/form-errors';
import { HudPanel } from '../../shared/hud-panel';
import { ProblemText } from '../../shared/problem-text';

export const MAX_REFERENCE = 64;
export const MAX_TIMEOUT_SECONDS = 4294967295;

interface FormValue {
  debitAccountId: string;
  creditAccountId: string;
  amount: string;
  code: number;
  reference: string;
  pending: boolean;
  timeoutSeconds: number;
}

type ControlName =
  | 'debitAccountId'
  | 'creditAccountId'
  | 'amount'
  | 'code'
  | 'reference'
  | 'pending'
  | 'timeoutSeconds';

export type FormResult =
  { kind: 'created' | 'replay'; transfer: Transfer } | { kind: 'error'; problem: ApiProblem };

/** Validates an amount string against the asset scale of the chosen ledger. */
export function amountValidator(scale: () => number | null): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value = control.value as string;
    if (value.trim() === '') return { message: 'Amount is required.' };
    const s = scale();
    if (s === null) return { message: 'Choose a debit account first so the currency is known.' };
    try {
      parseMajor(value, s);
      return null;
    } catch (e) {
      if (e instanceof MoneyParseError) return { message: e.message };
      throw e;
    }
  };
}

@Component({
  selector: 'app-transfer-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink, HudPanel, Field, ProblemText],
  template: `
    <div class="page-head">
      <div>
        <h1 class="glitch" data-text="New transfer">New transfer</h1>
        <p class="page-head__sub">// one debit leg · one credit leg</p>
      </div>
    </div>

    <app-hud-panel title="Move money">
      @if (accountsLoading()) {
        <p class="state" role="status">Loading accounts…</p>
      } @else if (accountsError(); as e) {
        <div class="state state--error" role="alert">
          <p>
            <strong>Error:</strong> accounts could not be loaded. <app-problem-text [problem]="e" />
          </p>
          <button type="button" class="cp-btn cp-btn--sm" (click)="loadAccounts()">
            Try again
          </button>
        </div>
      } @else if (accounts().length < 2) {
        <p class="state">
          A transfer needs at least two accounts in the same ledger.
          <a routerLink="/">Create accounts on the dashboard</a> first.
        </p>
      } @else {
        <form class="stack" [formGroup]="form" (ngSubmit)="submit()" novalidate>
          <div class="form-grid">
            <app-field
              fieldId="tf-debit"
              label="Debit account"
              hint="Money leaves this account."
              [error]="error('debitAccountId', 'Debit account')"
            >
              <select
                id="tf-debit"
                class="cp-input"
                formControlName="debitAccountId"
                [attr.aria-invalid]="error('debitAccountId', 'Debit account') ? 'true' : null"
                [attr.aria-describedby]="
                  describe('tf-debit', true, error('debitAccountId', 'Debit account'))
                "
              >
                <option value="" disabled>Select an account</option>
                @for (a of accounts(); track a.id) {
                  <option [value]="a.id">{{ label(a) }}</option>
                }
              </select>
            </app-field>
            <app-field
              fieldId="tf-credit"
              label="Credit account"
              [hint]="creditHint()"
              [error]="error('creditAccountId', 'Credit account')"
            >
              <select
                id="tf-credit"
                class="cp-input"
                formControlName="creditAccountId"
                [attr.aria-invalid]="error('creditAccountId', 'Credit account') ? 'true' : null"
                [attr.aria-describedby]="
                  describe('tf-credit', true, error('creditAccountId', 'Credit account'))
                "
              >
                <option value="" disabled>Select an account</option>
                @for (a of creditOptions(); track a.id) {
                  <option [value]="a.id">{{ label(a) }}</option>
                }
              </select>
            </app-field>
          </div>

          <div class="form-grid">
            <app-field
              fieldId="tf-amount"
              label="Amount"
              [hint]="amountHint()"
              [error]="error('amount', 'Amount')"
            >
              <input
                id="tf-amount"
                class="cp-input"
                type="text"
                inputmode="decimal"
                autocomplete="off"
                formControlName="amount"
                [attr.aria-invalid]="error('amount', 'Amount') ? 'true' : null"
                [attr.aria-describedby]="describe('tf-amount', true, error('amount', 'Amount'))"
              />
            </app-field>
            <app-field
              fieldId="tf-code"
              label="Code"
              hint="Your own category, 1 to 65535."
              [error]="error('code', 'Code')"
            >
              <input
                id="tf-code"
                class="cp-input"
                type="number"
                inputmode="numeric"
                formControlName="code"
                [attr.aria-invalid]="error('code', 'Code') ? 'true' : null"
                [attr.aria-describedby]="describe('tf-code', true, error('code', 'Code'))"
              />
            </app-field>
          </div>

          <app-field
            fieldId="tf-reference"
            label="Reference (optional)"
            hint="Free text up to 64 characters, e.g. an invoice number."
            [error]="error('reference', 'Reference')"
          >
            <input
              id="tf-reference"
              class="cp-input"
              type="text"
              autocomplete="off"
              formControlName="reference"
              [attr.aria-invalid]="error('reference', 'Reference') ? 'true' : null"
              [attr.aria-describedby]="
                describe('tf-reference', true, error('reference', 'Reference'))
              "
            />
          </app-field>

          <label class="check">
            <input type="checkbox" formControlName="pending" />
            <span class="check__text">
              <span>Two-phase (pending)</span>
              <span class="hint"
                >Hold the amount now and post or void it later from the Pending screen.</span
              >
            </span>
          </label>

          @if (value().pending) {
            <app-field
              fieldId="tf-timeout"
              label="Timeout (seconds)"
              hint="The hold expires after this many seconds. 0 means it does not expire."
              [error]="error('timeoutSeconds', 'Timeout')"
            >
              <input
                id="tf-timeout"
                class="cp-input"
                type="number"
                inputmode="numeric"
                formControlName="timeoutSeconds"
                [attr.aria-invalid]="error('timeoutSeconds', 'Timeout') ? 'true' : null"
                [attr.aria-describedby]="
                  describe('tf-timeout', true, error('timeoutSeconds', 'Timeout'))
                "
              />
            </app-field>
          }

          <p class="hint">
            Transfer id (idempotency key): <code class="code">{{ transferId() }}</code><br />
            Retrying after a network error sends this same id, so the transfer cannot be applied
            twice. A new id is generated after a success or when you choose “New transfer”.
          </p>

          <div class="form-actions">
            <button
              type="submit"
              class="cp-btn cp-btn--solid"
              [disabled]="busy()"
              [attr.aria-busy]="busy()"
            >
              {{ busy() ? 'Sending…' : retrying() ? 'Retry transfer' : 'Send transfer' }}
            </button>
            <button type="button" class="cp-btn" (click)="newTransfer()">New transfer</button>
          </div>
        </form>

        <div class="stack" aria-live="polite" aria-atomic="true" id="transfer-result">
          @if (result(); as r) {
            @if (r.kind === 'error') {
              <div class="result result--error">
                <p class="result__title">Transfer not recorded</p>
                <p><app-problem-text [problem]="r.problem" /></p>
                @if (r.problem.retryable) {
                  <p class="hint">
                    The request may or may not have reached the server. Press “Retry transfer”: the
                    same id is sent, so it is applied at most once.
                  </p>
                }
              </div>
            } @else {
              <div class="result">
                <p class="result__title">
                  {{ r.kind === 'replay' ? 'Already recorded' : 'Transfer recorded' }}
                </p>
                <p>
                  {{ resultMessage(r) }}
                  <code class="code">{{ r.kind === 'replay' ? 'exists' : 'created' }}</code>
                </p>
                <p class="mono">
                  {{ r.transfer.id }} · sequence {{ r.transfer.seq }} ·
                  {{ ledgers.format(r.transfer.amount, r.transfer.ledger) }}
                  @if (r.transfer.pendingStatus) {
                    · {{ r.transfer.pendingStatus }}
                  }
                </p>
                <p>
                  <a [routerLink]="['/accounts', r.transfer.debitAccountId]"
                    >Debit account {{ short(r.transfer.debitAccountId) }}</a
                  >
                  ·
                  <a [routerLink]="['/accounts', r.transfer.creditAccountId]"
                    >Credit account {{ short(r.transfer.creditAccountId) }}</a
                  >
                  @if (r.transfer.pendingStatus === 'PENDING') {
                    · <a routerLink="/transfers/pending">Pending transfers</a>
                  }
                </p>
              </div>
            }
          }
        </div>
      }
    </app-hud-panel>
  `,
})
export class TransferForm implements OnInit {
  protected readonly ledgers = inject(LedgerStore);
  private readonly accountsApi = inject(AccountsApi);
  private readonly transfersApi = inject(TransfersApi);
  private readonly notices = inject(NoticeService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly uuid = inject(NEW_UUID);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly accounts = signal<Account[]>([]);
  protected readonly accountsLoading = signal(true);
  protected readonly accountsError = signal<ApiProblem | null>(null);

  /** The idempotency key. Generated on open/reset, regenerated only after success or "New transfer". */
  readonly transferId = signal(this.uuid());
  protected readonly busy = signal(false);
  protected readonly result = signal<FormResult | null>(null);
  /** True once a send has failed in a way that leaves the outcome unknown. */
  protected readonly retrying = signal(false);
  private readonly submitted = signal(false);

  protected readonly form = this.fb.group({
    debitAccountId: this.fb.control('', [Validators.required]),
    creditAccountId: this.fb.control('', [Validators.required]),
    amount: this.fb.control('', [amountValidator(() => this.scale())]),
    code: this.fb.control(1, [
      Validators.required,
      Validators.min(1),
      Validators.max(65535),
      (c: AbstractControl): ValidationErrors | null =>
        Number.isInteger(c.value) ? null : { message: 'Code must be a whole number.' },
    ]),
    reference: this.fb.control('', [Validators.maxLength(MAX_REFERENCE)]),
    pending: this.fb.control(false),
    timeoutSeconds: this.fb.control(0, [
      Validators.min(0),
      Validators.max(MAX_TIMEOUT_SECONDS),
      (c: AbstractControl): ValidationErrors | null =>
        Number.isInteger(c.value)
          ? null
          : { message: 'Timeout must be a whole number of seconds.' },
    ]),
  });
  private readonly tick = toSignal(this.form.events.pipe(startWith(null)));
  protected readonly value: Signal<FormValue> = computed(() => {
    this.tick();
    return this.form.getRawValue();
  });

  protected readonly debitAccount: Signal<Account | undefined> = computed(() =>
    this.accounts().find((a) => a.id === this.value().debitAccountId),
  );
  protected readonly scale: Signal<number | null> = computed(() => {
    const a = this.debitAccount();
    return a ? (this.ledgers.get(a.ledger)?.assetScale ?? null) : null;
  });
  /** Same-ledger filtering: the credit side is limited to the debit account's ledger. */
  protected readonly creditOptions = computed(() => {
    const debit = this.debitAccount();
    return debit
      ? this.accounts().filter((a) => a.ledger === debit.ledger && a.id !== debit.id)
      : this.accounts();
  });
  protected readonly creditHint = computed(() =>
    this.debitAccount()
      ? `Money arrives here. Only accounts in ledger ${this.debitAccount()?.ledger} are listed.`
      : 'Money arrives here. Pick the debit account first to filter by ledger.',
  );
  protected readonly amountHint = computed(() => {
    const a = this.debitAccount();
    const ledger = a ? this.ledgers.get(a.ledger) : undefined;
    if (!ledger) return 'In major units, e.g. 10.50.';
    return ledger.assetScale === 0
      ? `Whole ${ledger.currency} units, no decimals.`
      : `In ${ledger.currency}, up to ${ledger.assetScale} decimal places, e.g. 10.50.`;
  });

  constructor() {
    this.ledgers.ensureLoaded();
    this.form.controls.debitAccountId.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((debitId) => {
        const credit = this.form.controls.creditAccountId;
        const ok = this.creditOptionsFor(debitId).some((a) => a.id === credit.value);
        if (!ok) credit.setValue('');
        this.form.controls.amount.updateValueAndValidity();
      });
  }

  ngOnInit(): void {
    this.loadAccounts();
  }

  protected loadAccounts(): void {
    this.accountsLoading.set(true);
    this.accountsError.set(null);
    this.accountsApi
      .listAll()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => {
          this.accounts.set(list.filter((a) => !a.flags.closed));
          this.accountsLoading.set(false);
        },
        error: (e: unknown) => {
          this.accountsError.set(ApiProblem.from(e));
          this.accountsLoading.set(false);
        },
      });
  }

  private creditOptionsFor(debitId: string): Account[] {
    const debit = this.accounts().find((a) => a.id === debitId);
    return debit
      ? this.accounts().filter((a) => a.ledger === debit.ledger && a.id !== debit.id)
      : [];
  }

  protected label(a: Account): string {
    return `${a.name} · ledger ${a.ledger} · code ${a.code}`;
  }

  protected short(id: string): string {
    return shortId(id);
  }

  protected error(name: ControlName, label: string): string | null {
    this.tick();
    if (name === 'timeoutSeconds' && !this.form.controls.pending.value) return null;
    const control = this.form.controls[name];
    if (!(control.touched || this.submitted())) return null;
    if (
      name === 'creditAccountId' &&
      control.value === this.form.controls.debitAccountId.value &&
      control.value !== ''
    ) {
      return messageForCode('accounts_must_be_different');
    }
    return errorText(control, label);
  }

  protected describe(id: string, hasHint: boolean, error: string | null): string | null {
    return describedBy(id, hasHint, error !== null);
  }

  protected resultMessage(r: { kind: 'created' | 'replay' }): string {
    return messageForCode(r.kind === 'replay' ? 'exists' : 'created');
  }

  /** A deliberate fresh start: new idempotency key, empty form, no result. */
  protected newTransfer(): void {
    this.transferId.set(this.uuid());
    this.form.reset();
    this.submitted.set(false);
    this.result.set(null);
    this.retrying.set(false);
  }

  protected submit(): void {
    this.submitted.set(true);
    this.form.markAllAsTouched();
    if (this.busy()) return;
    const v = this.form.getRawValue();
    const timeoutValid = !v.pending || this.form.controls.timeoutSeconds.valid;
    const debit = this.debitAccount();
    const sameAccount = v.debitAccountId !== '' && v.debitAccountId === v.creditAccountId;
    const s = this.scale();
    const invalid =
      this.form.controls.debitAccountId.invalid ||
      this.form.controls.creditAccountId.invalid ||
      this.form.controls.amount.invalid ||
      this.form.controls.code.invalid ||
      this.form.controls.reference.invalid ||
      !timeoutValid ||
      sameAccount;
    if (invalid || !debit || s === null) {
      this.focusFirstInvalid();
      return;
    }
    const reference = v.reference.trim();
    this.busy.set(true);
    this.transfersApi
      .create({
        id: this.transferId(),
        debitAccountId: v.debitAccountId,
        creditAccountId: v.creditAccountId,
        amount: parseMajor(v.amount, s),
        ledger: debit.ledger,
        code: v.code,
        flags: { pending: v.pending },
        timeoutSeconds: v.pending ? v.timeoutSeconds : 0,
        reference: reference === '' ? null : reference,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ value, replay }) => {
          this.busy.set(false);
          this.retrying.set(false);
          this.result.set({ kind: replay ? 'replay' : 'created', transfer: value });
          this.notices.success(
            replay ? 'Transfer was already recorded. Nothing changed.' : 'Transfer recorded.',
            replay ? 'exists' : 'created',
          );
          // Success: the next send is a different transfer, so it gets a different id.
          this.transferId.set(this.uuid());
          this.form.patchValue({ amount: '', reference: '' });
          this.form.markAsUntouched();
          this.submitted.set(false);
        },
        error: (e: unknown) => {
          const problem = ApiProblem.from(e);
          this.busy.set(false);
          // The key is kept: a retry after a network error must reuse it.
          this.retrying.set(problem.retryable);
          this.result.set({ kind: 'error', problem });
        },
      });
  }

  private focusFirstInvalid(): void {
    queueMicrotask(() => {
      const el = document.querySelector<HTMLElement>('app-transfer-form [aria-invalid="true"]');
      el?.focus();
    });
  }
}

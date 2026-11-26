import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import {
  FormControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { startWith } from 'rxjs';
import { AccountsApi } from '../../api/accounts.api';
import { ApiProblem } from '../../api/api-problem';
import { LedgerStore } from '../../api/ledger-store';
import { Account } from '../../api/models';
import { NEW_UUID } from '../../core/ids';
import { NoticeService } from '../../core/notice.service';
import { Dialog } from '../../shared/dialog';
import { Field } from '../../shared/field';
import { describedBy, errorText, integerValidator } from '../../shared/form-errors';
import { ProblemText } from '../../shared/problem-text';

@Component({
  selector: 'app-new-account-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, Dialog, Field, ProblemText],
  template: `
    <app-dialog title="New account" (closed)="closed.emit()">
      <form class="stack" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <app-field
          fieldId="na-name"
          label="Name"
          hint="1 to 100 characters."
          [error]="error('name', 'Name')"
        >
          <input
            id="na-name"
            class="cp-input"
            type="text"
            formControlName="name"
            autocomplete="off"
            [attr.aria-invalid]="error('name', 'Name') ? 'true' : null"
            [attr.aria-describedby]="describe('na-name', true, error('name', 'Name'))"
          />
        </app-field>
        <div class="form-grid">
          <app-field fieldId="na-ledger" label="Ledger" [error]="error('ledger', 'Ledger')">
            <select
              id="na-ledger"
              class="cp-input"
              formControlName="ledger"
              [attr.aria-invalid]="error('ledger', 'Ledger') ? 'true' : null"
              [attr.aria-describedby]="describe('na-ledger', false, error('ledger', 'Ledger'))"
            >
              <option [ngValue]="null" disabled>Select a ledger</option>
              @for (l of ledgers.ledgers(); track l.id) {
                <option [ngValue]="l.id">{{ l.currency }} ({{ l.id }})</option>
              }
            </select>
          </app-field>
          <app-field
            fieldId="na-code"
            label="Code"
            hint="Your own category, 1 to 65535."
            [error]="error('code', 'Code')"
          >
            <input
              id="na-code"
              class="cp-input"
              type="number"
              inputmode="numeric"
              formControlName="code"
              [attr.aria-invalid]="error('code', 'Code') ? 'true' : null"
              [attr.aria-describedby]="describe('na-code', true, error('code', 'Code'))"
            />
          </app-field>
        </div>
        <fieldset class="stack plain-fieldset">
          <legend class="field__label">Flags</legend>
          <label class="check">
            <input type="checkbox" formControlName="debitsMustNotExceedCredits" />
            <span class="check__text">
              <span>Debits must not exceed credits</span>
              <span class="hint"
                >Rejects transfers that would overdraw this account as the debit side.</span
              >
            </span>
          </label>
          <label class="check">
            <input type="checkbox" formControlName="creditsMustNotExceedDebits" />
            <span class="check__text">
              <span>Credits must not exceed debits</span>
              <span class="hint"
                >Rejects transfers that would push this account's credits above its debits.</span
              >
            </span>
          </label>
          <label class="check">
            <input type="checkbox" formControlName="history" />
            <span class="check__text">
              <span>Keep balance history</span>
              <span class="hint"
                >Records a balance snapshot after every transfer touching the account.</span
              >
            </span>
          </label>
        </fieldset>
        <p class="hint">
          Account id (idempotency key): <code class="code">{{ accountId() }}</code>
        </p>

        <div aria-live="polite" aria-atomic="true">
          @if (problem(); as p) {
            <div class="result result--error">
              <p class="result__title">{{ conflict() ? 'Conflict' : 'Account not created' }}</p>
              <p><app-problem-text [problem]="p" /></p>
              @if (p.retryable) {
                <p class="hint">
                  Safe to retry: the same account id is sent, so it cannot be created twice.
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
            {{ busy() ? 'Creating…' : 'Create account' }}
          </button>
          @if (conflict()) {
            <button type="button" class="cp-btn cp-btn--yellow" (click)="newId()">
              Use a new id
            </button>
          }
          <button type="button" class="cp-btn" (click)="closed.emit()">Cancel</button>
        </div>
      </form>
    </app-dialog>
  `,
})
export class NewAccountDialog {
  readonly created = output<Account>();
  readonly closed = output<void>();

  protected readonly ledgers = inject(LedgerStore);
  private readonly api = inject(AccountsApi);
  private readonly notices = inject(NoticeService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly uuid = inject(NEW_UUID);
  private readonly fb = inject(NonNullableFormBuilder);

  /** Generated when the dialog opens and reused on every retry. */
  protected readonly accountId = signal(this.uuid());
  protected readonly busy = signal(false);
  protected readonly problem = signal<ApiProblem | null>(null);
  protected readonly conflict = computed(() => this.problem()?.status === 409);
  private readonly submitted = signal(false);

  protected readonly form = this.fb.group({
    name: this.fb.control('', [Validators.required, Validators.maxLength(100)]),
    ledger: new FormControl<number | null>(null, { validators: [Validators.required] }),
    code: new FormControl<number | null>(1, {
      validators: [
        Validators.required,
        Validators.min(1),
        Validators.max(65535),
        integerValidator('Code'),
      ],
    }),
    debitsMustNotExceedCredits: this.fb.control(false),
    creditsMustNotExceedDebits: this.fb.control(false),
    history: this.fb.control(false),
  });
  private readonly tick = toSignal(this.form.events.pipe(startWith(null)));

  constructor() {
    this.ledgers.ensureLoaded();
    // Preselect the first ledger once the list arrives.
    effect(() => {
      const first = this.ledgers.ledgers()[0];
      if (first && this.form.controls.ledger.value === null) {
        this.form.controls.ledger.setValue(first.id);
      }
    });
  }

  protected error(name: 'name' | 'ledger' | 'code', label: string): string | null {
    this.tick();
    const control = this.form.controls[name];
    return control.touched || this.submitted() ? errorText(control, label) : null;
  }

  protected describe(id: string, hasHint: boolean, error: string | null): string | null {
    return describedBy(id, hasHint, error !== null);
  }

  protected newId(): void {
    this.accountId.set(this.uuid());
    this.problem.set(null);
  }

  protected submit(): void {
    this.submitted.set(true);
    this.form.markAllAsTouched();
    if (this.form.invalid || this.busy()) return;
    const v = this.form.getRawValue();
    if (v.ledger === null || v.code === null) return;
    this.busy.set(true);
    this.problem.set(null);
    this.api
      .create({
        id: this.accountId(),
        ledger: v.ledger,
        code: v.code,
        name: v.name.trim(),
        flags: {
          debitsMustNotExceedCredits: v.debitsMustNotExceedCredits,
          creditsMustNotExceedDebits: v.creditsMustNotExceedDebits,
          history: v.history,
        },
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ value, replay }) => {
          this.busy.set(false);
          if (replay) {
            this.notices.info(
              `Account "${value.name}" already existed with the same fields. Nothing changed.`,
              'exists',
            );
          } else {
            this.notices.success(`Account "${value.name}" created.`, 'created');
          }
          this.created.emit(value);
        },
        error: (e: unknown) => {
          this.busy.set(false);
          this.problem.set(ApiProblem.from(e));
        },
      });
  }
}

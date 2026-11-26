import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { AccountFlags } from '../api/models';

const LABELS: readonly (readonly [keyof AccountFlags, string, string])[] = [
  ['debitsMustNotExceedCredits', 'Debits ≤ credits', 'chip'],
  ['creditsMustNotExceedDebits', 'Credits ≤ debits', 'chip'],
  ['history', 'History', 'chip'],
  ['closed', 'Closed', 'chip chip--yellow'],
];

/** Account flags as chips. Shows a muted "No flags" when none are set. */
@Component({
  selector: 'app-flag-chips',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="chips">
      @for (f of active(); track f.label) {
        <span [class]="f.css">{{ f.label }}</span>
      } @empty {
        <span class="chip chip--muted">No flags</span>
      }
    </span>
  `,
})
export class FlagChips {
  readonly flags = input.required<AccountFlags>();
  protected readonly active = computed(() =>
    LABELS.filter(([key]) => this.flags()[key]).map(([, label, css]) => ({ label, css })),
  );
}

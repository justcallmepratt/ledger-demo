import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Label, hint and error text around a projected control. The control must carry
 * `[id]="fieldId"` and `[attr.aria-describedby]="describedBy(...)"` (see form-errors.ts) so the
 * hint and error are announced with it.
 */
@Component({
  selector: 'app-field',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="field">
      <label class="field__label" [for]="fieldId()"
        ><span aria-hidden="true">&rsaquo; </span>{{ label() }}</label
      >
      <ng-content />
      @if (hint()) {
        <p class="field__hint" [id]="fieldId() + '-hint'">{{ hint() }}</p>
      }
      @if (error()) {
        <p class="field__error" [id]="fieldId() + '-error'">{{ error() }}</p>
      }
    </div>
  `,
})
export class Field {
  readonly fieldId = input.required<string>();
  readonly label = input.required<string>();
  readonly hint = input<string | null>(null);
  readonly error = input<string | null>(null);
}

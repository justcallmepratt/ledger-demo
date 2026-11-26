import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ApiProblem } from '../api/api-problem';
import { describeProblem } from '../api/result-codes';

/** A problem as words plus the raw code in monospace, and any per-field validation errors. */
@Component({
  selector: 'app-problem-text',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span>{{ text().message }}</span>
    <code class="code">{{ text().code }}</code>
    @if (problem().errors.length > 0) {
      <ul>
        @for (e of problem().errors; track $index) {
          <li>
            <span class="mono">{{ e.field }}</span>: {{ e.message }}
          </li>
        }
      </ul>
    }
  `,
})
export class ProblemText {
  readonly problem = input.required<ApiProblem>();
  protected readonly text = computed(() => describeProblem(this.problem()));
}

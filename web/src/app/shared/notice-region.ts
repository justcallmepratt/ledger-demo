import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { NoticeService } from '../core/notice.service';

/** Global toast region. The container is always in the DOM so screen readers announce additions. */
@Component({
  selector: 'app-notice-region',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="notices" role="region" aria-label="Notifications">
      <div aria-live="polite" aria-relevant="additions text" class="stack">
        @for (n of notices.notices(); track n.id) {
          <div
            class="notice"
            [class.notice--error]="n.kind === 'error'"
            [class.notice--info]="n.kind === 'info'"
          >
            <p class="notice__text">
              <span class="notice__kind">{{ kindLabel(n.kind) }}</span>
              {{ n.message }}
              @if (n.code) {
                <code class="code">{{ n.code }}</code>
              }
            </p>
            <button type="button" class="cp-btn cp-btn--sm" (click)="notices.dismiss(n.id)">
              Dismiss<span class="sr-only"> notification</span>
            </button>
          </div>
        }
      </div>
    </div>
  `,
})
export class NoticeRegion {
  protected readonly notices = inject(NoticeService);

  protected kindLabel(kind: 'success' | 'info' | 'error'): string {
    return kind === 'success' ? 'Done' : kind === 'error' ? 'Error' : 'Notice';
  }
}

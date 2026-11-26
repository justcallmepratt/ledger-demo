import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  OnDestroy,
  input,
  output,
  viewChild,
} from '@angular/core';

let nextDialogId = 0;

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Modal dialog: role="dialog" aria-modal, focus moves in on open, Tab is trapped,
 * Escape and the close button emit `closed`, and focus returns to the opener.
 * Render it with @if; destroying it closes it.
 */
@Component({
  selector: 'app-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="backdrop" (mousedown)="onBackdrop($event)">
      <div
        #panel
        class="dialog"
        role="dialog"
        aria-modal="true"
        tabindex="-1"
        [attr.aria-labelledby]="titleId"
      >
        <div class="dialog__head">
          <h2 [id]="titleId">{{ title() }}</h2>
          <button type="button" class="cp-btn cp-btn--sm" (click)="closed.emit()">
            Close<span class="sr-only"> dialog</span>
          </button>
        </div>
        <div class="dialog__body"><ng-content /></div>
      </div>
    </div>
  `,
})
export class Dialog implements AfterViewInit, OnDestroy {
  readonly title = input.required<string>();
  readonly closed = output<void>();

  protected readonly titleId = `dialog-title-${nextDialogId++}`;
  private readonly panel = viewChild.required<ElementRef<HTMLElement>>('panel');
  private opener: HTMLElement | null = null;

  constructor() {
    this.opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  }

  ngAfterViewInit(): void {
    const root = this.panel().nativeElement;
    const first = root.querySelector<HTMLElement>(
      '.dialog__body input, .dialog__body select, .dialog__body textarea, .dialog__body button',
    );
    (first ?? root).focus();
  }

  ngOnDestroy(): void {
    if (this.opener && this.opener.isConnected) this.opener.focus();
  }

  protected onBackdrop(event: MouseEvent): void {
    if (event.target === event.currentTarget) this.closed.emit();
  }

  @HostListener('keydown', ['$event'])
  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.stopPropagation();
      this.closed.emit();
      return;
    }
    if (event.key !== 'Tab') return;
    const root = this.panel().nativeElement;
    const items = Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (items.length === 0) {
      event.preventDefault();
      root.focus();
      return;
    }
    const first = items[0] as HTMLElement;
    const last = items[items.length - 1] as HTMLElement;
    const active = document.activeElement;
    if (event.shiftKey && (active === first || active === root)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }
}

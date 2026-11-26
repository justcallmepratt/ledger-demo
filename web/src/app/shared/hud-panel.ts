import { ChangeDetectionStrategy, Component, input } from '@angular/core';

let nextPanelId = 0;

/** Chamfered HUD panel with a titled header. Title is an h2; header actions go in `[panelActions]`. */
@Component({
  selector: 'app-hud-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section
      class="cp-panel"
      [class.cp-panel--teal]="accent() === 'teal'"
      [class.cp-panel--warn]="accent() === 'warn'"
      [attr.aria-labelledby]="titleId"
    >
      <div class="cp-panel__head">
        <h2 [id]="titleId"><span aria-hidden="true">&#9648; </span>{{ title() }}</h2>
        <div class="row"><ng-content select="[panelActions]" /></div>
      </div>
      <div class="cp-panel__body"><ng-content /></div>
    </section>
  `,
})
export class HudPanel {
  readonly title = input.required<string>();
  readonly accent = input<'red' | 'teal' | 'warn'>('red');
  protected readonly titleId = `hud-panel-title-${nextPanelId++}`;
}

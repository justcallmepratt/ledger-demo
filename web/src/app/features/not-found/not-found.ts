import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  template: `
    <div class="page-head">
      <div>
        <h1 class="glitch" data-text="404 · Signal lost">404 · Signal lost</h1>
        <p class="page-head__sub">// no such page</p>
      </div>
    </div>
    <p>That address does not lead anywhere in the ledger dashboard.</p>
    <div class="row">
      <a class="cp-btn cp-btn--solid" routerLink="/">Back to the dashboard</a>
    </div>
  `,
})
export class NotFound {}

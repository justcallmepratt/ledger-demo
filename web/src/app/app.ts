import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { NoticeRegion } from './shared/notice-region';

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, NoticeRegion],
  template: `
    <a class="skip-link" href="#main">Skip to content</a>
    <div class="shell">
      <header class="shell__header">
        <div class="shell__bar">
          <a class="brand" routerLink="/" aria-label="Ledger core, dashboard">
            <span class="brand__name">Ledger//Core</span>
            <span class="brand__sub">Double-entry control panel</span>
          </a>
          <nav aria-label="Main">
            <ul class="nav">
              <li>
                <a
                  routerLink="/"
                  routerLinkActive
                  ariaCurrentWhenActive="page"
                  [routerLinkActiveOptions]="{ exact: true }"
                  >Dashboard</a
                >
              </li>
              <li>
                <a routerLink="/transfers/new" routerLinkActive ariaCurrentWhenActive="page"
                  >New transfer</a
                >
              </li>
              <li>
                <a routerLink="/transfers/pending" routerLinkActive ariaCurrentWhenActive="page"
                  >Pending</a
                >
              </li>
              <li>
                <a routerLink="/activity" routerLinkActive ariaCurrentWhenActive="page">Activity</a>
              </li>
            </ul>
          </nav>
        </div>
      </header>
      <main id="main" class="shell__main" tabindex="-1">
        <router-outlet />
      </main>
      <footer class="shell__footer">
        <p>Visual style inspired by the Cyberpunk 2077 UI. Unofficial fan design.</p>
      </footer>
    </div>
    <app-notice-region />
    <div class="scanlines" aria-hidden="true"></div>
  `,
})
export class App {}

import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LedgerStore } from '../../api/ledger-store';
import { ActivityItem } from '../../api/models';
import { ActivityFeed, FEED_CAP } from '../../core/activity-feed';
import { shortId } from '../../core/ids';
import { formatTimestamp } from '../../core/time';
import { HudPanel } from '../../shared/hud-panel';

@Component({
  selector: 'app-activity-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ActivityFeed],
  imports: [RouterLink, HudPanel],
  template: `
    <div class="page-head">
      <div>
        <h1 class="glitch" data-text="Live activity">Live activity</h1>
        <p class="page-head__sub">// newest first · last {{ cap }} events</p>
      </div>
    </div>

    <app-hud-panel title="Event stream" accent="teal">
      <p panelActions class="status-line" role="status">
        <span
          class="dot"
          [class.dot--live]="feed.status() === 'live'"
          [class.dot--wait]="feed.status() !== 'live'"
          aria-hidden="true"
        ></span>
        <span>Connection: {{ statusLabel() }}</span>
      </p>
      <div class="stack">
        @if (feed.loadFailed()) {
          <div class="state state--error" role="alert">
            <p>
              <strong>Warning:</strong> recent history could not be loaded. New events still arrive
              live.
            </p>
          </div>
        }
        @if (!feed.loaded()) {
          <p class="state" role="status">Loading activity…</p>
        } @else if (feed.items().length === 0) {
          <p class="state">No activity yet. Events appear here the moment they happen.</p>
        }
        <ul class="feed" aria-live="polite" aria-relevant="additions" aria-label="Activity feed">
          @for (item of feed.items(); track item.eventId) {
            <li class="feed__item">
              <span class="chip chip--muted">{{ item.type }}</span>
              <span>
                {{ item.summary }}
                @if (item.amount !== null) {
                  <span class="amount"> · {{ money(item) }}</span>
                }
                @if (item.transferId) {
                  <span class="hint mono"> · transfer {{ short(item.transferId) }}</span>
                }
                @if (item.accountId) {
                  ·
                  <a class="mono" [routerLink]="['/accounts', item.accountId]"
                    >account {{ short(item.accountId) }}</a
                  >
                }
              </span>
              <span class="feed__time">{{ time(item.occurredAt) }}</span>
            </li>
          }
        </ul>
      </div>
    </app-hud-panel>
  `,
})
export class ActivityPage implements OnInit {
  protected readonly feed = inject(ActivityFeed);
  private readonly ledgers = inject(LedgerStore);
  protected readonly cap = FEED_CAP;

  protected readonly statusLabel = computed(() => {
    switch (this.feed.status()) {
      case 'live':
        return 'live';
      case 'reconnecting':
        return 'reconnecting…';
      case 'closed':
        return 'closed';
      default:
        return 'connecting…';
    }
  });

  constructor() {
    this.ledgers.ensureLoaded();
  }

  ngOnInit(): void {
    this.feed.start();
  }

  protected money(item: ActivityItem): string {
    return item.amount === null ? '' : this.ledgers.format(item.amount, item.ledger);
  }

  protected short(id: string): string {
    return shortId(id);
  }

  protected time(iso: string): string {
    return formatTimestamp(iso);
  }
}

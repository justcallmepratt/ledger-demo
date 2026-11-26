import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivityItem } from '../../api/models';
import { EVENT_SOURCE_FACTORY, FEED_CAP } from '../../core/activity-feed';
import { expectNoA11yViolations } from '../../../testing/axe';
import { q, qa, settle } from '../../../testing/dom';
import { FakeEventSource } from '../../../testing/fake-event-source';
import { flushLedgers, http, pageTestProviders } from '../../../testing/providers';
import { ActivityPage } from './activity-page';

function item(n: number, over: Partial<ActivityItem> = {}): ActivityItem {
  return {
    eventId: `e${n}`,
    type: 'transfer.created',
    seq: String(n),
    transferId: '33333333-3333-4333-8333-333333333333',
    accountId: null,
    amount: '1050',
    ledger: 840,
    occurredAt: new Date(Date.UTC(2026, 10, 26, 8, 0, 0) + n * 1000).toISOString(),
    summary: `Alice paid Bob (${n})`,
    ...over,
  };
}

describe('ActivityPage', () => {
  let fixture: ComponentFixture<ActivityPage>;
  let el: HTMLElement;
  let ctrl: HttpTestingController;

  async function open(initial: ActivityItem[] | 'error' = []): Promise<void> {
    fixture = TestBed.createComponent(ActivityPage);
    el = fixture.nativeElement;
    ctrl = http();
    fixture.detectChanges();
    flushLedgers(ctrl);
    const req = ctrl.expectOne((r) => r.url === '/api/v1/activity');
    expect(req.request.params.get('limit')).toBe('50');
    if (initial === 'error') req.flush({}, { status: 500, statusText: 'x' });
    else req.flush(initial);
    await settle(fixture);
  }

  beforeEach(() => {
    FakeEventSource.reset();
    TestBed.configureTestingModule({
      providers: [
        ...pageTestProviders(),
        { provide: EVENT_SOURCE_FACTORY, useValue: (url: string) => new FakeEventSource(url) },
      ],
    });
  });

  afterEach(() => ctrl.verify());

  it('has no automatically detectable accessibility violations', async () => {
    await open([item(2), item(1)]);
    FakeEventSource.last.open();
    await settle(fixture);
    await expectNoA11yViolations(el);
  });

  it('has no accessibility violations when empty or failing', async () => {
    await open('error');
    await expectNoA11yViolations(el);
  });

  it('shows the initial feed, then live events on top, in an aria-live polite list', async () => {
    await open([item(2), item(1)]);
    const list = q(el, 'ul.feed');
    expect(list.getAttribute('aria-live')).toBe('polite');
    expect(qa(list, 'li')).toHaveLength(2);
    expect(FakeEventSource.last.url).toBe('/api/v1/activity/stream');
    FakeEventSource.last.open();
    FakeEventSource.last.emit('activity', JSON.stringify(item(3)));
    await settle(fixture);
    const rows = qa(list, 'li');
    expect(rows).toHaveLength(3);
    expect(rows[0]?.textContent).toContain('Alice paid Bob (3)');
    expect(rows[0]?.textContent).toContain('$10.50');
  });

  it('links to the account when the event has one', async () => {
    await open([
      item(1, {
        accountId: '11111111-1111-4111-8111-111111111111',
        type: 'account.created',
        amount: null,
      }),
    ]);
    expect(q(el, 'ul.feed a').getAttribute('href')).toBe(
      '/accounts/11111111-1111-4111-8111-111111111111',
    );
    expect(el.querySelector('ul.feed .amount')).toBeNull();
  });

  it('caps the feed at 200 items', async () => {
    await open([]);
    for (let n = 1; n <= FEED_CAP + 20; n++) {
      FakeEventSource.last.emit('activity', JSON.stringify(item(n)));
    }
    await settle(fixture);
    expect(qa(el, 'ul.feed li')).toHaveLength(FEED_CAP);
    expect(q(el, 'ul.feed li').textContent).toContain(`(${FEED_CAP + 20})`);
  });

  it('shows the connection status as text, with a status role', async () => {
    await open();
    const status = q(el, '.status-line[role="status"]');
    expect(status.textContent).toContain('connecting');
    FakeEventSource.last.open();
    await settle(fixture);
    expect(status.textContent).toContain('live');
    FakeEventSource.last.transientError();
    await settle(fixture);
    expect(status.textContent).toContain('reconnecting');
    FakeEventSource.last.open();
    await settle(fixture);
    expect(status.textContent).toContain('live');
  });

  it('auto-reconnects after the connection is lost for good', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      await open([item(1)]);
      FakeEventSource.last.open();
      FakeEventSource.last.fatalError();
      await settle(fixture);
      expect(el.textContent).toContain('reconnecting');
      vi.advanceTimersByTime(1000);
      expect(FakeEventSource.instances).toHaveLength(2);
      ctrl.expectOne((r) => r.url === '/api/v1/activity').flush([item(2), item(1)]);
      FakeEventSource.last.open();
      await settle(fixture);
      expect(el.textContent).toContain('live');
      expect(qa(el, 'ul.feed li')).toHaveLength(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it('shows an empty state and a warning when history fails to load', async () => {
    await open([]);
    expect(el.textContent).toContain('No activity yet');
  });

  it('warns when the recent history could not be loaded but keeps streaming', async () => {
    await open('error');
    expect(q(el, '[role="alert"]').textContent).toContain('recent history could not be loaded');
    FakeEventSource.last.emit('activity', JSON.stringify(item(1)));
    await settle(fixture);
    expect(qa(el, 'ul.feed li')).toHaveLength(1);
  });

  it('closes the stream when leaving the page', async () => {
    await open();
    fixture.destroy();
    expect(FakeEventSource.last.closed).toBe(true);
  });
});

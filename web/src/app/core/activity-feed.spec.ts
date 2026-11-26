import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivityItem } from '../api/models';
import { FakeEventSource } from '../../testing/fake-event-source';
import { http, httpTestProviders } from '../../testing/providers';
import {
  ActivityFeed,
  EVENT_SOURCE_FACTORY,
  FEED_CAP,
  RECONNECT_BASE_MS,
  RECONNECT_MAX_MS,
  isActivityItem,
} from './activity-feed';

function item(n: number, over: Partial<ActivityItem> = {}): ActivityItem {
  return {
    eventId: `e${n}`,
    type: 'transfer.created',
    seq: String(n),
    transferId: `t${n}`,
    accountId: null,
    amount: '100',
    ledger: 840,
    occurredAt: new Date(Date.UTC(2026, 10, 26, 8, 0, n)).toISOString(),
    summary: `Transfer ${n}`,
    ...over,
  };
}

describe('ActivityFeed', () => {
  let ctrl: HttpTestingController;
  let feed: ActivityFeed;

  beforeEach(() => {
    vi.useFakeTimers();
    FakeEventSource.reset();
    TestBed.configureTestingModule({
      providers: [
        ...httpTestProviders(),
        ActivityFeed,
        { provide: EVENT_SOURCE_FACTORY, useValue: (url: string) => new FakeEventSource(url) },
      ],
    });
    ctrl = http();
    feed = TestBed.inject(ActivityFeed);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function startWith(initial: ActivityItem[]): void {
    feed.start();
    ctrl.expectOne((r) => r.url === '/api/v1/activity').flush(initial);
  }

  it('loads the initial feed (newest first) then opens the SSE stream', () => {
    startWith([item(3), item(2), item(1)]);
    expect(feed.items().map((i) => i.eventId)).toEqual(['e3', 'e2', 'e1']);
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(FakeEventSource.last.url).toBe('/api/v1/activity/stream');
    expect(feed.status()).toBe('connecting');
    FakeEventSource.last.open();
    expect(feed.status()).toBe('live');
    expect(feed.loaded()).toBe(true);
  });

  it('puts new `activity` events on top', () => {
    startWith([item(2), item(1)]);
    FakeEventSource.last.open();
    FakeEventSource.last.emit('activity', JSON.stringify(item(3)));
    expect(feed.items().map((i) => i.eventId)).toEqual(['e3', 'e2', 'e1']);
  });

  it('ignores duplicates (replayed after Last-Event-ID) by eventId', () => {
    startWith([item(2), item(1)]);
    FakeEventSource.last.emit('activity', JSON.stringify(item(2)));
    FakeEventSource.last.emit('activity', JSON.stringify(item(3)));
    FakeEventSource.last.emit('activity', JSON.stringify(item(3)));
    expect(feed.items().map((i) => i.eventId)).toEqual(['e3', 'e2', 'e1']);
  });

  it('ignores malformed and unknown events', () => {
    startWith([]);
    FakeEventSource.last.emit('activity', 'not json');
    FakeEventSource.last.emit('activity', '{"eventId":1}');
    FakeEventSource.last.emit('activity', 'null');
    expect(feed.items()).toEqual([]);
  });

  it('caps the list at 200, dropping the oldest', () => {
    startWith([]);
    for (let n = 1; n <= FEED_CAP + 25; n++) {
      FakeEventSource.last.emit('activity', JSON.stringify(item(n)));
    }
    expect(feed.items()).toHaveLength(FEED_CAP);
    expect(feed.items()[0]?.eventId).toBe(`e${FEED_CAP + 25}`);
    expect(feed.items()[FEED_CAP - 1]?.eventId).toBe('e26');
  });

  it('caps the merged initial feed too', () => {
    startWith(Array.from({ length: 250 }, (_, i) => item(250 - i)));
    expect(feed.items()).toHaveLength(FEED_CAP);
    expect(feed.items()[0]?.eventId).toBe('e250');
  });

  it('shows a reconnecting status on a transient error and live again on open', () => {
    startWith([]);
    FakeEventSource.last.open();
    FakeEventSource.last.transientError();
    expect(feed.status()).toBe('reconnecting');
    // The browser retries by itself: no new source is created.
    vi.advanceTimersByTime(60_000);
    expect(FakeEventSource.instances).toHaveLength(1);
    FakeEventSource.last.open();
    expect(feed.status()).toBe('live');
  });

  it('reconnects with exponential backoff after a fatal error and refills the gap', () => {
    startWith([item(1)]);
    FakeEventSource.last.open();
    const first = FakeEventSource.last;
    first.fatalError();
    expect(first.closed).toBe(true);
    expect(feed.status()).toBe('reconnecting');

    vi.advanceTimersByTime(RECONNECT_BASE_MS - 1);
    expect(FakeEventSource.instances).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(FakeEventSource.instances).toHaveLength(2);
    // A fresh EventSource has no Last-Event-ID: history is re-fetched and merged.
    ctrl.expectOne((r) => r.url === '/api/v1/activity').flush([item(2), item(1)]);
    expect(feed.items().map((i) => i.eventId)).toEqual(['e2', 'e1']);

    // Fails again before opening: the delay doubles.
    FakeEventSource.last.fatalError();
    vi.advanceTimersByTime(RECONNECT_BASE_MS * 2 - 1);
    expect(FakeEventSource.instances).toHaveLength(2);
    vi.advanceTimersByTime(1);
    expect(FakeEventSource.instances).toHaveLength(3);
    ctrl.expectOne((r) => r.url === '/api/v1/activity').flush([]);

    // A successful open resets the backoff.
    FakeEventSource.last.open();
    expect(feed.status()).toBe('live');
    FakeEventSource.last.fatalError();
    vi.advanceTimersByTime(RECONNECT_BASE_MS);
    expect(FakeEventSource.instances).toHaveLength(4);
    ctrl.expectOne((r) => r.url === '/api/v1/activity').flush([]);
  });

  it('never waits longer than the maximum delay', () => {
    startWith([]);
    for (let i = 0; i < 12; i++) {
      FakeEventSource.last.fatalError();
      vi.advanceTimersByTime(RECONNECT_MAX_MS);
      ctrl.expectOne((r) => r.url === '/api/v1/activity').flush([]);
    }
    expect(FakeEventSource.instances).toHaveLength(13);
  });

  it('keeps working when the initial REST call fails', () => {
    feed.start();
    ctrl.expectOne((r) => r.url === '/api/v1/activity').flush({}, { status: 500, statusText: 'x' });
    expect(feed.loadFailed()).toBe(true);
    expect(feed.loaded()).toBe(true);
    FakeEventSource.last.emit('activity', JSON.stringify(item(1)));
    expect(feed.items()).toHaveLength(1);
  });

  it('start is idempotent and stop closes the stream and cancels reconnects', () => {
    startWith([]);
    feed.start();
    expect(FakeEventSource.instances).toHaveLength(1);
    FakeEventSource.last.fatalError();
    feed.stop();
    expect(feed.status()).toBe('closed');
    vi.advanceTimersByTime(RECONNECT_MAX_MS * 2);
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(FakeEventSource.last.closed).toBe(true);
    ctrl.verify();
  });

  it('is closed when its host is destroyed', () => {
    startWith([]);
    TestBed.resetTestingModule();
    expect(FakeEventSource.last.closed).toBe(true);
  });

  it('orders by time first, then by seq as a BigInt', () => {
    startWith([
      item(1, { occurredAt: '2026-11-26T08:00:00Z', seq: '9223372036854775806' }),
      item(2, { occurredAt: '2026-11-26T08:00:00Z', seq: '9223372036854775807' }),
      item(3, { occurredAt: '2026-11-26T07:00:00Z', seq: '99999999999999999999' }),
    ]);
    expect(feed.items().map((i) => i.eventId)).toEqual(['e2', 'e1', 'e3']);
  });
});

describe('isActivityItem', () => {
  it('accepts a full item and rejects junk', () => {
    expect(isActivityItem(item(1))).toBe(true);
    expect(isActivityItem(null)).toBe(false);
    expect(isActivityItem('x')).toBe(false);
    expect(isActivityItem({})).toBe(false);
  });
});

describe('default EventSource factory', () => {
  it('builds a native EventSource for the stream URL', () => {
    const urls: string[] = [];
    class Native {
      constructor(url: string) {
        urls.push(url);
      }
    }
    vi.stubGlobal('EventSource', Native);
    try {
      const factory = TestBed.inject(EVENT_SOURCE_FACTORY);
      factory('/x');
      expect(urls).toEqual(['/x']);
    } finally {
      vi.unstubAllGlobals();
    }
  });
});

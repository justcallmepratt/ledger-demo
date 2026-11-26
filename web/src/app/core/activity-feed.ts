import { DestroyRef, Injectable, InjectionToken, inject, signal } from '@angular/core';
import { ActivityApi, ACTIVITY_STREAM_URL } from '../api/activity.api';
import { ActivityItem } from '../api/models';

export const FEED_CAP = 200;
export const INITIAL_LIMIT = 50;
export const RECONNECT_BASE_MS = 1000;
export const RECONNECT_MAX_MS = 30000;

/** The part of EventSource the feed needs (so tests can provide a fake). */
export interface EventSourceLike {
  readonly readyState: number;
  onopen: ((ev: Event) => unknown) | null;
  onerror: ((ev: Event) => unknown) | null;
  addEventListener(type: string, listener: (ev: MessageEvent<string>) => unknown): void;
  close(): void;
}

export const EVENT_SOURCE_FACTORY = new InjectionToken<(url: string) => EventSourceLike>(
  'EVENT_SOURCE_FACTORY',
  {
    providedIn: 'root',
    factory: () => (url: string) => new EventSource(url) as unknown as EventSourceLike,
  },
);

/** EventSource.CLOSED. */
const CLOSED = 2;

export type ConnectionStatus = 'connecting' | 'live' | 'reconnecting' | 'closed';

/**
 * Live activity feed: the initial `GET /activity`, then `activity` events from the SSE stream.
 * Newest first (by occurredAt, then seq), capped at 200, deduplicated by eventId, auto-reconnecting with backoff.
 * Provide it at component level (`providers: [ActivityFeed]`): it closes with its host.
 */
@Injectable()
export class ActivityFeed {
  private readonly api = inject(ActivityApi);
  private readonly makeSource = inject(EVENT_SOURCE_FACTORY);

  readonly items = signal<ActivityItem[]>([]);
  readonly status = signal<ConnectionStatus>('connecting');
  readonly loadFailed = signal(false);
  readonly loaded = signal(false);

  private source: EventSourceLike | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private attempts = 0;
  private stopped = false;
  private started = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  start(): void {
    if (this.started) return;
    this.started = true;
    this.stopped = false;
    this.loadInitial();
    this.connect();
  }

  stop(): void {
    this.stopped = true;
    this.started = false;
    if (this.retryTimer !== null) clearTimeout(this.retryTimer);
    this.retryTimer = null;
    this.source?.close();
    this.source = null;
    this.status.set('closed');
  }

  /** Re-fetches the recent history (used on start and after a manual reconnect to close gaps). */
  private loadInitial(): void {
    this.api.recent(INITIAL_LIMIT).subscribe({
      next: (list) => {
        this.loadFailed.set(false);
        this.loaded.set(true);
        this.merge(list);
      },
      error: () => {
        this.loadFailed.set(true);
        this.loaded.set(true);
      },
    });
  }

  private connect(): void {
    if (this.stopped) return;
    this.status.set(this.attempts === 0 ? 'connecting' : 'reconnecting');
    const source = this.makeSource(ACTIVITY_STREAM_URL);
    this.source = source;
    source.onopen = () => {
      this.attempts = 0;
      this.status.set('live');
    };
    source.addEventListener('activity', (ev) => this.onEvent(ev.data));
    source.onerror = () => {
      if (this.stopped) return;
      this.status.set('reconnecting');
      // A CLOSED source will not retry by itself (e.g. the server answered with an HTTP error).
      if (source.readyState === CLOSED) {
        source.close();
        this.scheduleReconnect();
      }
    };
  }

  private scheduleReconnect(): void {
    if (this.retryTimer !== null) return;
    const delay = Math.min(RECONNECT_BASE_MS * 2 ** this.attempts, RECONNECT_MAX_MS);
    this.attempts++;
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      if (this.stopped) return;
      // A fresh EventSource has no Last-Event-ID, so refill the gap from the REST feed.
      this.loadInitial();
      this.connect();
    }, delay);
  }

  private onEvent(data: string): void {
    let item: unknown;
    try {
      item = JSON.parse(data);
    } catch {
      return;
    }
    if (isActivityItem(item)) this.merge([item]);
  }

  /** Merges items (any order), dedupes by eventId, keeps newest-first, caps the list. */
  private merge(incoming: ActivityItem[]): void {
    if (incoming.length === 0) return;
    this.items.update((current) => {
      const seen = new Set(current.map((i) => i.eventId));
      const fresh = incoming.filter((i) => !seen.has(i.eventId));
      if (fresh.length === 0) return current;
      return [...fresh, ...current].sort(compareNewestFirst).slice(0, FEED_CAP);
    });
  }
}

/** Newest first: by occurredAt, ties broken by seq (BigInt-safe). */
function compareNewestFirst(a: ActivityItem, b: ActivityItem): number {
  const ta = Date.parse(a.occurredAt);
  const tb = Date.parse(b.occurredAt);
  if (!Number.isNaN(ta) && !Number.isNaN(tb) && ta !== tb) return tb - ta;
  const sa = seqOf(a.seq);
  const sb = seqOf(b.seq);
  if (sa !== null && sb !== null && sa !== sb) return sa > sb ? -1 : 1;
  return 0;
}

function seqOf(seq: string): bigint | null {
  return /^\d+$/.test(seq) ? BigInt(seq) : null;
}

export function isActivityItem(v: unknown): v is ActivityItem {
  if (typeof v !== 'object' || v === null) return false;
  const o = v as Record<string, unknown>;
  return (
    typeof o['eventId'] === 'string' &&
    typeof o['type'] === 'string' &&
    typeof o['summary'] === 'string' &&
    typeof o['occurredAt'] === 'string'
  );
}

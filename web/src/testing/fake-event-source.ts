import { EventSourceLike } from '../app/core/activity-feed';

/** A controllable EventSource stand-in. `instances` lists every source the app opened. */
export class FakeEventSource implements EventSourceLike {
  static instances: FakeEventSource[] = [];
  static reset(): void {
    FakeEventSource.instances = [];
  }
  static get last(): FakeEventSource {
    return FakeEventSource.instances[FakeEventSource.instances.length - 1] as FakeEventSource;
  }

  readyState = 0;
  onopen: ((ev: Event) => unknown) | null = null;
  onerror: ((ev: Event) => unknown) | null = null;
  closed = false;
  private readonly listeners = new Map<string, ((ev: MessageEvent<string>) => unknown)[]>();

  constructor(readonly url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (ev: MessageEvent<string>) => unknown): void {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }

  close(): void {
    this.closed = true;
    this.readyState = 2;
  }

  open(): void {
    this.readyState = 1;
    this.onopen?.(new Event('open'));
  }

  emit(type: string, data: string): void {
    for (const l of this.listeners.get(type) ?? []) l(new MessageEvent(type, { data }));
  }

  /** The browser keeps retrying itself (readyState CONNECTING). */
  transientError(): void {
    this.readyState = 0;
    this.onerror?.(new Event('error'));
  }

  /** The connection failed for good (readyState CLOSED, e.g. an HTTP error). */
  fatalError(): void {
    this.readyState = 2;
    this.onerror?.(new Event('error'));
  }
}

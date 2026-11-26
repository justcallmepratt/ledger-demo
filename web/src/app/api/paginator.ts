import { DestroyRef, Signal, WritableSignal, computed, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable } from 'rxjs';
import { ApiProblem } from './api-problem';
import { Page } from './models';

export type PagerStatus = 'idle' | 'loading' | 'loaded' | 'error';

/** Keyset pagination state: items, nextCursor, loading/empty/error flags. */
export class Paginator<T> {
  readonly items: WritableSignal<T[]> = signal<T[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly status = signal<PagerStatus>('idle');
  readonly loadingMore = signal(false);
  readonly error = signal<ApiProblem | null>(null);

  readonly hasMore: Signal<boolean> = computed(() => this.nextCursor() !== null);
  readonly isEmpty = computed(() => this.status() === 'loaded' && this.items().length === 0);

  constructor(
    private readonly fetchPage: (cursor: string | null) => Observable<Page<T>>,
    private readonly destroyRef: DestroyRef,
  ) {}

  /** (Re)loads from the first page. */
  refresh(): void {
    this.status.set('loading');
    this.error.set(null);
    this.fetchPage(null)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.items.set(page.items);
          this.nextCursor.set(page.nextCursor);
          this.status.set('loaded');
        },
        error: (e: unknown) => this.fail(e),
      });
  }

  loadMore(): void {
    const cursor = this.nextCursor();
    if (cursor === null || this.loadingMore()) return;
    this.loadingMore.set(true);
    this.error.set(null);
    this.fetchPage(cursor)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.items.update((all) => all.concat(page.items));
          this.nextCursor.set(page.nextCursor);
          this.loadingMore.set(false);
        },
        error: (e: unknown) => {
          this.loadingMore.set(false);
          this.fail(e, false);
        },
      });
  }

  /** Removes an item locally (e.g. a pending transfer that was just posted). */
  remove(predicate: (item: T) => boolean): void {
    this.items.update((all) => all.filter((x) => !predicate(x)));
  }

  /** Puts a new item on top (e.g. an account that was just created). */
  prepend(item: T): void {
    this.items.update((all) => [item, ...all]);
  }

  private fail(e: unknown, resetStatus = true): void {
    this.error.set(ApiProblem.from(e));
    if (resetStatus) this.status.set('error');
  }
}

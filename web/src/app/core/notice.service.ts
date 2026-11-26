import { Injectable, signal } from '@angular/core';

export type NoticeKind = 'success' | 'info' | 'error';

export interface Notice {
  id: number;
  kind: NoticeKind;
  message: string;
  /** Raw result code, shown in monospace. */
  code?: string;
}

/** How long success and info notices stay up. Errors stay until dismissed. */
export const NOTICE_TTL_MS = 8000;

@Injectable({ providedIn: 'root' })
export class NoticeService {
  private nextId = 1;
  private readonly timers = new Map<number, ReturnType<typeof setTimeout>>();
  readonly notices = signal<Notice[]>([]);

  success(message: string, code?: string): number {
    return this.push('success', message, code);
  }

  info(message: string, code?: string): number {
    return this.push('info', message, code);
  }

  error(message: string, code?: string): number {
    return this.push('error', message, code);
  }

  dismiss(id: number): void {
    const timer = this.timers.get(id);
    if (timer !== undefined) clearTimeout(timer);
    this.timers.delete(id);
    this.notices.update((all) => all.filter((n) => n.id !== id));
  }

  private push(kind: NoticeKind, message: string, code?: string): number {
    const id = this.nextId++;
    this.notices.update((all) => [...all, { id, kind, message, code }].slice(-5));
    if (kind !== 'error') {
      this.timers.set(
        id,
        setTimeout(() => this.dismiss(id), NOTICE_TTL_MS),
      );
    }
    return id;
  }
}

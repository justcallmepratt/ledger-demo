import { Injectable, computed, inject, signal } from '@angular/core';
import { LedgersApi } from './ledgers.api';
import { ApiProblem } from './api-problem';
import { Ledger } from './models';
import { formatMinor } from '../core/money';

/** Loads `GET /ledgers` once and formats amounts with each ledger's currency and scale. */
@Injectable({ providedIn: 'root' })
export class LedgerStore {
  private readonly api = inject(LedgersApi);
  private requested = false;

  readonly ledgers = signal<Ledger[]>([]);
  readonly loaded = signal(false);
  readonly error = signal<ApiProblem | null>(null);
  private readonly byId = computed(() => new Map(this.ledgers().map((l) => [l.id, l])));

  /** Idempotent: the first call fetches, later calls do nothing (use reload() to retry). */
  ensureLoaded(): void {
    if (this.requested) return;
    this.reload();
  }

  reload(): void {
    this.requested = true;
    this.error.set(null);
    this.api.list().subscribe({
      next: (list) => {
        this.ledgers.set(list);
        this.loaded.set(true);
      },
      error: (e: unknown) => {
        this.requested = false;
        this.error.set(ApiProblem.from(e));
      },
    });
  }

  get(id: number): Ledger | undefined {
    return this.byId().get(id);
  }

  /** "$10.50" for a known ledger; the raw minor value tagged with the ledger id otherwise. */
  format(minor: string, ledgerId: number): string {
    const ledger = this.byId().get(ledgerId);
    if (!ledger) return `${minor} (ledger ${ledgerId})`;
    return formatMinor(minor, ledger.assetScale, ledger.currency);
  }
}

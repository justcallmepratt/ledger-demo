import { InjectionToken } from '@angular/core';

/** Source of client-generated UUIDs (idempotency keys). Replaceable in tests. */
export const NEW_UUID = new InjectionToken<() => string>('NEW_UUID', {
  providedIn: 'root',
  factory: () => () => crypto.randomUUID(),
});

/** First 8 characters of a UUID, for compact display next to a link to the full id. */
export function shortId(id: string): string {
  return id.slice(0, 8);
}

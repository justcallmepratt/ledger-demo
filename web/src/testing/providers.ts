import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { acceptInterceptor, errorInterceptor } from '../app/api/interceptors';
import { Account, Ledger, Transfer } from '../app/api/models';
import { NEW_UUID } from '../app/core/ids';

/** Deterministic UUIDs: 00000000-0000-4000-8000-000000000001, ...002, ... */
export function sequentialUuids(): () => string {
  let n = 0;
  return () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
}

/** HTTP stack as in the app (both interceptors) but with the testing backend. */
export function httpTestProviders(): (Provider | EnvironmentProviders)[] {
  return [
    provideHttpClient(withInterceptors([acceptInterceptor, errorInterceptor])),
    provideHttpClientTesting(),
  ];
}

export function pageTestProviders(): (Provider | EnvironmentProviders)[] {
  return [
    ...httpTestProviders(),
    provideRouter([]),
    { provide: NEW_UUID, useValue: sequentialUuids() },
  ];
}

export const USD: Ledger = { id: 840, currency: 'USD', assetScale: 2 };

export function account(over: Partial<Account> = {}): Account {
  return {
    id: '11111111-1111-4111-8111-111111111111',
    ledger: 840,
    code: 1,
    name: 'Alice wallet',
    flags: {
      debitsMustNotExceedCredits: false,
      creditsMustNotExceedDebits: false,
      history: false,
      closed: false,
    },
    debitsPending: '0',
    debitsPosted: '0',
    creditsPending: '0',
    creditsPosted: '0',
    createdAt: '2026-11-26T08:00:00Z',
    ...over,
  };
}

export function transfer(over: Partial<Transfer> = {}): Transfer {
  return {
    id: '33333333-3333-4333-8333-333333333333',
    debitAccountId: '11111111-1111-4111-8111-111111111111',
    creditAccountId: '22222222-2222-4222-8222-222222222222',
    amount: '1050',
    ledger: 840,
    code: 1,
    flags: {
      linked: false,
      pending: false,
      postPendingTransfer: false,
      voidPendingTransfer: false,
    },
    pendingId: null,
    timeoutSeconds: 0,
    reference: null,
    seq: '1',
    timestamp: '2026-11-26T08:05:00Z',
    pendingStatus: null,
    expiresAt: null,
    ...over,
  };
}

export function problemBody(code: string, status: number, extra: object = {}): object {
  return { type: 'about:blank', title: code, status, code, ...extra };
}

export function http(): HttpTestingController {
  return TestBed.inject(HttpTestingController);
}

/** Answers the GET /ledgers request that LedgerStore sends. */
export function flushLedgers(ctrl: HttpTestingController, ledgers: Ledger[] = [USD]): void {
  ctrl.expectOne('/api/v1/ledgers').flush(ledgers);
}

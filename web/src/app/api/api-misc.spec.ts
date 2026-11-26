import { DestroyRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { flushLedgers, http, httpTestProviders } from '../../testing/providers';
import { ApiProblem } from './api-problem';
import { LedgerStore } from './ledger-store';
import { Page } from './models';
import { Paginator } from './paginator';
import { RESULT_MESSAGES, describeProblem, messageForCode } from './result-codes';

/** Every code listed in docs/API.md's result table (the `<field>` family is covered separately). */
const API_MD_CODES = [
  'created',
  'exists',
  'exists_with_different_fields',
  'id_must_not_be_nil',
  'flags_are_mutually_exclusive',
  'amount_must_be_positive',
  'accounts_must_be_different',
  'pending_id_required',
  'pending_id_must_be_null',
  'timeout_reserved_for_pending',
  'linked_not_allowed',
  'invalid_request',
  'debit_account_not_found',
  'credit_account_not_found',
  'pending_transfer_not_found',
  'ledger_must_match',
  'transfer_must_have_same_ledger_as_accounts',
  'pending_transfer_not_pending',
  'pending_transfer_already_posted',
  'pending_transfer_already_voided',
  'pending_transfer_expired',
  'exceeds_pending_transfer_amount',
  'debit_account_closed',
  'credit_account_closed',
  'exceeds_credits',
  'exceeds_debits',
  'overflows_balance',
  'linked_event_failed',
  'linked_event_chain_open',
  'account_not_found',
  'transfer_not_found',
  'history_not_enabled',
];

describe('result code messages', () => {
  it.each(API_MD_CODES)('has a human message for %s', (code) => {
    const msg = messageForCode(code);
    expect(msg.length).toBeGreaterThan(10);
    expect(msg).not.toContain('_');
    expect(RESULT_MESSAGES[code]).toBeDefined();
  });

  it('every message is a sentence for people (no raw codes inside)', () => {
    for (const [code, msg] of Object.entries(RESULT_MESSAGES)) {
      expect(msg, code).toMatch(/[.!]$/);
      expect(msg, code).not.toMatch(/[a-z]+_[a-z]+/);
    }
  });

  it('handles the pending_transfer_has_different_<field> family', () => {
    expect(messageForCode('pending_transfer_has_different_debit_account_id')).toBe(
      "The debit account id does not match the pending transfer's debit account id.",
    );
    expect(messageForCode('pending_transfer_has_different_ledger')).toContain('ledger');
    expect(messageForCode('pending_transfer_has_different_code')).toContain('code');
  });

  it('handles exists_with_different_<field> and appends detail for the generic form', () => {
    expect(messageForCode('exists_with_different_amount')).toContain('amount');
    expect(messageForCode('exists_with_different_fields', 'amount, code')).toContain(
      '(amount, code)',
    );
    expect(messageForCode('exists_with_different_fields')).not.toContain('(');
  });

  it('falls back to the detail, then a generic sentence, for unknown codes', () => {
    expect(messageForCode('brand_new_code', 'Server says so')).toBe('Server says so');
    expect(messageForCode('brand_new_code')).toBe('Something unexpected went wrong.');
  });

  it('describeProblem returns the message and the raw code', () => {
    const p = new ApiProblem({ status: 422, code: 'exceeds_credits', title: 'x' });
    expect(describeProblem(p)).toEqual({
      message: RESULT_MESSAGES['exceeds_credits'],
      code: 'exceeds_credits',
    });
  });

  it('has client-side messages for network and server failures', () => {
    expect(messageForCode('network_error')).toMatch(/could not be reached/);
    expect(messageForCode('server_error')).toMatch(/problem/);
  });
});

describe('Paginator', () => {
  function setup(pages: Record<string, Page<number>>) {
    const calls: (string | null)[] = [];
    const destroy = TestBed.inject(DestroyRef);
    const pager = new Paginator<number>((cursor) => {
      calls.push(cursor);
      return of(pages[cursor ?? 'first'] as Page<number>);
    }, destroy);
    return { pager, calls };
  }

  beforeEach(() => TestBed.configureTestingModule({}));

  it('loads the first page, then appends on loadMore until the cursor ends', () => {
    const { pager, calls } = setup({
      first: { items: [1, 2], nextCursor: 'a' },
      a: { items: [3], nextCursor: null },
    });
    expect(pager.status()).toBe('idle');
    pager.refresh();
    expect(pager.status()).toBe('loaded');
    expect(pager.items()).toEqual([1, 2]);
    expect(pager.hasMore()).toBe(true);
    pager.loadMore();
    expect(pager.items()).toEqual([1, 2, 3]);
    expect(pager.hasMore()).toBe(false);
    pager.loadMore();
    expect(calls).toEqual([null, 'a']);
  });

  it('reports empty only after a successful load', () => {
    const { pager } = setup({ first: { items: [], nextCursor: null } });
    expect(pager.isEmpty()).toBe(false);
    pager.refresh();
    expect(pager.isEmpty()).toBe(true);
  });

  it('keeps items and exposes the error when a later page fails', () => {
    const destroy = TestBed.inject(DestroyRef);
    let n = 0;
    const pager = new Paginator<number>(() => {
      n++;
      return n === 1
        ? of({ items: [1], nextCursor: 'a' })
        : throwError(() => new ApiProblem({ status: 0, code: 'network_error' }));
    }, destroy);
    pager.refresh();
    pager.loadMore();
    expect(pager.items()).toEqual([1]);
    expect(pager.status()).toBe('loaded');
    expect(pager.error()?.code).toBe('network_error');
    expect(pager.loadingMore()).toBe(false);
    expect(pager.hasMore()).toBe(true);
  });

  it('goes to the error state when the first page fails', () => {
    const pager = new Paginator<number>(
      () => throwError(() => new Error('boom')),
      TestBed.inject(DestroyRef),
    );
    pager.refresh();
    expect(pager.status()).toBe('error');
    expect(pager.error()?.code).toBe('unknown_error');
  });

  it('ignores loadMore while a page is in flight', () => {
    const subject = new Subject<Page<number>>();
    let calls = 0;
    const pager = new Paginator<number>((cursor): Observable<Page<number>> => {
      calls++;
      return cursor === null ? of({ items: [1], nextCursor: 'a' }) : subject;
    }, TestBed.inject(DestroyRef));
    pager.refresh();
    pager.loadMore();
    pager.loadMore();
    expect(calls).toBe(2);
    expect(pager.loadingMore()).toBe(true);
    subject.next({ items: [2], nextCursor: null });
    expect(pager.items()).toEqual([1, 2]);
  });

  it('removes and prepends items locally', () => {
    const { pager } = setup({ first: { items: [1, 2, 3], nextCursor: null } });
    pager.refresh();
    pager.remove((x) => x === 2);
    pager.prepend(9);
    expect(pager.items()).toEqual([9, 1, 3]);
  });
});

describe('LedgerStore', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: httpTestProviders() }));
  afterEach(() => http().verify());

  it('loads once and formats with the ledger currency and scale', () => {
    const store = TestBed.inject(LedgerStore);
    store.ensureLoaded();
    store.ensureLoaded();
    flushLedgers(http(), [
      { id: 840, currency: 'USD', assetScale: 2 },
      { id: 392, currency: 'JPY', assetScale: 0 },
    ]);
    expect(store.loaded()).toBe(true);
    expect(store.format('1050', 840)).toBe('$10.50');
    expect(store.format('1050', 392)).toBe('¥1,050');
    expect(store.get(840)?.currency).toBe('USD');
  });

  it('shows the raw minor value for an unknown ledger', () => {
    expect(TestBed.inject(LedgerStore).format('1050', 999)).toBe('1050 (ledger 999)');
  });

  it('records an error and allows a retry', () => {
    const store = TestBed.inject(LedgerStore);
    store.ensureLoaded();
    http().expectOne('/api/v1/ledgers').flush({}, { status: 500, statusText: 'x' });
    expect(store.error()?.code).toBe('server_error');
    store.ensureLoaded();
    flushLedgers(http());
    expect(store.error()).toBeNull();
    expect(store.loaded()).toBe(true);
  });
});

import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { account, http, httpTestProviders, problemBody, transfer } from '../../testing/providers';
import { AccountsApi } from './accounts.api';
import { ActivityApi } from './activity.api';
import { ApiProblem } from './api-problem';
import { LedgersApi } from './ledgers.api';
import { ReconciliationApi } from './reconciliation.api';
import { TransfersApi } from './transfers.api';

describe('API services', () => {
  let ctrl: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: httpTestProviders() });
    ctrl = http();
  });

  afterEach(() => ctrl.verify());

  describe('LedgersApi', () => {
    it('GETs /ledgers', () => {
      let out: unknown;
      TestBed.inject(LedgersApi)
        .list()
        .subscribe((l) => (out = l));
      const req = ctrl.expectOne('/api/v1/ledgers');
      expect(req.request.method).toBe('GET');
      req.flush([{ id: 840, currency: 'USD', assetScale: 2 }]);
      expect(out).toEqual([{ id: 840, currency: 'USD', assetScale: 2 }]);
    });
  });

  describe('AccountsApi', () => {
    let api: AccountsApi;
    beforeEach(() => (api = TestBed.inject(AccountsApi)));

    it('lists with limit and cursor params, omitting empty ones', () => {
      api.list({ limit: 50 }).subscribe();
      const first = ctrl.expectOne((r) => r.url === '/api/v1/accounts');
      expect(first.request.params.get('limit')).toBe('50');
      expect(first.request.params.has('cursor')).toBe(false);
      first.flush({ items: [], nextCursor: null });

      api.list({ limit: 50, cursor: 'abc=' }).subscribe();
      const second = ctrl.expectOne((r) => r.url === '/api/v1/accounts');
      expect(second.request.params.get('cursor')).toBe('abc=');
      second.flush({ items: [], nextCursor: null });
    });

    it('sends JSON Accept headers', () => {
      api.list().subscribe();
      const req = ctrl.expectOne((r) => r.url === '/api/v1/accounts');
      expect(req.request.headers.get('Accept')).toContain('application/problem+json');
      req.flush({ items: [], nextCursor: null });
    });

    it('listAll follows nextCursor until it is null', () => {
      let all: unknown[] = [];
      api.listAll(2).subscribe((a) => (all = a));
      const a1 = account({ id: 'a1' });
      const a2 = account({ id: 'a2' });
      const a3 = account({ id: 'a3' });
      ctrl
        .expectOne((r) => r.params.get('limit') === '2' && !r.params.has('cursor'))
        .flush({
          items: [a1, a2],
          nextCursor: 'c1',
        });
      ctrl
        .expectOne((r) => r.params.get('cursor') === 'c1')
        .flush({ items: [a3], nextCursor: null });
      expect(all).toEqual([a1, a2, a3]);
    });

    it('gets one account by id', () => {
      api.get('abc').subscribe();
      const req = ctrl.expectOne('/api/v1/accounts/abc');
      expect(req.request.method).toBe('GET');
      req.flush(account());
    });

    it('reports 201 as created and 200 as a replay', () => {
      const body = { id: 'x', ledger: 840, code: 1, name: 'N', flags: { history: true } };
      const seen: boolean[] = [];
      api.create(body).subscribe((w) => seen.push(w.replay));
      const r1 = ctrl.expectOne('/api/v1/accounts');
      expect(r1.request.method).toBe('POST');
      expect(r1.request.body).toEqual(body);
      r1.flush(account(), { status: 201, statusText: 'Created' });

      api.create(body).subscribe((w) => seen.push(w.replay));
      ctrl.expectOne('/api/v1/accounts').flush(account(), { status: 200, statusText: 'OK' });
      expect(seen).toEqual([false, true]);
    });

    it('turns a 409 into an ApiProblem with its code', () => {
      let err: unknown;
      api
        .create({ id: 'x', ledger: 840, code: 1, name: 'N' })
        .subscribe({ error: (e) => (err = e) });
      ctrl
        .expectOne('/api/v1/accounts')
        .flush(problemBody('exists_with_different_fields', 409, { detail: 'name differs' }), {
          status: 409,
          statusText: 'Conflict',
        });
      expect(err).toBeInstanceOf(ApiProblem);
      expect((err as ApiProblem).code).toBe('exists_with_different_fields');
      expect((err as ApiProblem).status).toBe(409);
      expect((err as ApiProblem).detail).toBe('name differs');
    });

    it('fetches an account’s transfers and balances', () => {
      api.transfers('abc', { limit: 25, cursor: 'c' }).subscribe();
      const t = ctrl.expectOne((r) => r.url === '/api/v1/accounts/abc/transfers');
      expect(t.request.params.get('limit')).toBe('25');
      expect(t.request.params.get('cursor')).toBe('c');
      t.flush({ items: [], nextCursor: null });

      api.balances('abc').subscribe();
      ctrl
        .expectOne((r) => r.url === '/api/v1/accounts/abc/balances')
        .flush({ items: [], nextCursor: null });
    });

    it('url-encodes ids', () => {
      api.get('a/b c').subscribe();
      ctrl.expectOne('/api/v1/accounts/a%2Fb%20c').flush(account());
    });

    it('surfaces history_not_enabled as a 422 problem', () => {
      let err: ApiProblem | undefined;
      api.balances('abc').subscribe({ error: (e) => (err = e) });
      ctrl
        .expectOne((r) => r.url.endsWith('/balances'))
        .flush(problemBody('history_not_enabled', 422), {
          status: 422,
          statusText: 'Unprocessable',
        });
      expect(err?.code).toBe('history_not_enabled');
      expect(err?.status).toBe(422);
    });
  });

  describe('TransfersApi', () => {
    let api: TransfersApi;
    beforeEach(() => (api = TestBed.inject(TransfersApi)));

    it('POSTs a transfer and maps 201 / 200', () => {
      const body = {
        id: 't1',
        amount: '1050',
        debitAccountId: 'a',
        creditAccountId: 'b',
        ledger: 840,
        code: 1,
      };
      const seen: boolean[] = [];
      api.create(body).subscribe((w) => seen.push(w.replay));
      const r = ctrl.expectOne('/api/v1/transfers');
      expect(r.request.method).toBe('POST');
      expect(r.request.body).toEqual(body);
      r.flush(transfer(), { status: 201, statusText: 'Created' });
      api.create(body).subscribe((w) => seen.push(w.replay));
      ctrl.expectOne('/api/v1/transfers').flush(transfer(), { status: 200, statusText: 'OK' });
      expect(seen).toEqual([false, true]);
    });

    it('does not mangle amounts beyond 2^53 in the request body', () => {
      api.create({ id: 't', amount: '9223372036854775807' }).subscribe();
      const r = ctrl.expectOne('/api/v1/transfers');
      expect(r.request.serializeBody()).toContain('"amount":"9223372036854775807"');
      r.flush(transfer());
    });

    it.each([
      ['amount_must_be_positive', 400],
      ['debit_account_not_found', 404],
      ['exceeds_credits', 422],
      ['exists_with_different_fields', 409],
    ])('maps problem+json %s (%i) to an ApiProblem', (code, status) => {
      let err: ApiProblem | undefined;
      api.create({ id: 't', amount: '1' }).subscribe({ error: (e) => (err = e) });
      ctrl
        .expectOne('/api/v1/transfers')
        .flush(problemBody(code, status), { status, statusText: 'x' });
      expect(err).toBeInstanceOf(ApiProblem);
      expect(err?.code).toBe(code);
      expect(err?.status).toBe(status);
      expect(err?.retryable).toBe(false);
    });

    it('lists pending transfers', () => {
      api.list({ pendingStatus: 'PENDING', limit: 50, cursor: 'z' }).subscribe();
      const r = ctrl.expectOne((q) => q.url === '/api/v1/transfers');
      expect(r.request.params.get('pendingStatus')).toBe('PENDING');
      expect(r.request.params.get('limit')).toBe('50');
      expect(r.request.params.get('cursor')).toBe('z');
      r.flush({ items: [], nextCursor: null });
    });

    it('gets one transfer', () => {
      api.get('t1').subscribe();
      ctrl.expectOne('/api/v1/transfers/t1').flush(transfer());
    });
  });

  describe('ReconciliationApi and ActivityApi', () => {
    it('GETs /reconciliation', () => {
      TestBed.inject(ReconciliationApi).check().subscribe();
      ctrl.expectOne('/api/v1/reconciliation').flush({
        checkedAt: '2026-11-26T08:00:00Z',
        balanced: true,
        ledgers: [],
        accountMismatches: [],
      });
    });

    it('GETs /activity with a limit', () => {
      TestBed.inject(ActivityApi).recent(50).subscribe();
      const r = ctrl.expectOne((q) => q.url === '/api/v1/activity');
      expect(r.request.params.get('limit')).toBe('50');
      r.flush([]);
    });
  });
});

describe('ApiProblem.fromHttp', () => {
  it('reads problem+json fields, including validation errors', () => {
    const p = ApiProblem.fromHttp(
      new HttpErrorResponse({
        status: 400,
        error: {
          title: 'Bad Request',
          status: 400,
          code: 'invalid_request',
          detail: 'Validation failed',
          errors: [{ field: 'name', message: 'must not be blank' }, { nope: true }],
        },
      }),
    );
    expect(p.code).toBe('invalid_request');
    expect(p.errors).toEqual([{ field: 'name', message: 'must not be blank' }]);
    expect(p.title).toBe('Bad Request');
    expect(p.message).toBe('Validation failed');
  });

  it('classifies a status-0 failure as a retryable network error', () => {
    const p = ApiProblem.fromHttp(
      new HttpErrorResponse({ status: 0, error: new ProgressEvent('error') }),
    );
    expect(p.code).toBe('network_error');
    expect(p.retryable).toBe(true);
    expect(p.status).toBe(0);
  });

  it('classifies a bare 5xx as a retryable server error', () => {
    const p = ApiProblem.fromHttp(new HttpErrorResponse({ status: 503, error: '<html>' }));
    expect(p.code).toBe('server_error');
    expect(p.retryable).toBe(true);
  });

  it('classifies a bare 4xx as unknown and not retryable', () => {
    const p = ApiProblem.fromHttp(new HttpErrorResponse({ status: 418, error: null }));
    expect(p.code).toBe('unknown_error');
    expect(p.retryable).toBe(false);
  });

  it('ApiProblem.from wraps arbitrary values', () => {
    const existing = new ApiProblem({ status: 400, code: 'x' });
    expect(ApiProblem.from(existing)).toBe(existing);
    expect(ApiProblem.from(new Error('boom')).detail).toBe('boom');
    expect(ApiProblem.from('str').code).toBe('unknown_error');
    expect(ApiProblem.is(existing)).toBe(true);
    expect(ApiProblem.is({})).toBe(false);
  });
});

describe('interceptors', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('turns a network failure into an ApiProblem', () => {
    TestBed.configureTestingModule({ providers: httpTestProviders() });
    let err: unknown;
    TestBed.inject(AccountsApi)
      .list()
      .subscribe({ error: (e) => (err = e) });
    http()
      .expectOne((r) => r.url === '/api/v1/accounts')
      .error(new ProgressEvent('error'), { status: 0 });
    expect(err).toBeInstanceOf(ApiProblem);
    expect((err as ApiProblem).code).toBe('network_error');
  });

  it('leaves successful responses alone', () => {
    TestBed.configureTestingModule({ providers: httpTestProviders() });
    let ok: unknown;
    TestBed.inject(LedgersApi)
      .list()
      .subscribe((v) => (ok = v));
    http().expectOne('/api/v1/ledgers').flush([]);
    expect(ok).toEqual([]);
  });

  it('adds Accept only to API requests and never overrides an existing one', () => {
    TestBed.configureTestingModule({ providers: httpTestProviders() });
    const client = TestBed.inject(HttpClient);
    client.get('/api/v1/x').subscribe();
    const api = http().expectOne('/api/v1/x');
    expect(api.request.headers.get('Accept')).toBe('application/json, application/problem+json');
    api.flush({});
    client.get('/elsewhere').subscribe();
    const other = http().expectOne('/elsewhere');
    expect(other.request.headers.has('Accept')).toBe(false);
    other.flush({});
    client
      .get('/api/v1/y', { headers: { Accept: 'text/plain' }, responseType: 'text' })
      .subscribe();
    const own = http().expectOne('/api/v1/y');
    expect(own.request.headers.get('Accept')).toBe('text/plain');
    own.flush('');
  });
});

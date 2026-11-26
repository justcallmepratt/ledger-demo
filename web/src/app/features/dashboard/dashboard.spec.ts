import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Account, ActivityItem, Reconciliation } from '../../api/models';
import { EVENT_SOURCE_FACTORY } from '../../core/activity-feed';
import { NoticeService } from '../../core/notice.service';
import { expectNoA11yViolations } from '../../../testing/axe';
import {
  clickButton,
  q,
  qa,
  setCheckbox,
  setInput,
  setSelect,
  settle,
  submitForm,
} from '../../../testing/dom';
import { FakeEventSource } from '../../../testing/fake-event-source';
import {
  account,
  flushLedgers,
  http,
  pageTestProviders,
  problemBody,
} from '../../../testing/providers';
import { Dashboard } from './dashboard';

const BALANCED: Reconciliation = {
  checkedAt: '2026-11-26T08:00:00Z',
  balanced: true,
  ledgers: [
    {
      ledger: 840,
      debitsPosted: '1050',
      creditsPosted: '1050',
      debitsPending: '200',
      creditsPending: '200',
      balanced: true,
    },
  ],
  accountMismatches: [],
};

const BROKEN: Reconciliation = {
  checkedAt: '2026-11-26T08:00:00Z',
  balanced: false,
  ledgers: [
    { ...(BALANCED.ledgers[0] as object), creditsPosted: '1000', balanced: false } as never,
  ],
  accountMismatches: [
    {
      accountId: '11111111-1111-4111-8111-111111111111',
      field: 'debitsPosted',
      stored: '1050',
      recomputed: '1000',
    },
  ],
};

function activity(n: number): ActivityItem {
  return {
    eventId: `e${n}`,
    type: 'transfer.created',
    seq: String(n),
    transferId: null,
    accountId: null,
    amount: '100',
    ledger: 840,
    occurredAt: new Date(Date.UTC(2026, 10, 26, 8, 0, n)).toISOString(),
    summary: `Alice paid Bob ${n}`,
  };
}

const ALICE = account({
  id: '11111111-1111-4111-8111-111111111111',
  name: 'Alice wallet',
  flags: {
    debitsMustNotExceedCredits: true,
    creditsMustNotExceedDebits: false,
    history: true,
    closed: false,
  },
  debitsPosted: '250',
  creditsPosted: '1000',
  debitsPending: '0',
  creditsPending: '50',
});
const BOB = account({
  id: '22222222-2222-4222-8222-222222222222',
  name: 'Bob wallet',
  debitsPosted: '1000',
  creditsPosted: '250',
});

describe('Dashboard', () => {
  let fixture: ComponentFixture<Dashboard>;
  let el: HTMLElement;
  let ctrl: HttpTestingController;

  async function open(
    opts: {
      health?: Reconciliation | 'error';
      accounts?: Account[] | 'error';
      nextCursor?: string | null;
      feed?: ActivityItem[];
    } = {},
  ): Promise<void> {
    fixture = TestBed.createComponent(Dashboard);
    el = fixture.nativeElement;
    ctrl = http();
    fixture.detectChanges();
    flushLedgers(ctrl);
    const health = opts.health ?? BALANCED;
    const hReq = ctrl.expectOne('/api/v1/reconciliation');
    if (health === 'error') hReq.flush({}, { status: 500, statusText: 'x' });
    else hReq.flush(health);
    const accounts = opts.accounts ?? [ALICE, BOB];
    const aReq = ctrl.expectOne((r) => r.url === '/api/v1/accounts');
    expect(aReq.request.params.get('limit')).toBe('50');
    if (accounts === 'error') aReq.flush({}, { status: 500, statusText: 'x' });
    else aReq.flush({ items: accounts, nextCursor: opts.nextCursor ?? null });
    ctrl.expectOne((r) => r.url === '/api/v1/activity').flush(opts.feed ?? []);
    await settle(fixture);
  }

  beforeEach(() => {
    FakeEventSource.reset();
    TestBed.configureTestingModule({
      providers: [
        ...pageTestProviders(),
        { provide: EVENT_SOURCE_FACTORY, useValue: (url: string) => new FakeEventSource(url) },
      ],
    });
  });

  afterEach(() => ctrl.verify());

  it('has no automatically detectable accessibility violations', async () => {
    await open({ feed: [activity(1)] });
    await expectNoA11yViolations(el);
  });

  it('has no accessibility violations with mismatches and an open dialog', async () => {
    await open({ health: BROKEN });
    clickButton(el, 'New account');
    await settle(fixture);
    await expectNoA11yViolations(el);
  });

  describe('ledger health', () => {
    it('shows a balanced check with per-ledger totals', async () => {
      await open();
      const panel = q(el, 'section[aria-labelledby]');
      expect(panel.textContent).toContain('Ledger health');
      expect(el.textContent).toContain('Balanced');
      expect(el.textContent).toContain('Total debits equal total credits');
      expect(q(el, 'table caption').textContent).toContain('per ledger');
      expect(el.textContent).toContain('$10.50');
      expect(el.textContent).toContain('$2.00');
      expect(el.textContent).not.toContain('Not balanced');
    });

    it('lists mismatches when the books do not balance', async () => {
      await open({ health: BROKEN });
      expect(el.textContent).toContain('Not balanced');
      expect(el.textContent).toContain('Mismatch');
      const table = qa(el, 'table').find((t) =>
        t.textContent?.includes('differ from the recomputed'),
      );
      expect(table).toBeTruthy();
      expect(table?.textContent).toContain('debitsPosted');
      expect(table?.textContent).toContain('1050');
      expect(table?.textContent).toContain('1000');
      expect(table?.querySelector('a')?.getAttribute('href')).toBe(
        '/accounts/11111111-1111-4111-8111-111111111111',
      );
    });

    it('says so when the check fails and can re-check', async () => {
      await open({ health: 'error' });
      expect(el.textContent).toContain('reconciliation check failed');
      expect(el.textContent).toContain('server_error');
      clickButton(el, /Re-check/);
      ctrl.expectOne('/api/v1/reconciliation').flush(BALANCED);
      await settle(fixture);
      expect(el.textContent).toContain('Balanced');
    });
  });

  describe('accounts table', () => {
    it('shows name, code, flags as chips and the four counters with net', async () => {
      await open();
      expect(q(el, 'table[class="cp-table"] caption, .cp-table caption')).toBeTruthy();
      const rows = qa(el, 'tbody tr').filter((r) => r.textContent?.includes('wallet'));
      expect(rows).toHaveLength(2);
      const alice = rows[0] as HTMLElement;
      expect(alice.querySelector('th a')?.textContent).toBe('Alice wallet');
      expect(alice.textContent).toContain('Debits ≤ credits');
      expect(alice.textContent).toContain('History');
      const cells = qa(alice, 'td.num').map((c) => c.textContent?.replace(/\s+/g, ' ').trim());
      expect(cells[0]).toBe('$2.50'); // debits posted
      expect(cells[1]).toBe('$0.00'); // debits pending
      expect(cells[2]).toBe('$10.00'); // credits posted
      expect(cells[3]).toBe('$0.50'); // credits pending
      expect(cells[4]).toBe('$7.50pending $0.50'); // net = 1000 - 250, pending net on its own line
    });

    it('shows negative nets with a minus sign and a warning colour class', async () => {
      await open();
      const bob = qa(el, 'tbody tr').find((r) =>
        r.textContent?.includes('Bob wallet'),
      ) as HTMLElement;
      const net = q(bob, 'td.num:last-child span');
      expect(net.textContent).toBe('-$7.50');
      expect(net.classList.contains('amount--neg')).toBe(true);
    });

    it('explains the debit and credit sides', async () => {
      await open();
      expect(el.textContent).toContain('Net = credits posted − debits posted');
      expect(el.textContent).toContain('Pending amounts are holds');
    });

    it('links to the account detail page', async () => {
      await open();
      expect(q(el, 'tbody th a').getAttribute('href')).toBe(
        '/accounts/11111111-1111-4111-8111-111111111111',
      );
    });

    it('loads more with the nextCursor and appends', async () => {
      await open({ nextCursor: 'cursor-1' });
      clickButton(el, 'Load more');
      const req = ctrl.expectOne(
        (r) => r.url === '/api/v1/accounts' && r.params.get('cursor') === 'cursor-1',
      );
      req.flush({ items: [account({ id: 'z', name: 'Carol wallet' })], nextCursor: null });
      await settle(fixture);
      expect(qa(el, 'tbody tr').filter((r) => r.textContent?.includes('wallet'))).toHaveLength(3);
      expect(el.textContent).not.toContain('Load more');
    });

    it('shows an empty state', async () => {
      await open({ accounts: [] });
      expect(el.textContent).toContain('No accounts yet');
    });

    it('shows an error state with retry', async () => {
      await open({ accounts: 'error' });
      expect(q(el, '[role="alert"]').textContent).toContain('accounts could not be loaded');
      clickButton(el, 'Try again');
      ctrl
        .expectOne((r) => r.url === '/api/v1/accounts')
        .flush({ items: [ALICE], nextCursor: null });
      await settle(fixture);
      expect(el.textContent).toContain('Alice wallet');
    });

    it('wraps the table in a focusable, labelled scroll region', async () => {
      await open();
      const region = q(el, '.table-wrap[aria-label^="Accounts"]');
      expect(region.getAttribute('tabindex')).toBe('0');
      expect(region.getAttribute('role')).toBe('region');
    });
  });

  describe('live ticker', () => {
    it('shows recent activity and updates from the stream with a polite live region', async () => {
      await open({ feed: [activity(2), activity(1)] });
      const ticker = q(el, 'ul[aria-label="Latest activity"]');
      expect(ticker.getAttribute('aria-live')).toBe('polite');
      expect(ticker.textContent).toContain('Alice paid Bob 2');
      expect(FakeEventSource.last.url).toBe('/api/v1/activity/stream');
      FakeEventSource.last.open();
      await settle(fixture);
      expect(el.textContent).toContain('Live');
      FakeEventSource.last.emit('activity', JSON.stringify(activity(3)));
      await settle(fixture);
      expect(qa(ticker, 'li')[0]?.textContent).toContain('Alice paid Bob 3');
    });

    it('keeps only the latest few items', async () => {
      await open({ feed: Array.from({ length: 10 }, (_, i) => activity(10 - i)) });
      expect(qa(el, 'ul[aria-label="Latest activity"] li')).toHaveLength(6);
    });

    it('shows the reconnecting status', async () => {
      await open();
      FakeEventSource.last.open();
      FakeEventSource.last.transientError();
      await settle(fixture);
      expect(el.textContent).toContain('Reconnecting');
    });

    it('closes the stream when the page is destroyed', async () => {
      await open();
      fixture.destroy();
      expect(FakeEventSource.last.closed).toBe(true);
    });
  });

  describe('new account dialog', () => {
    async function openDialog(): Promise<HTMLElement> {
      await open();
      clickButton(el, 'New account');
      await settle(fixture);
      return q(el, '[role="dialog"]');
    }

    function fillDialog(dialog: HTMLElement): void {
      setInput(dialog, '#na-name', 'Carol wallet');
      setInput(dialog, '#na-code', '7');
      setCheckbox(dialog, 'input[formControlName="history"]', true);
      fixture.detectChanges();
    }

    it('validates before sending', async () => {
      const dialog = await openDialog();
      submitForm(dialog);
      await settle(fixture);
      ctrl.expectNone((r) => r.method === 'POST');
      expect(q(dialog, '#na-name-error').textContent).toContain('Name is required');
      expect(q(dialog, '#na-name').getAttribute('aria-describedby')).toContain('na-name-error');
      setInput(dialog, '#na-name', 'x'.repeat(101));
      setInput(dialog, '#na-code', '70000');
      fixture.detectChanges();
      expect(q(dialog, '#na-name-error').textContent).toContain('at most 100');
      expect(q(dialog, '#na-code-error').textContent).toContain('at most 65535');
    });

    it('preselects the first ledger and offers the loaded ledgers', async () => {
      const dialog = await openDialog();
      expect(q<HTMLSelectElement>(dialog, '#na-ledger').value).toContain('840');
      expect(qa(dialog, '#na-ledger option').map((o) => o.textContent?.trim())).toContain(
        'USD (840)',
      );
    });

    it('creates with a client-generated UUID (201), closes, refreshes and notifies', async () => {
      const dialog = await openDialog();
      fillDialog(dialog);
      submitForm(dialog);
      const req = ctrl.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/accounts');
      expect(req.request.body).toEqual({
        id: '00000000-0000-4000-8000-000000000001',
        ledger: 840,
        code: 7,
        name: 'Carol wallet',
        flags: {
          debitsMustNotExceedCredits: false,
          creditsMustNotExceedDebits: false,
          history: true,
        },
      });
      expect(req.request.body.flags.closed).toBeUndefined();
      req.flush(account({ name: 'Carol wallet' }), { status: 201, statusText: 'Created' });
      ctrl.expectOne('/api/v1/reconciliation').flush(BALANCED);
      ctrl
        .expectOne((r) => r.method === 'GET' && r.url === '/api/v1/accounts')
        .flush({ items: [ALICE, BOB], nextCursor: null });
      await settle(fixture);
      expect(el.querySelector('[role="dialog"]')).toBeNull();
      const notice = TestBed.inject(NoticeService).notices()[0];
      expect(notice?.message).toContain('created');
      expect(notice?.kind).toBe('success');
    });

    it('handles a 200 replay as "already existed"', async () => {
      const dialog = await openDialog();
      fillDialog(dialog);
      submitForm(dialog);
      ctrl
        .expectOne((r) => r.method === 'POST')
        .flush(account({ name: 'Carol wallet' }), { status: 200, statusText: 'OK' });
      ctrl.expectOne('/api/v1/reconciliation').flush(BALANCED);
      ctrl
        .expectOne((r) => r.method === 'GET' && r.url === '/api/v1/accounts')
        .flush({ items: [], nextCursor: null });
      await settle(fixture);
      const notice = TestBed.inject(NoticeService).notices()[0];
      expect(notice?.kind).toBe('info');
      expect(notice?.message).toContain('already existed');
    });

    it('handles a 409 conflict and lets the user choose a new id', async () => {
      const dialog = await openDialog();
      fillDialog(dialog);
      submitForm(dialog);
      ctrl
        .expectOne((r) => r.method === 'POST')
        .flush(problemBody('exists_with_different_fields', 409, { detail: 'name' }), {
          status: 409,
          statusText: 'Conflict',
        });
      await settle(fixture);
      expect(dialog.textContent).toContain('Conflict');
      expect(dialog.textContent).toContain('different details');
      expect(q(dialog, '.result--error code.code').textContent).toBe(
        'exists_with_different_fields',
      );
      clickButton(dialog, 'Use a new id');
      await settle(fixture);
      expect(dialog.textContent).toContain('00000000-0000-4000-8000-000000000002');
      expect(dialog.textContent).not.toContain('Conflict');
      submitForm(dialog);
      const retry = ctrl.expectOne((r) => r.method === 'POST');
      expect((retry.request.body as { id: string }).id).toBe(
        '00000000-0000-4000-8000-000000000002',
      );
      retry.error(new ProgressEvent('error'), { status: 0 });
    });

    it('reuses the same id when retrying after a network error', async () => {
      const dialog = await openDialog();
      fillDialog(dialog);
      submitForm(dialog);
      const first = ctrl.expectOne((r) => r.method === 'POST');
      first.error(new ProgressEvent('error'), { status: 0 });
      await settle(fixture);
      expect(dialog.textContent).toContain('Safe to retry');
      expect(el.querySelector('[role="dialog"]')).toBeTruthy();
      submitForm(dialog);
      const second = ctrl.expectOne((r) => r.method === 'POST');
      expect((second.request.body as { id: string }).id).toBe(
        (first.request.body as { id: string }).id,
      );
      second.error(new ProgressEvent('error'), { status: 0 });
    });

    it('can be dismissed with Escape or Cancel', async () => {
      const dialog = await openDialog();
      dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await settle(fixture);
      expect(el.querySelector('[role="dialog"]')).toBeNull();
      clickButton(el, 'New account');
      await settle(fixture);
      clickButton(el, 'Cancel');
      await settle(fixture);
      expect(el.querySelector('[role="dialog"]')).toBeNull();
    });

    it('selects another ledger', async () => {
      const dialog = await openDialog();
      setSelect(
        dialog,
        '#na-ledger',
        q<HTMLOptionElement>(dialog, '#na-ledger option:nth-child(2)').value,
      );
      expect(dialog).toBeTruthy();
    });
  });
});

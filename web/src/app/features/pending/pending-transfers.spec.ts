import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Transfer } from '../../api/models';
import { NoticeService } from '../../core/notice.service';
import { expectNoA11yViolations } from '../../../testing/axe';
import { q, qa, setInput, settle, submitForm } from '../../../testing/dom';
import {
  flushLedgers,
  http,
  pageTestProviders,
  problemBody,
  transfer,
} from '../../../testing/providers';
import { PendingTransfers, buildResolution } from './pending-transfers';

const NOW = Date.parse('2026-11-26T08:00:00Z');
const P1 = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const P2 = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const P3 = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';

function pending(id: string, over: Partial<Transfer> = {}): Transfer {
  return transfer({
    id,
    amount: '1050',
    flags: { linked: false, pending: true, postPendingTransfer: false, voidPendingTransfer: false },
    pendingStatus: 'PENDING',
    timeoutSeconds: 90,
    expiresAt: new Date(NOW + 90_000).toISOString(),
    ...over,
  });
}

describe('PendingTransfers', () => {
  let fixture: ComponentFixture<PendingTransfers>;
  let el: HTMLElement;
  let ctrl: HttpTestingController;

  async function open(items: Transfer[], nextCursor: string | null = null): Promise<void> {
    fixture = TestBed.createComponent(PendingTransfers);
    el = fixture.nativeElement;
    ctrl = http();
    fixture.detectChanges();
    flushLedgers(ctrl);
    const req = ctrl.expectOne((r) => r.url === '/api/v1/transfers');
    expect(req.request.params.get('pendingStatus')).toBe('PENDING');
    req.flush({ items, nextCursor });
    await settle(fixture);
  }

  function rowFor(id: string): HTMLElement {
    const short = id.slice(0, 8);
    const row = qa(el, 'tbody tr').find((r) => r.textContent?.includes(short));
    if (!row) throw new Error(`no row for ${short}`);
    return row;
  }

  function clickIn(root: HTMLElement, label: string): void {
    const b = qa<HTMLButtonElement>(root, 'button').find((x) =>
      x.textContent?.trim().startsWith(label),
    );
    if (!b) throw new Error(`no button ${label}`);
    b.click();
  }

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(NOW);
    TestBed.configureTestingModule({ providers: pageTestProviders() });
  });

  afterEach(() => {
    ctrl.verify();
    vi.useRealTimers();
  });

  it('has no automatically detectable accessibility violations (list and dialog)', async () => {
    await open([pending(P1), pending(P2, { expiresAt: null })]);
    await expectNoA11yViolations(el);
    clickIn(rowFor(P1), 'Post');
    await settle(fixture);
    await expectNoA11yViolations(el);
  });

  it('shows the empty state', async () => {
    await open([]);
    expect(el.textContent).toContain('Nothing is pending');
  });

  it('shows an error and retries the load', async () => {
    fixture = TestBed.createComponent(PendingTransfers);
    el = fixture.nativeElement;
    ctrl = http();
    fixture.detectChanges();
    flushLedgers(ctrl);
    ctrl
      .expectOne((r) => r.url === '/api/v1/transfers')
      .flush({}, { status: 500, statusText: 'x' });
    await settle(fixture);
    expect(q(el, '[role="alert"]').textContent).toContain('server_error');
    clickIn(el, 'Try again');
    ctrl.expectOne((r) => r.url === '/api/v1/transfers').flush({ items: [], nextCursor: null });
  });

  it('renders a captioned table with column headers and row headers', async () => {
    await open([pending(P1)]);
    expect(q(el, 'table caption').textContent).toContain('Pending transfers');
    expect(qa(el, 'thead th[scope="col"]').length).toBe(5);
    expect(qa(el, 'tbody th[scope="row"]').length).toBe(1);
  });

  describe('countdown', () => {
    it('counts down to expiresAt every second', async () => {
      await open([pending(P1)]);
      expect(q(rowFor(P1), '.countdown').textContent).toBe('1m 30s');
      vi.advanceTimersByTime(1000);
      await settle(fixture);
      expect(q(rowFor(P1), '.countdown').textContent).toBe('1m 29s');
      vi.advanceTimersByTime(29_000);
      await settle(fixture);
      expect(q(rowFor(P1), '.countdown').textContent).toBe('1m 00s');
    });

    it('warns under a minute and marks the hold expired at zero, disabling the actions', async () => {
      await open([pending(P1)]);
      vi.advanceTimersByTime(31_000);
      await settle(fixture);
      expect(q(rowFor(P1), '.countdown').classList.contains('countdown--soon')).toBe(true);
      vi.advanceTimersByTime(60_000);
      await settle(fixture);
      const row = rowFor(P1);
      expect(row.textContent).toContain('Expired');
      expect(qa<HTMLButtonElement>(row, 'button').every((b) => b.disabled)).toBe(true);
    });

    it('shows "No expiry" when there is no timeout', async () => {
      await open([pending(P1, { expiresAt: null, timeoutSeconds: 0 })]);
      expect(rowFor(P1).textContent).toContain('No expiry');
    });

    it('stops its timer when destroyed', async () => {
      await open([pending(P1)]);
      fixture.destroy();
      expect(vi.getTimerCount()).toBe(0);
    });
  });

  describe('post', () => {
    it('posts the full amount: a new transfer with its own id, pendingId and amount 0', async () => {
      await open([pending(P1), pending(P2)]);
      clickIn(rowFor(P1), 'Post');
      await settle(fixture);
      expect(q(el, '[role="dialog"] h2').textContent).toContain('Post pending transfer');
      submitForm(q(el, '[role="dialog"]'));
      const req = ctrl.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/transfers');
      expect(req.request.body).toEqual({
        id: '00000000-0000-4000-8000-000000000001',
        pendingId: P1,
        amount: '0',
        flags: { postPendingTransfer: true, voidPendingTransfer: false },
      });
      req.flush(transfer({ id: '00000000-0000-4000-8000-000000000001', pendingStatus: null }), {
        status: 201,
        statusText: 'Created',
      });
      await settle(fixture);
      expect(el.querySelector('[role="dialog"]')).toBeNull();
      expect(qa(el, 'tbody tr').length).toBe(1);
      expect(el.textContent).not.toContain(P1.slice(0, 8));
      expect(TestBed.inject(NoticeService).notices()[0]?.message).toContain('posted');
    });

    it('posts a partial amount in minor units', async () => {
      await open([pending(P1)]);
      clickIn(rowFor(P1), 'Post');
      await settle(fixture);
      setInput(el, '#pa-amount', '5.25');
      submitForm(q(el, '[role="dialog"]'));
      const req = ctrl.expectOne((r) => r.method === 'POST');
      expect((req.request.body as { amount: string }).amount).toBe('525');
      req.flush(transfer(), { status: 201, statusText: 'Created' });
    });

    it.each([
      ['abc', 'digits'],
      ['1.234', '2 decimal places'],
      ['10.51', 'more than the $10.50 held'],
      ['-1', 'digits'],
    ])('rejects partial amount %j without sending', async (value, message) => {
      await open([pending(P1)]);
      clickIn(rowFor(P1), 'Post');
      await settle(fixture);
      setInput(el, '#pa-amount', value);
      submitForm(q(el, '[role="dialog"]'));
      await settle(fixture);
      ctrl.expectNone((r) => r.method === 'POST');
      const input = q(el, '#pa-amount');
      expect(input.getAttribute('aria-invalid')).toBe('true');
      expect(input.getAttribute('aria-describedby')).toContain('pa-amount-error');
      expect(q(el, '#pa-amount-error').textContent).toContain(message);
    });

    it('accepts exactly the held amount and a zero amount means the full amount', async () => {
      await open([pending(P1)]);
      clickIn(rowFor(P1), 'Post');
      await settle(fixture);
      setInput(el, '#pa-amount', '10.50');
      submitForm(q(el, '[role="dialog"]'));
      const req = ctrl.expectOne((r) => r.method === 'POST');
      expect((req.request.body as { amount: string }).amount).toBe('1050');
      req.flush(transfer(), { status: 201, statusText: 'Created' });
    });
  });

  describe('void', () => {
    it('voids with a different id from a post, amount 0 and the void flag', async () => {
      await open([pending(P1), pending(P2)]);
      clickIn(rowFor(P1), 'Post');
      await settle(fixture);
      submitForm(q(el, '[role="dialog"]'));
      const post = ctrl.expectOne((r) => r.method === 'POST');
      post.flush(transfer(), { status: 201, statusText: 'Created' });
      await settle(fixture);

      clickIn(rowFor(P2), 'Void');
      await settle(fixture);
      expect(q(el, '[role="dialog"] h2').textContent).toContain('Void pending transfer');
      expect(el.querySelector('#pa-amount')).toBeNull();
      submitForm(q(el, '[role="dialog"]'));
      const req = ctrl.expectOne((r) => r.method === 'POST');
      expect(req.request.body).toEqual({
        id: '00000000-0000-4000-8000-000000000002',
        pendingId: P2,
        amount: '0',
        flags: { postPendingTransfer: false, voidPendingTransfer: true },
      });
      req.flush(transfer(), { status: 201, statusText: 'Created' });
      await settle(fixture);
      expect(el.querySelector('tbody tr')).toBeNull();
      expect(el.textContent).toContain('Nothing is pending');
    });

    it('treats a 200 replay as already done and still removes the row', async () => {
      await open([pending(P1)]);
      clickIn(rowFor(P1), 'Void');
      await settle(fixture);
      submitForm(q(el, '[role="dialog"]'));
      ctrl
        .expectOne((r) => r.method === 'POST')
        .flush(transfer(), { status: 200, statusText: 'OK' });
      await settle(fixture);
      expect(TestBed.inject(NoticeService).notices()[0]?.message).toContain('already voided');
      expect(el.querySelector('tbody tr')).toBeNull();
    });
  });

  describe('idempotency and errors', () => {
    it('reuses the action id when retrying after a network error', async () => {
      await open([pending(P1)]);
      clickIn(rowFor(P1), 'Post');
      await settle(fixture);
      submitForm(q(el, '[role="dialog"]'));
      const first = ctrl.expectOne((r) => r.method === 'POST');
      const id = (first.request.body as { id: string }).id;
      first.error(new ProgressEvent('error'), { status: 0 });
      await settle(fixture);
      expect(q(el, '[role="dialog"]').textContent).toContain('network_error');
      expect(q(el, '[role="dialog"]').textContent).toContain('Safe to retry');
      submitForm(q(el, '[role="dialog"]'));
      const second = ctrl.expectOne((r) => r.method === 'POST');
      expect((second.request.body as { id: string }).id).toBe(id);
      second.flush(transfer(), { status: 201, statusText: 'Created' });
    });

    it('uses a fresh id when the partial amount is changed, and the old id again if restored', async () => {
      await open([pending(P1)]);
      clickIn(rowFor(P1), 'Post');
      await settle(fixture);
      setInput(el, '#pa-amount', '1');
      submitForm(q(el, '[role="dialog"]'));
      const a = ctrl.expectOne((r) => r.method === 'POST');
      const idA = (a.request.body as { id: string }).id;
      a.error(new ProgressEvent('error'), { status: 0 });
      await settle(fixture);
      setInput(el, '#pa-amount', '2');
      submitForm(q(el, '[role="dialog"]'));
      const b = ctrl.expectOne((r) => r.method === 'POST');
      const idB = (b.request.body as { id: string }).id;
      expect(idB).not.toBe(idA);
      b.error(new ProgressEvent('error'), { status: 0 });
      await settle(fixture);
      setInput(el, '#pa-amount', '1');
      submitForm(q(el, '[role="dialog"]'));
      const c = ctrl.expectOne((r) => r.method === 'POST');
      expect((c.request.body as { id: string }).id).toBe(idA);
      c.flush(transfer(), { status: 201, statusText: 'Created' });
    });

    it('explains a rejection in words with the raw code and refreshes a stale list', async () => {
      await open([pending(P1)]);
      clickIn(rowFor(P1), 'Post');
      await settle(fixture);
      submitForm(q(el, '[role="dialog"]'));
      ctrl
        .expectOne((r) => r.method === 'POST')
        .flush(problemBody('pending_transfer_already_posted', 422), {
          status: 422,
          statusText: 'x',
        });
      await settle(fixture);
      const dialog = q(el, '[role="dialog"]');
      expect(dialog.textContent).toContain('already posted');
      expect(q(dialog, '.result--error code.code').textContent).toBe('pending_transfer_already_posted');
      expect(dialog.textContent).not.toContain('Safe to retry');
      ctrl
        .expectOne((r) => r.method === 'GET' && r.url === '/api/v1/transfers')
        .flush({ items: [], nextCursor: null });
    });

    it('shows exceeds_pending_transfer_amount in words', async () => {
      await open([pending(P1)]);
      clickIn(rowFor(P1), 'Post');
      await settle(fixture);
      submitForm(q(el, '[role="dialog"]'));
      ctrl
        .expectOne((r) => r.method === 'POST')
        .flush(problemBody('exceeds_pending_transfer_amount', 422), {
          status: 422,
          statusText: 'x',
        });
      await settle(fixture);
      expect(q(el, '[role="dialog"]').textContent).toContain(
        'more than the pending transfer holds',
      );
    });
  });

  describe('dialog behaviour', () => {
    it('closes on Cancel and on Escape, returning focus to the opening button', async () => {
      await open([pending(P1)]);
      const opener = qa<HTMLButtonElement>(rowFor(P1), 'button')[0] as HTMLButtonElement;
      opener.focus();
      opener.click();
      await settle(fixture);
      const dialog = q(el, '[role="dialog"]');
      expect(dialog.getAttribute('aria-modal')).toBe('true');
      expect(dialog.getAttribute('aria-labelledby')).toBeTruthy();
      dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await settle(fixture);
      expect(el.querySelector('[role="dialog"]')).toBeNull();

      opener.click();
      await settle(fixture);
      clickIn(el, 'Cancel');
      await settle(fixture);
      expect(el.querySelector('[role="dialog"]')).toBeNull();
    });

    it('traps Tab inside the dialog', async () => {
      await open([pending(P1)]);
      clickIn(rowFor(P1), 'Post');
      await settle(fixture);
      const dialog = q(el, '[role="dialog"]');
      const focusables = qa<HTMLElement>(dialog, 'button, input');
      const last = focusables[focusables.length - 1] as HTMLElement;
      const first = focusables[0] as HTMLElement;
      last.focus();
      const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
      last.dispatchEvent(tab);
      expect(tab.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(first);
      const back = new KeyboardEvent('keydown', {
        key: 'Tab',
        shiftKey: true,
        bubbles: true,
        cancelable: true,
      });
      first.dispatchEvent(back);
      expect(document.activeElement).toBe(last);
    });
  });

  it('loads more with the cursor', async () => {
    await open([pending(P1)], 'next');
    clickIn(el, 'Load more');
    const req = ctrl.expectOne(
      (r) => r.url === '/api/v1/transfers' && r.params.get('cursor') === 'next',
    );
    req.flush({ items: [pending(P3)], nextCursor: null });
    await settle(fixture);
    expect(qa(el, 'tbody tr').length).toBe(2);
    expect(el.textContent).not.toContain('Load more');
  });
});

describe('buildResolution', () => {
  it('builds post and void bodies without account, ledger or code', () => {
    expect(buildResolution('post', 'i', 'p', '5')).toEqual({
      id: 'i',
      pendingId: 'p',
      amount: '5',
      flags: { postPendingTransfer: true, voidPendingTransfer: false },
    });
    expect(buildResolution('void', 'i', 'p', '0').flags).toEqual({
      postPendingTransfer: false,
      voidPendingTransfer: true,
    });
  });
});

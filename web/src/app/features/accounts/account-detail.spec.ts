import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Account, BalanceSnapshot, Transfer } from '../../api/models';
import { expectNoA11yViolations } from '../../../testing/axe';
import { clickButton, q, qa, settle } from '../../../testing/dom';
import {
  account,
  flushLedgers,
  http,
  pageTestProviders,
  problemBody,
  transfer,
} from '../../../testing/providers';
import { AccountDetail, directionFor, kindOf } from './account-detail';

const ME = '11111111-1111-4111-8111-111111111111';
const OTHER = '22222222-2222-4222-8222-222222222222';

const OUT = transfer({
  id: 't-out',
  debitAccountId: ME,
  creditAccountId: OTHER,
  amount: '300',
  seq: '2',
  reference: 'INV-1',
});
const IN = transfer({
  id: 't-in',
  debitAccountId: OTHER,
  creditAccountId: ME,
  amount: '1000',
  seq: '1',
});

function snap(n: number): BalanceSnapshot {
  return {
    transferId: `t${n}`,
    seq: String(n),
    timestamp: '2026-11-26T08:05:00Z',
    debitsPending: '0',
    debitsPosted: '300',
    creditsPending: '0',
    creditsPosted: '1000',
  };
}

describe('AccountDetail', () => {
  let fixture: ComponentFixture<AccountDetail>;
  let el: HTMLElement;
  let ctrl: HttpTestingController;

  async function open(
    acc: Account | 'not-found' = account({
      id: ME,
      debitsPosted: '300',
      creditsPosted: '1000',
      debitsPending: '100',
      creditsPending: '0',
      flags: {
        debitsMustNotExceedCredits: true,
        creditsMustNotExceedDebits: false,
        history: false,
        closed: false,
      },
    }),
    opts: {
      transfers?: Transfer[];
      transfersCursor?: string | null;
      history?: BalanceSnapshot[];
    } = {},
  ): Promise<void> {
    fixture = TestBed.createComponent(AccountDetail);
    fixture.componentRef.setInput('id', ME);
    el = fixture.nativeElement;
    ctrl = http();
    fixture.detectChanges();
    flushLedgers(ctrl);
    const req = ctrl.expectOne(`/api/v1/accounts/${ME}`);
    if (acc === 'not-found') {
      req.flush(problemBody('account_not_found', 404), { status: 404, statusText: 'Not Found' });
    } else {
      req.flush(acc);
      const t = ctrl.expectOne((r) => r.url === `/api/v1/accounts/${ME}/transfers`);
      t.flush({ items: opts.transfers ?? [OUT, IN], nextCursor: opts.transfersCursor ?? null });
      if (acc.flags.history) {
        ctrl
          .expectOne((r) => r.url === `/api/v1/accounts/${ME}/balances`)
          .flush({ items: opts.history ?? [snap(2), snap(1)], nextCursor: null });
      }
    }
    await settle(fixture);
  }

  beforeEach(() => TestBed.configureTestingModule({ providers: pageTestProviders() }));
  afterEach(() => ctrl.verify());

  it('has no automatically detectable accessibility violations', async () => {
    await open(
      account({
        id: ME,
        flags: {
          debitsMustNotExceedCredits: false,
          creditsMustNotExceedDebits: false,
          history: true,
          closed: false,
        },
      }),
    );
    await expectNoA11yViolations(el);
  });

  it('has no accessibility violations in the error state', async () => {
    await open('not-found');
    await expectNoA11yViolations(el);
  });

  it('shows the four counters, net, pending net and flags', async () => {
    await open();
    const stats = qa(el, '.stats > div').map(
      (d) => `${q(d, 'dt').textContent} ${q(d, 'dd').textContent}`,
    );
    expect(stats).toEqual([
      'Debits posted $3.00',
      'Debits pending $1.00',
      'Credits posted $10.00',
      'Credits pending $0.00',
      'Net (posted) $7.00',
      'Net (pending) -$1.00',
    ]);
    expect(el.textContent).toContain('Debits ≤ credits');
    expect(el.textContent).toContain('Net = credits − debits');
    expect(q(el, 'h1').textContent).toBe('Alice wallet');
  });

  it('shows transfers with direction relative to this account, newest first as served', async () => {
    await open();
    const rows = qa(el, 'tbody tr');
    expect(rows).toHaveLength(2);
    const out = rows[0] as HTMLElement;
    expect(out.textContent).toContain('Debit (out)');
    expect(out.textContent).toContain('−$3.00');
    expect(out.textContent).toContain('INV-1');
    expect(q(out, 'td a').getAttribute('href')).toBe(`/accounts/${OTHER}`);
    const inbound = rows[1] as HTMLElement;
    expect(inbound.textContent).toContain('Credit (in)');
    expect(inbound.textContent).toContain('+$10.00');
    expect(q(inbound, 'td a').getAttribute('href')).toBe(`/accounts/${OTHER}`);
  });

  it('conveys direction in words for screen readers, not just the sign', async () => {
    await open();
    expect(qa(el, 'tbody tr td.num')[0]?.querySelector('.sr-only')?.textContent).toContain('minus');
    expect(qa(el, 'tbody tr td.num')[1]?.querySelector('.sr-only')?.textContent).toContain('plus');
  });

  it('has a captioned transfers table with row headers', async () => {
    await open();
    expect(q(el, 'caption').textContent).toContain('Direction is relative to this account');
    expect(qa(el, 'thead th[scope="col"]').length).toBeGreaterThanOrEqual(6);
  });

  it('shows pending status and the kind of a two-phase transfer', async () => {
    await open(undefined, {
      transfers: [
        transfer({
          id: 'p',
          debitAccountId: ME,
          creditAccountId: OTHER,
          flags: {
            linked: false,
            pending: true,
            postPendingTransfer: false,
            voidPendingTransfer: false,
          },
          pendingStatus: 'PENDING',
        }),
      ],
    });
    const row = q(el, 'tbody tr');
    expect(row.textContent).toContain('Pending');
    expect(row.querySelector('.chip--muted')?.textContent).toBe('PENDING');
  });

  it('paginates transfers with the cursor', async () => {
    await open(undefined, { transfersCursor: 'next-page' });
    clickButton(el, 'Load more transfers');
    const req = ctrl.expectOne(
      (r) => r.url === `/api/v1/accounts/${ME}/transfers` && r.params.get('cursor') === 'next-page',
    );
    req.flush({
      items: [
        transfer({
          id: 'older',
          debitAccountId: OTHER,
          creditAccountId: ME,
          amount: '5',
          seq: '0',
        }),
      ],
      nextCursor: null,
    });
    await settle(fixture);
    expect(qa(el, 'tbody tr')).toHaveLength(3);
    expect(el.textContent).not.toContain('Load more transfers');
  });

  it('shows an empty state without transfers', async () => {
    await open(undefined, { transfers: [] });
    expect(el.textContent).toContain('No transfers touch this account yet');
  });

  describe('balance history', () => {
    const withHistory = account({
      id: ME,
      flags: {
        debitsMustNotExceedCredits: false,
        creditsMustNotExceedDebits: false,
        history: true,
        closed: false,
      },
    });

    it('shows the history table only for accounts with the history flag', async () => {
      await open(withHistory);
      expect(el.textContent).toContain('Balance history');
      const table = qa(el, 'table').find((t) =>
        t.textContent?.includes('Balance after each transfer'),
      );
      expect(table).toBeTruthy();
      expect(qa(table as HTMLElement, 'tbody tr')).toHaveLength(2);
      expect((table as HTMLElement).textContent).toContain('$7.00');
    });

    it('does not request history without the flag', async () => {
      await open();
      expect(el.textContent).not.toContain('Balance history');
      ctrl.expectNone((r) => r.url.endsWith('/balances'));
    });
  });

  describe('failure and loading states', () => {
    it('shows a loading state first', () => {
      fixture = TestBed.createComponent(AccountDetail);
      fixture.componentRef.setInput('id', ME);
      el = fixture.nativeElement;
      ctrl = http();
      fixture.detectChanges();
      expect(q(el, '[role="status"]').textContent).toContain('Loading account');
      flushLedgers(ctrl);
      ctrl
        .expectOne(`/api/v1/accounts/${ME}`)
        .flush(problemBody('account_not_found', 404), { status: 404, statusText: 'x' });
    });

    it('explains account_not_found in words with the raw code', async () => {
      await open('not-found');
      const alert = q(el, '[role="alert"]');
      expect(alert.textContent).toContain('That account does not exist');
      expect(q(alert, 'code.code').textContent).toBe('account_not_found');
    });

    it('shows a transfers error with retry', async () => {
      fixture = TestBed.createComponent(AccountDetail);
      fixture.componentRef.setInput('id', ME);
      el = fixture.nativeElement;
      ctrl = http();
      fixture.detectChanges();
      flushLedgers(ctrl);
      ctrl.expectOne(`/api/v1/accounts/${ME}`).flush(account({ id: ME }));
      ctrl
        .expectOne((r) => r.url.endsWith('/transfers'))
        .flush({}, { status: 500, statusText: 'x' });
      await settle(fixture);
      expect(el.textContent).toContain('transfers could not be loaded');
      clickButton(el, 'Try again');
      ctrl.expectOne((r) => r.url.endsWith('/transfers')).flush({ items: [], nextCursor: null });
    });

    it('reloads when the route id changes', async () => {
      await open();
      fixture.componentRef.setInput('id', OTHER);
      await settle(fixture);
      ctrl.expectOne(`/api/v1/accounts/${OTHER}`).flush(account({ id: OTHER, name: 'Bob wallet' }));
      ctrl
        .expectOne((r) => r.url === `/api/v1/accounts/${OTHER}/transfers`)
        .flush({ items: [], nextCursor: null });
      await settle(fixture);
      expect(q(el, 'h1').textContent).toBe('Bob wallet');
    });
  });
});

describe('transfer direction helpers', () => {
  it('computes direction and kind', () => {
    expect(directionFor(OUT, ME)).toBe('debit');
    expect(directionFor(IN, ME)).toBe('credit');
    expect(kindOf(OUT)).toBe('Transfer');
    expect(
      kindOf(
        transfer({
          flags: {
            linked: false,
            pending: true,
            postPendingTransfer: false,
            voidPendingTransfer: false,
          },
        }),
      ),
    ).toBe('Pending');
    expect(
      kindOf(
        transfer({
          flags: {
            linked: false,
            pending: false,
            postPendingTransfer: true,
            voidPendingTransfer: false,
          },
        }),
      ),
    ).toBe('Post');
    expect(
      kindOf(
        transfer({
          flags: {
            linked: false,
            pending: false,
            postPendingTransfer: false,
            voidPendingTransfer: true,
          },
        }),
      ),
    ).toBe('Void');
  });
});

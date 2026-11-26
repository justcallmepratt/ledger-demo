import { HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Account, Ledger } from '../../api/models';
import { expectNoA11yViolations } from '../../../testing/axe';
import { q, qa, setCheckbox, setInput, setSelect, settle, submitForm } from '../../../testing/dom';
import {
  account,
  http,
  pageTestProviders,
  problemBody,
  transfer,
} from '../../../testing/providers';
import { TransferForm } from './transfer-form';

const A1 = '11111111-1111-4111-8111-111111111111';
const A2 = '22222222-2222-4222-8222-222222222222';
const A3 = '33333333-3333-4333-8333-333333333333';
const A4 = '44444444-4444-4444-8444-444444444444';
const LEDGERS: Ledger[] = [
  { id: 840, currency: 'USD', assetScale: 2 },
  { id: 978, currency: 'EUR', assetScale: 2 },
  { id: 392, currency: 'JPY', assetScale: 0 },
];
const ACCOUNTS: Account[] = [
  account({ id: A1, name: 'Alice wallet' }),
  account({ id: A2, name: 'Bob wallet' }),
  account({ id: A3, name: 'Euro pot', ledger: 978 }),
  account({ id: A4, name: 'Yen pot', ledger: 392 }),
];

describe('TransferForm', () => {
  let fixture: ComponentFixture<TransferForm>;
  let el: HTMLElement;
  let ctrl: HttpTestingController;

  async function open(accounts: Account[] = ACCOUNTS): Promise<void> {
    fixture = TestBed.createComponent(TransferForm);
    el = fixture.nativeElement;
    ctrl = http();
    fixture.detectChanges();
    ctrl.expectOne('/api/v1/ledgers').flush(LEDGERS);
    ctrl
      .expectOne((r) => r.url === '/api/v1/accounts')
      .flush({ items: accounts, nextCursor: null });
    await settle(fixture);
  }

  function fill(over: { debit?: string; credit?: string; amount?: string } = {}): void {
    setSelect(el, '#tf-debit', over.debit ?? A1);
    fixture.detectChanges();
    setSelect(el, '#tf-credit', over.credit ?? A2);
    setInput(el, '#tf-amount', over.amount ?? '10.50');
    fixture.detectChanges();
  }

  function sendAndGetRequest(): TestRequest {
    submitForm(el);
    fixture.detectChanges();
    return ctrl.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/transfers');
  }

  beforeEach(() => TestBed.configureTestingModule({ providers: pageTestProviders() }));
  afterEach(() => ctrl.verify());

  it('has no automatically detectable accessibility violations', async () => {
    await open();
    await expectNoA11yViolations(el);
  });

  it('has no accessibility violations while showing errors and a result', async () => {
    await open();
    submitForm(el);
    await settle(fixture);
    await expectNoA11yViolations(el);
    fill();
    const req = sendAndGetRequest();
    req.flush(problemBody('exceeds_credits', 422), { status: 422, statusText: 'x' });
    await settle(fixture);
    await expectNoA11yViolations(el);
  });

  it('shows the empty state when fewer than two accounts exist', async () => {
    await open([ACCOUNTS[0] as Account]);
    expect(el.textContent).toContain('needs at least two accounts');
    expect(el.querySelector('form')).toBeNull();
  });

  it('shows an error with a retry when accounts cannot be loaded', async () => {
    fixture = TestBed.createComponent(TransferForm);
    el = fixture.nativeElement;
    ctrl = http();
    fixture.detectChanges();
    ctrl.expectOne('/api/v1/ledgers').flush(LEDGERS);
    ctrl
      .expectOne((r) => r.url === '/api/v1/accounts')
      .error(new ProgressEvent('error'), { status: 0 });
    await settle(fixture);
    expect(q(el, '[role="alert"]').textContent).toContain('could not be reached');
    expect(q(el, '[role="alert"] code.code').textContent).toBe('network_error');
  });

  describe('validation', () => {
    it('blocks an empty submit, shows errors tied to their fields, sends nothing', async () => {
      await open();
      submitForm(el);
      await settle(fixture);
      ctrl.expectNone('/api/v1/transfers');
      const debit = q(el, '#tf-debit');
      expect(debit.getAttribute('aria-invalid')).toBe('true');
      const describedBy = debit.getAttribute('aria-describedby') ?? '';
      expect(describedBy).toContain('tf-debit-error');
      expect(q(el, '#tf-debit-error').textContent).toContain('Debit account is required');
      expect(q(el, '#tf-credit-error').textContent).toContain('Credit account is required');
      expect(q(el, '#tf-amount-error').textContent).toContain('Amount is required');
      // Every referenced description id exists in the document.
      for (const input of qa(el, '[aria-describedby]')) {
        for (const id of (input.getAttribute('aria-describedby') ?? '').split(' ')) {
          expect(el.querySelector(`#${id}`), id).toBeTruthy();
        }
      }
    });

    it('asks for the debit account before judging an amount', async () => {
      await open();
      setInput(el, '#tf-amount', '5');
      fixture.detectChanges();
      expect(q(el, '#tf-amount-error').textContent).toContain('Choose a debit account first');
    });

    it('does not show errors before the user has touched a field or submitted', async () => {
      await open();
      expect(el.querySelector('.field__error')).toBeNull();
    });

    it.each([
      ['', 'Amount is required'],
      ['abc', 'digits'],
      ['-5', 'digits'],
      ['1,000', 'digits'],
      ['10.505', '2 decimal places'],
      ['0', 'greater than zero'],
      ['0.00', 'greater than zero'],
      ['92233720368547758.08', 'larger than the ledger can hold'],
    ])('rejects amount %j with a clear message', async (amount, message) => {
      await open();
      setSelect(el, '#tf-debit', A1);
      setInput(el, '#tf-amount', amount);
      fixture.detectChanges();
      expect(q(el, '#tf-amount-error').textContent).toContain(message);
      expect(q(el, '#tf-amount').getAttribute('aria-invalid')).toBe('true');
    });

    it('applies the scale of the debit ledger (JPY has no decimals)', async () => {
      await open([
        account({ id: A4, name: 'Yen pot', ledger: 392 }),
        account({ id: A3, name: 'Yen 2', ledger: 392 }),
      ]);
      setSelect(el, '#tf-debit', A4);
      setInput(el, '#tf-amount', '10.5');
      fixture.detectChanges();
      expect(q(el, '#tf-amount-error').textContent).toContain('no decimal places');
      setInput(el, '#tf-amount', '1050');
      fixture.detectChanges();
      expect(el.querySelector('#tf-amount-error')).toBeNull();
    });

    it('validates code and reference limits', async () => {
      await open();
      fill();
      setInput(el, '#tf-code', '0');
      setInput(el, '#tf-reference', 'x'.repeat(65));
      submitForm(el);
      await settle(fixture);
      ctrl.expectNone('/api/v1/transfers');
      expect(q(el, '#tf-code-error').textContent).toContain('at least 1');
      expect(q(el, '#tf-reference-error').textContent).toContain('at most 64');
      setInput(el, '#tf-code', '65536');
      fixture.detectChanges();
      expect(q(el, '#tf-code-error').textContent).toContain('at most 65535');
      setInput(el, '#tf-code', '1.5');
      fixture.detectChanges();
      expect(q(el, '#tf-code-error').textContent).toContain('whole number');
    });

    it('validates the timeout only for two-phase transfers', async () => {
      await open();
      fill();
      setInput(el, '#tf-code', '1');
      expect(el.querySelector('#tf-timeout')).toBeNull();
      setCheckbox(el, 'input[type="checkbox"]', true);
      fixture.detectChanges();
      setInput(el, '#tf-timeout', '-1');
      submitForm(el);
      await settle(fixture);
      ctrl.expectNone('/api/v1/transfers');
      expect(q(el, '#tf-timeout-error').textContent).toContain('at least 0');
    });
  });

  describe('same-ledger filtering', () => {
    it('lists only accounts of the debit account’s ledger, minus the debit account', async () => {
      await open();
      const options = () => qa<HTMLOptionElement>(el, '#tf-credit option').map((o) => o.value);
      // Before a debit account is chosen everything is listed.
      expect(options()).toEqual(['', A1, A2, A3, A4]);
      setSelect(el, '#tf-debit', A1);
      fixture.detectChanges();
      expect(options()).toEqual(['', A2]);
      setSelect(el, '#tf-debit', A3);
      fixture.detectChanges();
      expect(options()).toEqual(['']);
    });

    it('clears a credit account that no longer matches after the debit account changes', async () => {
      await open();
      fill();
      expect(q<HTMLSelectElement>(el, '#tf-credit').value).toBe(A2);
      setSelect(el, '#tf-debit', A3);
      fixture.detectChanges();
      expect(q<HTMLSelectElement>(el, '#tf-credit').value).toBe('');
    });
  });

  describe('sending', () => {
    it('sends the exact decimal-string amount, ledger and idempotency id', async () => {
      await open();
      fill({ amount: '10.5' });
      setInput(el, '#tf-reference', '  INV-42  ');
      fixture.detectChanges();
      const req = sendAndGetRequest();
      expect(req.request.body).toEqual({
        id: '00000000-0000-4000-8000-000000000001',
        debitAccountId: A1,
        creditAccountId: A2,
        amount: '1050',
        ledger: 840,
        code: 1,
        flags: { pending: false },
        timeoutSeconds: 0,
        reference: 'INV-42',
      });
      req.flush(transfer({ amount: '1050' }), { status: 201, statusText: 'Created' });
      await settle(fixture);
      const result = q(el, '#transfer-result');
      expect(result.textContent).toContain('Transfer recorded');
      expect(result.textContent).toContain('$10.50');
      expect(result.querySelector('code.code')?.textContent).toBe('created');
    });

    it('sends a very large amount without floating point loss', async () => {
      await open();
      fill({ amount: '92233720368547758.07' });
      const req = sendAndGetRequest();
      expect((req.request.body as { amount: string }).amount).toBe('9223372036854775807');
      req.flush(transfer(), { status: 201, statusText: 'Created' });
    });

    it('sends pending flags and the timeout for a two-phase transfer', async () => {
      await open();
      fill();
      setCheckbox(el, 'input[type="checkbox"]', true);
      fixture.detectChanges();
      setInput(el, '#tf-timeout', '90');
      const req = sendAndGetRequest();
      expect(req.request.body).toMatchObject({ flags: { pending: true }, timeoutSeconds: 90 });
      req.flush(transfer({ pendingStatus: 'PENDING' }), { status: 201, statusText: 'Created' });
      await settle(fixture);
      expect(q(el, '#transfer-result').textContent).toContain('PENDING');
      expect(q(el, '#transfer-result a[href="/transfers/pending"]')).toBeTruthy();
    });

    it('omits the reference when blank', async () => {
      await open();
      fill();
      const req = sendAndGetRequest();
      expect((req.request.body as { reference: unknown }).reference).toBeNull();
      req.flush(transfer(), { status: 201, statusText: 'Created' });
    });

    it('does not send twice while a request is in flight', async () => {
      await open();
      fill();
      const req = sendAndGetRequest();
      submitForm(el);
      fixture.detectChanges();
      ctrl.expectNone('/api/v1/transfers');
      expect(q(el, 'button[type="submit"]').hasAttribute('disabled')).toBe(true);
      req.flush(transfer(), { status: 201, statusText: 'Created' });
    });

    it('shows an idempotent replay (200) as already recorded', async () => {
      await open();
      fill();
      sendAndGetRequest().flush(transfer(), { status: 200, statusText: 'OK' });
      await settle(fixture);
      expect(q(el, '#transfer-result').textContent).toContain('Already recorded');
      expect(q(el, '#transfer-result code.code').textContent).toBe('exists');
    });
  });

  describe('idempotency key', () => {
    it('shows the transfer id and keeps it while the form is edited', async () => {
      await open();
      expect(el.textContent).toContain('00000000-0000-4000-8000-000000000001');
      fill();
      setInput(el, '#tf-amount', '3');
      expect(el.textContent).toContain('00000000-0000-4000-8000-000000000001');
    });

    it('reuses the same id when retrying after a network error', async () => {
      await open();
      fill();
      const first = sendAndGetRequest();
      const id = (first.request.body as { id: string }).id;
      first.error(new ProgressEvent('error'), { status: 0 });
      await settle(fixture);
      const result = q(el, '#transfer-result');
      expect(result.querySelector('code.code')?.textContent).toBe('network_error');
      expect(result.textContent).toContain('Press “Retry transfer”');
      expect(q(el, 'button[type="submit"]').textContent).toContain('Retry transfer');

      const second = sendAndGetRequest();
      expect((second.request.body as { id: string }).id).toBe(id);
      second.error(new ProgressEvent('error'), { status: 0 });
      await settle(fixture);
      const third = sendAndGetRequest();
      expect((third.request.body as { id: string }).id).toBe(id);
      third.flush(transfer({ id }), { status: 201, statusText: 'Created' });
    });

    it('reuses the id after a 5xx as well', async () => {
      await open();
      fill();
      const first = sendAndGetRequest();
      const id = (first.request.body as { id: string }).id;
      first.flush('oops', { status: 503, statusText: 'Unavailable' });
      await settle(fixture);
      expect(q(el, '#transfer-result code.code').textContent).toBe('server_error');
      expect((sendAndGetRequest().request.body as { id: string }).id).toBe(id);
    });

    it('keeps the id after a rejection so a corrected resubmit is the same attempt', async () => {
      await open();
      fill();
      const first = sendAndGetRequest();
      const id = (first.request.body as { id: string }).id;
      first.flush(problemBody('exceeds_credits', 422), { status: 422, statusText: 'x' });
      await settle(fixture);
      expect(q(el, '#transfer-result').textContent).not.toContain('Press “Retry transfer”');
      setInput(el, '#tf-amount', '1');
      expect((sendAndGetRequest().request.body as { id: string }).id).toBe(id);
    });

    it('generates a new id only after a success', async () => {
      await open();
      fill();
      const first = sendAndGetRequest();
      expect((first.request.body as { id: string }).id).toBe(
        '00000000-0000-4000-8000-000000000001',
      );
      first.flush(transfer(), { status: 201, statusText: 'Created' });
      await settle(fixture);
      expect(el.textContent).toContain('00000000-0000-4000-8000-000000000002');
      // The form keeps the accounts but clears amount and reference.
      expect(q<HTMLSelectElement>(el, '#tf-debit').value).toBe(A1);
      expect(q<HTMLInputElement>(el, '#tf-amount').value).toBe('');
      setInput(el, '#tf-amount', '2');
      const second = sendAndGetRequest();
      expect((second.request.body as { id: string }).id).toBe(
        '00000000-0000-4000-8000-000000000002',
      );
      second.flush(transfer(), { status: 201, statusText: 'Created' });
    });

    it('generates a new id after a replay success as well', async () => {
      await open();
      fill();
      sendAndGetRequest().flush(transfer(), { status: 200, statusText: 'OK' });
      await settle(fixture);
      expect(el.textContent).toContain('00000000-0000-4000-8000-000000000002');
    });

    it('"New transfer" deliberately regenerates the id and clears the form and result', async () => {
      await open();
      fill();
      const first = sendAndGetRequest();
      first.error(new ProgressEvent('error'), { status: 0 });
      await settle(fixture);
      const newButton = qa<HTMLButtonElement>(el, 'button').find(
        (b) => b.textContent?.trim() === 'New transfer',
      );
      newButton?.click();
      await settle(fixture);
      expect(el.textContent).toContain('00000000-0000-4000-8000-000000000002');
      expect(q<HTMLInputElement>(el, '#tf-amount').value).toBe('');
      expect(q<HTMLSelectElement>(el, '#tf-debit').value).toBe('');
      expect(q(el, '#transfer-result').textContent?.trim()).toBe('');
      expect(q(el, 'button[type="submit"]').textContent).toContain('Send transfer');
    });
  });

  describe('server result codes', () => {
    it.each([
      ['debit_account_not_found', 404, 'debit account does not exist'],
      ['credit_account_not_found', 404, 'credit account does not exist'],
      ['exceeds_credits', 422, 'does not have enough credits'],
      ['exceeds_debits', 422, 'exceed the debits'],
      ['debit_account_closed', 422, 'debit account is closed'],
      ['transfer_must_have_same_ledger_as_accounts', 422, 'same ledger'],
      ['overflows_balance', 422, 'overflow'],
      ['amount_must_be_positive', 400, 'greater than zero'],
      ['exists_with_different_fields', 409, 'different details'],
    ])('explains %s in words and shows the raw code', async (code, status, words) => {
      await open();
      fill();
      sendAndGetRequest().flush(problemBody(code, status), { status, statusText: 'x' });
      await settle(fixture);
      const result = q(el, '#transfer-result');
      expect(result.textContent).toContain(words);
      expect(result.querySelector('code.code')?.textContent).toBe(code);
      expect(result.textContent).toContain('Transfer not recorded');
    });

    it('lists server-side validation errors by field', async () => {
      await open();
      fill();
      sendAndGetRequest().flush(
        problemBody('invalid_request', 400, {
          errors: [{ field: 'reference', message: 'too long' }],
        }),
        { status: 400, statusText: 'x' },
      );
      await settle(fixture);
      expect(q(el, '#transfer-result li').textContent).toContain('reference: too long');
    });

    it('announces results through a polite live region', async () => {
      await open();
      const region = q(el, '#transfer-result');
      expect(region.getAttribute('aria-live')).toBe('polite');
      expect(region.getAttribute('aria-atomic')).toBe('true');
    });
  });
});

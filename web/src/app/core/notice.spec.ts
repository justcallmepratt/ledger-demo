import { TestBed } from '@angular/core/testing';
import { NOTICE_TTL_MS, NoticeService } from './notice.service';
import { NoticeRegion } from '../shared/notice-region';
import { expectNoA11yViolations } from '../../testing/axe';

describe('NoticeService', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('auto-dismisses success and info notices but keeps errors until dismissed', () => {
    const n = TestBed.inject(NoticeService);
    n.success('ok');
    n.info('fyi');
    const errorId = n.error('bad', 'exceeds_credits');
    expect(n.notices().map((x) => x.kind)).toEqual(['success', 'info', 'error']);
    vi.advanceTimersByTime(NOTICE_TTL_MS + 1);
    expect(n.notices().map((x) => x.kind)).toEqual(['error']);
    n.dismiss(errorId);
    expect(n.notices()).toEqual([]);
  });

  it('keeps at most five notices', () => {
    const n = TestBed.inject(NoticeService);
    for (let i = 0; i < 8; i++) n.error(`e${i}`);
    expect(n.notices()).toHaveLength(5);
    expect(n.notices()[0]?.message).toBe('e3');
  });
});

describe('NoticeRegion', () => {
  it('renders into a polite live region with the raw code in monospace, and dismisses', async () => {
    const fixture = TestBed.createComponent(NoticeRegion);
    const n = TestBed.inject(NoticeService);
    fixture.detectChanges();
    const live = fixture.nativeElement.querySelector('[aria-live="polite"]') as HTMLElement;
    expect(live).toBeTruthy();
    n.error('Not enough funds.', 'exceeds_credits');
    n.success('Saved.');
    fixture.detectChanges();
    expect(live.textContent).toContain('Not enough funds.');
    expect(live.querySelector('code.code')?.textContent).toBe('exceeds_credits');
    expect(live.textContent).toContain('Error');
    expect(live.textContent).toContain('Done');
    await expectNoA11yViolations(fixture.nativeElement);
    (live.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(live.textContent).not.toContain('Not enough funds.');
  });
});

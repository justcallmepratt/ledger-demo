import { formatRemaining, formatTimestamp } from './time';

describe('formatRemaining', () => {
  it.each([
    [0, '0s'],
    [-5000, '0s'],
    [999, '0s'],
    [1000, '1s'],
    [59_000, '59s'],
    [60_000, '1m 00s'],
    [125_000, '2m 05s'],
    [3_600_000, '1h 00m 00s'],
    [3_723_000, '1h 02m 03s'],
    [90_061_000, '25h 01m 01s'],
  ])('formats %i ms as %s', (ms, expected) => {
    expect(formatRemaining(ms)).toBe(expected);
  });
});

describe('formatTimestamp', () => {
  it('shows a UTC timestamp', () => {
    expect(formatTimestamp('2026-11-26T08:00:00Z')).toBe('26 Nov 2026, 08:00:00 UTC');
  });

  it('returns unparseable input unchanged', () => {
    expect(formatTimestamp('yesterday')).toBe('yesterday');
  });
});

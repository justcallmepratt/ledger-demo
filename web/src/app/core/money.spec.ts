import {
  MAX_MINOR,
  MoneyParseError,
  addMinor,
  compareMinor,
  currencySymbol,
  formatMinor,
  isMinorString,
  minorEquals,
  netMinor,
  parseMajor,
  parseMinor,
  tryParseMajor,
} from './money';

/** Small deterministic PRNG (mulberry32) so property loops are reproducible. */
function prng(seed: number): () => number {
  let a = seed;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function randomMinor(rand: () => number, maxDigits = 19): string {
  const len = 1 + Math.floor(rand() * maxDigits);
  let s = String(1 + Math.floor(rand() * 9));
  for (let i = 1; i < len; i++) s += Math.floor(rand() * 10);
  return BigInt(s) > MAX_MINOR ? MAX_MINOR.toString() : s;
}

function reason(fn: () => unknown): string {
  try {
    fn();
  } catch (e) {
    if (e instanceof MoneyParseError) return e.reason;
    throw e;
  }
  return 'no error';
}

describe('formatMinor', () => {
  it('formats the documented example', () => {
    expect(formatMinor('1050', 2, 'USD')).toBe('$10.50');
  });

  it.each([
    ['0', 2, '$0.00'],
    ['1', 2, '$0.01'],
    ['9', 2, '$0.09'],
    ['10', 2, '$0.10'],
    ['100', 2, '$1.00'],
    ['999', 2, '$9.99'],
    ['100000', 2, '$1,000.00'],
    ['123456789', 2, '$1,234,567.89'],
    ['5', 0, '$5'],
    ['1500', 0, '$1,500'],
    ['5', 3, '$0.005'],
    ['1', 8, '$0.00000001'],
    ['007', 2, '$0.07'],
  ])('formats %s at scale %i as %s', (minor, scale, expected) => {
    expect(formatMinor(minor, scale, 'USD')).toBe(expected);
  });

  it('shows negative balances with a minus sign', () => {
    expect(formatMinor('-1050', 2, 'USD')).toBe('-$10.50');
    expect(formatMinor('-1', 2, 'USD')).toBe('-$0.01');
    expect(formatMinor('-100000', 0, 'USD')).toBe('-$100,000');
  });

  it('never shows negative zero', () => {
    expect(formatMinor('-0', 2, 'USD')).toBe('$0.00');
  });

  it('is exact on the signed 64-bit extremes (no floating point)', () => {
    expect(formatMinor('9223372036854775807', 2, 'USD')).toBe('$92,233,720,368,547,758.07');
    expect(formatMinor('-9223372036854775808', 2, 'USD')).toBe('-$92,233,720,368,547,758.08');
    // Number(9007199254740993) would round to ...992.
    expect(formatMinor('9007199254740993', 0, 'USD')).toBe('$9,007,199,254,740,993');
  });

  it('supports other currencies and falls back to the code for unknown ones', () => {
    expect(formatMinor('1050', 2, 'EUR')).toBe('€10.50');
    expect(formatMinor('1050', 2, 'GBP')).toBe('£10.50');
    expect(formatMinor('1050', 2, 'ZZZ')).toBe('ZZZ 10.50');
    expect(formatMinor('1050', 2, 'not a code')).toBe('not a code10.50');
  });

  it('throws on malformed minor strings and bad scales', () => {
    for (const bad of ['', '1.5', '1e3', ' 10', '10 ', '--1', '+1', 'abc', '0x10', '１２']) {
      expect(() => formatMinor(bad, 2, 'USD')).toThrow(RangeError);
    }
    expect(() => formatMinor('1', -1, 'USD')).toThrow(RangeError);
    expect(() => formatMinor('1', 19, 'USD')).toThrow(RangeError);
    expect(() => formatMinor('1', 1.5, 'USD')).toThrow(RangeError);
  });

  it('keeps the digits of random values (property loop)', () => {
    const rand = prng(42);
    for (let i = 0; i < 2000; i++) {
      const scale = Math.floor(rand() * 9);
      const minor = randomMinor(rand);
      const negative = rand() < 0.3;
      const out = formatMinor(negative ? `-${minor}` : minor, scale, 'USD');
      const digits = out.replace(/[^0-9]/g, '').replace(/^0+(?=.)/, '');
      expect(digits).toBe(minor.padStart(scale + 1, '0').replace(/^0+(?=.)/, ''));
      expect(out.startsWith('-')).toBe(negative);
      if (scale > 0) expect(out).toMatch(new RegExp(`\\.\\d{${scale}}$`));
      else expect(out).not.toContain('.');
    }
  });
});

describe('parseMajor', () => {
  it('parses the documented example', () => {
    expect(parseMajor('10.5', 2)).toBe('1050');
  });

  it.each([
    ['10', 2, '1000'],
    ['10.50', 2, '1050'],
    ['0.01', 2, '1'],
    ['.5', 2, '50'],
    ['0.5', 2, '50'],
    ['007.5', 2, '750'],
    ['  12.34  ', 2, '1234'],
    ['5', 0, '5'],
    ['0.123', 3, '123'],
    ['1.00000001', 8, '100000001'],
    ['92233720368547758.07', 2, '9223372036854775807'],
    ['9223372036854775807', 0, '9223372036854775807'],
  ])('parses %j at scale %i to %s', (input, scale, expected) => {
    expect(parseMajor(input, scale)).toBe(expected);
  });

  it('rejects empty input', () => {
    expect(reason(() => parseMajor('', 2))).toBe('empty');
    expect(reason(() => parseMajor('   ', 2))).toBe('empty');
  });

  it.each([
    'abc',
    '1,000',
    '1 000',
    '-5',
    '+5',
    '1e3',
    '1.2.3',
    '10.',
    '.',
    '1_0',
    '$5',
    '５',
    '0x1f',
    '١٢٣',
  ])('rejects non-digit input %j', (input) => {
    expect(reason(() => parseMajor(input, 2))).toBe('format');
  });

  it('rejects more decimals than the scale, even trailing zeros', () => {
    expect(reason(() => parseMajor('10.505', 2))).toBe('too_many_decimals');
    expect(reason(() => parseMajor('10.500', 2))).toBe('too_many_decimals');
    expect(reason(() => parseMajor('1.0', 0))).toBe('too_many_decimals');
    expect(reason(() => parseMajor('0.1', 0))).toBe('too_many_decimals');
  });

  it('rejects zero unless allowed', () => {
    for (const z of ['0', '0.00', '000', '.00', '0.0']) {
      expect(reason(() => parseMajor(z, 2))).toBe('zero');
    }
    expect(parseMajor('0', 2, { allowZero: true })).toBe('0');
    expect(parseMajor('0.00', 2, { allowZero: true })).toBe('0');
  });

  it('rejects values above the signed 64-bit maximum', () => {
    expect(reason(() => parseMajor('92233720368547758.08', 2))).toBe('too_large');
    expect(reason(() => parseMajor('9223372036854775808', 0))).toBe('too_large');
    expect(reason(() => parseMajor('99999999999999999999999999', 2))).toBe('too_large');
    expect(reason(() => parseMajor('9223372036854775808', 0, { allowZero: true }))).toBe(
      'too_large',
    );
  });

  it('rejects an invalid scale', () => {
    expect(reason(() => parseMajor('1', -1))).toBe('bad_scale');
    expect(reason(() => parseMajor('1', 19))).toBe('bad_scale');
    expect(reason(() => parseMajor('1', 0.5))).toBe('bad_scale');
  });

  it('gives person-readable messages', () => {
    expect(() => parseMajor('abc', 2)).toThrow(/digits/);
    expect(() => parseMajor('1.234', 2)).toThrow(/2 decimal places/);
    expect(() => parseMajor('1.2', 0)).toThrow(/no decimal places/);
    expect(() => parseMajor('1.22', 1)).toThrow(/1 decimal place\b/);
  });

  it('tryParseMajor returns null instead of throwing', () => {
    expect(tryParseMajor('1.5', 2)).toBe('150');
    expect(tryParseMajor('x', 2)).toBeNull();
    expect(tryParseMajor('0', 2)).toBeNull();
    expect(tryParseMajor('0', 2, { allowZero: true })).toBe('0');
  });

  it('round-trips formatted random values (property loop)', () => {
    const rand = prng(7);
    for (let i = 0; i < 3000; i++) {
      const scale = Math.floor(rand() * 10);
      const minor = randomMinor(rand);
      const shown = formatMinor(minor, scale, 'USD').replace(/^\$/, '').replace(/,/g, '');
      expect(parseMajor(shown, scale)).toBe(minor);
    }
  });

  it('rejects any string with a stray character inserted (property loop)', () => {
    const rand = prng(99);
    const junk = ['a', ' ', ',', '-', '+', 'e', '$', '..', '_'];
    for (let i = 0; i < 1000; i++) {
      const scale = 2;
      const good = formatMinor(randomMinor(rand, 12), scale, 'USD')
        .replace(/^\$/, '')
        .replace(/,/g, '');
      const at = 1 + Math.floor(rand() * (good.length - 1));
      const j = junk[Math.floor(rand() * junk.length)] as string;
      const bad = good.slice(0, at) + j + good.slice(at);
      // Interior whitespace is invalid too; only outer whitespace is trimmed.
      expect(tryParseMajor(bad, scale)).toBeNull();
    }
  });

  it('accepts every value up to the maximum and nothing above (boundary loop)', () => {
    for (let scale = 0; scale <= 8; scale++) {
      const max = formatMinor(MAX_MINOR.toString(), scale, 'USD')
        .replace(/^\$/, '')
        .replace(/,/g, '');
      expect(parseMajor(max, scale)).toBe(MAX_MINOR.toString());
      const over = formatMinor((MAX_MINOR + 1n).toString(), scale, 'USD')
        .replace(/^\$/, '')
        .replace(/,/g, '');
      expect(reason(() => parseMajor(over, scale))).toBe('too_large');
    }
  });
});

describe('minor-unit arithmetic', () => {
  it('validates minor strings', () => {
    expect(isMinorString('0')).toBe(true);
    expect(isMinorString('-12')).toBe(true);
    expect(isMinorString('1.2')).toBe(false);
    expect(isMinorString(12)).toBe(false);
    expect(isMinorString('')).toBe(false);
  });

  it('parses to bigint and throws on junk', () => {
    expect(parseMinor('9223372036854775807')).toBe(MAX_MINOR);
    expect(() => parseMinor('1.0')).toThrow(RangeError);
  });

  it('computes net = credits - debits exactly, including negatives and beyond 2^63', () => {
    expect(netMinor('1000', '250')).toBe('750');
    expect(netMinor('250', '1000')).toBe('-750');
    expect(netMinor('0', '0')).toBe('0');
    expect(netMinor('9223372036854775807', '0')).toBe('9223372036854775807');
    expect(netMinor('9223372036854775807', '9223372036854775807')).toBe('0');
    expect(netMinor('0', '9223372036854775807')).toBe('-9223372036854775807');
    expect(netMinor('9007199254740993', '1')).toBe('9007199254740992');
  });

  it('net and add are consistent (property loop)', () => {
    const rand = prng(1234);
    for (let i = 0; i < 1500; i++) {
      const a = randomMinor(rand);
      const b = randomMinor(rand);
      expect(addMinor(netMinor(a, b), b)).toBe(a);
      expect(netMinor(a, b)).toBe((BigInt(a) - BigInt(b)).toString());
    }
  });

  it('compares and equates numerically', () => {
    expect(compareMinor('2', '10')).toBe(-1);
    expect(compareMinor('10', '2')).toBe(1);
    expect(compareMinor('10', '10')).toBe(0);
    expect(compareMinor('-5', '3')).toBe(-1);
    expect(minorEquals('007', '7')).toBe(true);
    expect(minorEquals('7', '8')).toBe(false);
  });

  it('currencySymbol caches and appends a separator only for alphabetic codes', () => {
    expect(currencySymbol('USD')).toBe('$');
    expect(currencySymbol('USD')).toBe('$');
    expect(currencySymbol('XYZ')).toBe('XYZ ');
  });
});

/**
 * Money helpers. Amounts are decimal integer strings in minor units (API.md). They are
 * parsed with BigInt and are never passed through Number or parseFloat.
 */

/** Largest value of a signed 64-bit integer, the ledger's counter and amount limit. */
export const MAX_MINOR = 9223372036854775807n;

const MINOR_RE = /^-?\d+$/;
const MAJOR_RE = /^(\d*)(?:\.(\d+))?$/;

export type MoneyParseReason =
  'empty' | 'format' | 'too_many_decimals' | 'zero' | 'too_large' | 'bad_scale';

/** Thrown by parseMajor. `reason` is stable, `message` is safe to show to a person. */
export class MoneyParseError extends Error {
  constructor(
    readonly reason: MoneyParseReason,
    message: string,
  ) {
    super(message);
    this.name = 'MoneyParseError';
  }
}

export function isValidScale(scale: number): boolean {
  return Number.isInteger(scale) && scale >= 0 && scale <= 18;
}

/** True when `value` is a decimal integer string (optional leading minus). */
export function isMinorString(value: unknown): value is string {
  return typeof value === 'string' && MINOR_RE.test(value);
}

/** Parses a minor-unit string to a bigint. Throws RangeError on anything else. */
export function parseMinor(minor: string): bigint {
  if (!isMinorString(minor)) {
    throw new RangeError(`Not a minor-unit integer string: "${minor}"`);
  }
  return BigInt(minor);
}

/** Strings equal as numbers, ignoring leading zeros ("007" vs "7"). */
export function minorEquals(a: string, b: string): boolean {
  return parseMinor(a) === parseMinor(b);
}

export function compareMinor(a: string, b: string): -1 | 0 | 1 {
  const x = parseMinor(a);
  const y = parseMinor(b);
  return x < y ? -1 : x > y ? 1 : 0;
}

/** credits − debits, as a minor-unit string. Never overflows (BigInt). */
export function netMinor(credits: string, debits: string): string {
  return (parseMinor(credits) - parseMinor(debits)).toString();
}

export function addMinor(a: string, b: string): string {
  return (parseMinor(a) + parseMinor(b)).toString();
}

const symbolCache = new Map<string, string>();

/** The display symbol for an ISO 4217 code ("USD" -> "$"), or the code itself if unknown. */
export function currencySymbol(currency: string): string {
  const cached = symbolCache.get(currency);
  if (cached !== undefined) return cached;
  let symbol = currency;
  try {
    const part = new Intl.NumberFormat('en-US', { style: 'currency', currency })
      .formatToParts(0)
      .find((p) => p.type === 'currency');
    if (part) symbol = part.value;
  } catch {
    // Not a valid currency code: show it verbatim.
  }
  // A code shown verbatim needs a separator ("XTS 1.00"); a symbol does not ("$1.00").
  const out = /^[A-Za-z]+$/.test(symbol) ? `${symbol}\u00a0` : symbol;
  symbolCache.set(currency, out);
  return out;
}

function groupThousands(digits: string): string {
  let out = '';
  for (let i = 0; i < digits.length; i++) {
    if (i > 0 && (digits.length - i) % 3 === 0) out += ',';
    out += digits[i];
  }
  return out;
}

/**
 * formatMinor("1050", 2, "USD") -> "$10.50". Negative values get a leading minus
 * ("-$10.50"). Works on the full signed 64-bit range and beyond.
 */
export function formatMinor(minor: string, assetScale: number, currency: string): string {
  if (!isValidScale(assetScale)) {
    throw new RangeError(`Invalid asset scale: ${assetScale}`);
  }
  const value = parseMinor(minor);
  const negative = value < 0n;
  const digits = (negative ? -value : value).toString().padStart(assetScale + 1, '0');
  const whole = digits.slice(0, digits.length - assetScale);
  const fraction = digits.slice(digits.length - assetScale);
  const body = groupThousands(whole) + (assetScale > 0 ? `.${fraction}` : '');
  return `${negative ? '-' : ''}${currencySymbol(currency)}${body}`;
}

export interface ParseMajorOptions {
  /** Accept 0 (used where 0 means "the full amount"). Default false. */
  allowZero?: boolean;
}

/**
 * parseMajor("10.5", 2) -> "1050". Accepts digits with an optional decimal part of at most
 * `assetScale` digits. Rejects empty input, signs, separators, exponents, more decimals
 * than the scale, zero (unless allowZero) and anything above 9223372036854775807.
 */
export function parseMajor(
  input: string,
  assetScale: number,
  options: ParseMajorOptions = {},
): string {
  if (!isValidScale(assetScale)) {
    throw new MoneyParseError('bad_scale', 'This ledger has an invalid asset scale.');
  }
  const text = input.trim();
  if (text === '') throw new MoneyParseError('empty', 'Enter an amount.');
  const match = MAJOR_RE.exec(text);
  if (!match || (match[1] === '' && match[2] === undefined)) {
    throw new MoneyParseError('format', 'Use digits and an optional decimal point, e.g. 10.50.');
  }
  const whole = match[1] ?? '';
  const fraction = match[2] ?? '';
  if (fraction.length > assetScale) {
    throw new MoneyParseError(
      'too_many_decimals',
      assetScale === 0
        ? 'This ledger has no decimal places.'
        : `At most ${assetScale} decimal place${assetScale === 1 ? '' : 's'} are allowed.`,
    );
  }
  const minor = BigInt((whole || '0') + fraction.padEnd(assetScale, '0'));
  if (minor === 0n && !options.allowZero) {
    throw new MoneyParseError('zero', 'The amount must be greater than zero.');
  }
  if (minor > MAX_MINOR) {
    throw new MoneyParseError('too_large', 'The amount is larger than the ledger can hold.');
  }
  return minor.toString();
}

/** Like parseMajor but returns null instead of throwing. */
export function tryParseMajor(
  input: string,
  assetScale: number,
  options: ParseMajorOptions = {},
): string | null {
  try {
    return parseMajor(input, assetScale, options);
  } catch (e) {
    if (e instanceof MoneyParseError) return null;
    throw e;
  }
}

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const scss = readFileSync(join(process.cwd(), 'src/styles/_tokens.scss'), 'utf8');

function token(name: string): string {
  const m = new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})`).exec(scss);
  if (!m) throw new Error(`token --${name} not found`);
  return (m[1] as string).toLowerCase();
}

function luminance(hex: string): number {
  const ch = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * (ch[0] as number) + 0.7152 * (ch[1] as number) + 0.0722 * (ch[2] as number);
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}

const SURFACES = ['cp-dark', 'cp-panel', 'cp-surface'];
const TEXT = ['cp-red', 'cp-teal', 'cp-yellow', 'cp-muted', 'cp-text', 'cp-bright'];

describe('design tokens (WCAG 2.1 AA)', () => {
  it('uses the personal site palette', () => {
    expect(token('cp-red')).toBe('#ff2e57');
    expect(token('cp-red-deep')).toBe('#c5003c');
    expect(token('cp-teal')).toBe('#55ead4');
    expect(token('cp-yellow')).toBe('#f3e600');
    expect(token('cp-dark')).toBe('#000000');
    expect(token('cp-panel')).toBe('#0a0a10');
    expect(token('cp-border')).toBe('#1e2235');
    expect(token('cp-border-strong')).toBe('#5f6688');
    expect(token('cp-muted')).toBe('#7a82a2');
    expect(token('cp-text')).toBe('#c8cdd8');
    expect(token('cp-focus')).toBe('#ffffff');
  });

  it.each(TEXT.flatMap((t) => SURFACES.map((s) => [t, s] as const)))(
    '%s text on %s is at least 4.5:1',
    (fg, bg) => {
      expect(contrast(token(fg), token(bg))).toBeGreaterThanOrEqual(4.5);
    },
  );

  it.each(SURFACES)('control outlines (cp-border-strong) on %s are at least 3:1', (bg) => {
    expect(contrast(token('cp-border-strong'), token(bg))).toBeGreaterThanOrEqual(3);
    expect(contrast(token('cp-border-strong'), '#000000')).toBeGreaterThanOrEqual(3);
  });

  it.each(SURFACES)('the focus ring on %s is at least 3:1', (bg) => {
    expect(contrast(token('cp-focus'), token(bg))).toBeGreaterThanOrEqual(3);
  });

  it('white text on the red-deep fill is at least 4.5:1', () => {
    expect(contrast('#ffffff', token('cp-red-deep'))).toBeGreaterThanOrEqual(4.5);
  });

  it('keeps the site note: red-deep is not text-safe on black', () => {
    expect(contrast(token('cp-red-deep'), token('cp-dark'))).toBeLessThan(4.5);
  });

  it('documents the measured ratios the source comment claims', () => {
    expect(contrast(token('cp-red'), '#000000')).toBeCloseTo(5.78, 1);
    expect(contrast(token('cp-muted'), '#000000')).toBeCloseTo(5.54, 1);
    expect(contrast(token('cp-border-strong'), '#000000')).toBeCloseTo(3.74, 1);
  });

  it('declares the three self-hosted font families', () => {
    expect(scss).toContain("--font-ui: 'Rajdhani'");
    expect(scss).toContain("--font-display: 'Orbitron'");
    expect(scss).toContain("--font-mono: 'Share Tech Mono'");
  });
});

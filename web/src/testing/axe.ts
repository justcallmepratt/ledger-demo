import axe from 'axe-core';

/**
 * Runs axe on a rendered element. jsdom has no layout or paint, so the colour-contrast rule
 * cannot run; contrast is asserted from the design tokens in styles/tokens.spec.ts instead.
 * The `region` rule is off because a lone component is not a whole page.
 */
export async function expectNoA11yViolations(el: Element): Promise<void> {
  const results = await axe.run(el, {
    rules: { 'color-contrast': { enabled: false }, region: { enabled: false } },
  });
  const summary = results.violations.map(
    (v) => `${v.id}: ${v.help}\n${v.nodes.map((n) => `  ${n.html}`).join('\n')}`,
  );
  expect(summary).toEqual([]);
}

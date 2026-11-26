import { ComponentFixture } from '@angular/core/testing';

export function q<T extends HTMLElement = HTMLElement>(root: ParentNode, selector: string): T {
  const el = root.querySelector<T>(selector);
  if (!el) throw new Error(`No element matches ${selector}`);
  return el;
}

export function qa<T extends HTMLElement = HTMLElement>(root: ParentNode, selector: string): T[] {
  return Array.from(root.querySelectorAll<T>(selector));
}

export function setInput(root: ParentNode, selector: string, value: string): void {
  const el = q<HTMLInputElement>(root, selector);
  el.value = value;
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('blur', { bubbles: true }));
}

export function setSelect(root: ParentNode, selector: string, value: string): void {
  const el = q<HTMLSelectElement>(root, selector);
  el.value = value;
  el.dispatchEvent(new Event('change', { bubbles: true }));
  el.dispatchEvent(new Event('blur', { bubbles: true }));
}

export function setCheckbox(root: ParentNode, selector: string, checked: boolean): void {
  const el = q<HTMLInputElement>(root, selector);
  el.checked = checked;
  el.dispatchEvent(new Event('change', { bubbles: true }));
}

export function clickButton(root: ParentNode, text: RegExp | string): void {
  const buttons = qa<HTMLElement>(root, 'button, a');
  const match = buttons.find((b) =>
    typeof text === 'string' ? b.textContent?.trim() === text : text.test(b.textContent ?? ''),
  );
  if (!match) throw new Error(`No button matches ${String(text)}`);
  match.click();
}

export function submitForm(root: ParentNode): void {
  q<HTMLFormElement>(root, 'form').dispatchEvent(
    new Event('submit', { bubbles: true, cancelable: true }),
  );
}

/** Runs change detection until the zoneless fixture settles. */
export async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
}

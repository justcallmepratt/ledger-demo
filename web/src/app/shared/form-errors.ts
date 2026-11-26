import { AbstractControl, ValidationErrors } from '@angular/forms';

/** Validators report their own sentence under `message`; the built-ins get generic wording. */
export function errorText(control: AbstractControl | null, label: string): string | null {
  const errors: ValidationErrors | null = control?.errors ?? null;
  if (!errors) return null;
  const custom = errors['message'];
  if (typeof custom === 'string') return custom;
  if (errors['required']) return `${label} is required.`;
  if (errors['min']) return `${label} must be at least ${(errors['min'] as { min: number }).min}.`;
  if (errors['max']) return `${label} must be at most ${(errors['max'] as { max: number }).max}.`;
  if (errors['minlength']) {
    return `${label} must have at least ${(errors['minlength'] as { requiredLength: number }).requiredLength} characters.`;
  }
  if (errors['maxlength']) {
    return `${label} must have at most ${(errors['maxlength'] as { requiredLength: number }).requiredLength} characters.`;
  }
  return `${label} is not valid.`;
}

/** Value for aria-describedby: the hint and/or the error text elements of a field. */
export function describedBy(fieldId: string, hasHint: boolean, hasError: boolean): string | null {
  const ids = [hasHint ? `${fieldId}-hint` : null, hasError ? `${fieldId}-error` : null].filter(
    (x): x is string => x !== null,
  );
  return ids.length > 0 ? ids.join(' ') : null;
}

/** Integer validator for number inputs (the typed value is number | null). */
export function integerValidator(label: string) {
  return (control: AbstractControl): ValidationErrors | null => {
    const v: unknown = control.value;
    if (v === null || v === '' || v === undefined) return null;
    return typeof v === 'number' && Number.isInteger(v)
      ? null
      : { message: `${label} must be a whole number.` };
  };
}

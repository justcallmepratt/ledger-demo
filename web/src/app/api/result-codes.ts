import { ApiProblem, CLIENT_CODES } from './api-problem';

/** Human wording for every result code in docs/API.md (plus client-side failure codes). */
export const RESULT_MESSAGES: Readonly<Record<string, string>> = {
  created: 'The transfer was recorded.',
  exists: 'This transfer was already recorded. Nothing changed.',
  exists_with_different_fields:
    'A transfer with this id already exists but with different details. Start a new transfer.',
  id_must_not_be_nil: 'The transfer id must not be the all-zero id.',
  flags_are_mutually_exclusive:
    'These transfer options cannot be combined (pending, post and void are mutually exclusive).',
  amount_must_be_positive: 'The amount must be greater than zero.',
  accounts_must_be_different: 'The debit and credit accounts must be different.',
  pending_id_required: 'Posting or voiding needs the id of the pending transfer.',
  pending_id_must_be_null: 'Only a post or void may reference a pending transfer.',
  timeout_reserved_for_pending: 'A timeout can only be set on a pending (two-phase) transfer.',
  linked_not_allowed: 'Linked transfers are only allowed in a batch.',
  invalid_request: 'The request was not valid. Check the highlighted fields.',
  debit_account_not_found: 'The debit account does not exist.',
  credit_account_not_found: 'The credit account does not exist.',
  pending_transfer_not_found: 'The pending transfer does not exist.',
  ledger_must_match: 'The transfer ledger must match the ledger of the pending transfer.',
  transfer_must_have_same_ledger_as_accounts:
    'Both accounts must belong to the same ledger as the transfer.',
  pending_transfer_not_pending: 'That transfer is not a pending transfer.',
  pending_transfer_already_posted: 'The pending transfer was already posted.',
  pending_transfer_already_voided: 'The pending transfer was already voided.',
  pending_transfer_expired:
    'The pending transfer has expired and can no longer be posted or voided.',
  exceeds_pending_transfer_amount: 'The amount is more than the pending transfer holds.',
  debit_account_closed: 'The debit account is closed.',
  credit_account_closed: 'The credit account is closed.',
  exceeds_credits: 'The debit account does not have enough credits to cover this amount.',
  exceeds_debits: 'The credit account would exceed the debits it is allowed to carry.',
  overflows_balance: 'This amount would overflow an account balance.',
  linked_event_failed:
    'Another transfer in the same linked chain failed, so this one was not applied.',
  linked_event_chain_open: 'The linked chain was never closed, so it was not applied.',
  account_not_found: 'That account does not exist.',
  transfer_not_found: 'That transfer does not exist.',
  history_not_enabled: 'Balance history is not enabled for this account.',
  [CLIENT_CODES.network]: 'The server could not be reached. Check your connection and try again.',
  [CLIENT_CODES.server]: 'The server had a problem. Try again in a moment.',
  [CLIENT_CODES.unknown]: 'Something unexpected went wrong.',
};

const DIFFERENT_PREFIX = 'pending_transfer_has_different_';
const EXISTS_DIFFERENT_PREFIX = 'exists_with_different_';

function words(field: string): string {
  return field.replace(/_/g, ' ');
}

/** The message for a result code. Handles the `<field>` families and unknown codes. */
export function messageForCode(code: string, detail?: string | null): string {
  const known = RESULT_MESSAGES[code];
  if (known !== undefined) {
    return code === 'exists_with_different_fields' && detail ? `${known} (${detail})` : known;
  }
  if (code.startsWith(DIFFERENT_PREFIX)) {
    const field = words(code.slice(DIFFERENT_PREFIX.length));
    return `The ${field} does not match the pending transfer's ${field}.`;
  }
  if (code.startsWith(EXISTS_DIFFERENT_PREFIX)) {
    const field = words(code.slice(EXISTS_DIFFERENT_PREFIX.length));
    return `A record with this id already exists with a different ${field}.`;
  }
  return detail ?? RESULT_MESSAGES[CLIENT_CODES.unknown] ?? 'Something unexpected went wrong.';
}

export interface ProblemText {
  /** Human sentence. */
  message: string;
  /** Raw code, shown in monospace next to the message. */
  code: string;
}

export function describeProblem(problem: ApiProblem): ProblemText {
  return { message: messageForCode(problem.code, problem.detail), code: problem.code };
}

/** DTOs mirroring docs/API.md exactly. Amounts and counters are minor-unit integer strings. */

export interface Ledger {
  id: number;
  currency: string;
  assetScale: number;
}

export interface AccountFlags {
  debitsMustNotExceedCredits: boolean;
  creditsMustNotExceedDebits: boolean;
  history: boolean;
  closed: boolean;
}

export interface Account {
  id: string;
  ledger: number;
  code: number;
  name: string;
  flags: AccountFlags;
  debitsPending: string;
  debitsPosted: string;
  creditsPending: string;
  creditsPosted: string;
  createdAt: string;
}

/** `closed` is not allowed at creation in v1; every flag is optional. */
export type AccountCreateFlags = Partial<Omit<AccountFlags, 'closed'>>;

export interface AccountCreate {
  id: string;
  ledger: number;
  code: number;
  name: string;
  flags?: AccountCreateFlags;
}

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

export interface BalanceSnapshot {
  transferId: string;
  seq: string;
  timestamp: string;
  debitsPending: string;
  debitsPosted: string;
  creditsPending: string;
  creditsPosted: string;
}

export interface TransferFlags {
  linked: boolean;
  pending: boolean;
  postPendingTransfer: boolean;
  voidPendingTransfer: boolean;
}

export type PendingStatus = 'PENDING' | 'POSTED' | 'VOIDED' | 'EXPIRED';

export interface Transfer {
  id: string;
  debitAccountId: string;
  creditAccountId: string;
  amount: string;
  ledger: number;
  code: number;
  flags: TransferFlags;
  pendingId: string | null;
  timeoutSeconds: number;
  reference: string | null;
  seq: string;
  timestamp: string;
  pendingStatus: PendingStatus | null;
  expiresAt: string | null;
}

/**
 * Body of POST /transfers. `seq`, `timestamp`, `pendingStatus` and `expiresAt` are
 * server-set and ignored on input, so they are not sent. For post/void, the accounts,
 * ledger and code may be omitted (inherited from the pending transfer).
 */
export interface TransferCreate {
  id: string;
  amount: string;
  debitAccountId?: string;
  creditAccountId?: string;
  ledger?: number;
  code?: number;
  flags?: Partial<TransferFlags>;
  pendingId?: string | null;
  timeoutSeconds?: number;
  reference?: string | null;
}

export interface LedgerReconciliation {
  ledger: number;
  debitsPosted: string;
  creditsPosted: string;
  debitsPending: string;
  creditsPending: string;
  balanced: boolean;
}

export type MismatchField = 'debitsPosted' | 'creditsPosted' | 'debitsPending' | 'creditsPending';

export interface AccountMismatch {
  accountId: string;
  field: MismatchField;
  stored: string;
  recomputed: string;
}

export interface Reconciliation {
  checkedAt: string;
  balanced: boolean;
  ledgers: LedgerReconciliation[];
  accountMismatches: AccountMismatch[];
}

export type ActivityType =
  | 'transfer.created'
  | 'transfer.posted'
  | 'transfer.voided'
  | 'transfer.expired'
  | 'account.created';

export interface ActivityItem {
  eventId: string;
  type: ActivityType;
  seq: string;
  transferId: string | null;
  accountId: string | null;
  amount: string | null;
  ledger: number;
  occurredAt: string;
  summary: string;
}

export interface FieldError {
  field: string;
  message: string;
}

/** Request wrapper result: the stored entity and whether it was an idempotent replay (200). */
export interface Written<T> {
  value: T;
  replay: boolean;
}

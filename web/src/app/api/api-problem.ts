import { HttpErrorResponse } from '@angular/common/http';
import { FieldError } from './models';

/** RFC 9457 body plus the ledger `code` (and `errors` for validation). */
export interface ProblemBody {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: string;
  errors?: FieldError[];
}

/** Codes the client invents for failures that have no server body. */
export const CLIENT_CODES = {
  network: 'network_error',
  server: 'server_error',
  unknown: 'unknown_error',
} as const;

/** A failed API call, typed. The error interceptor turns every HttpErrorResponse into one. */
export class ApiProblem extends Error {
  readonly status: number;
  readonly code: string;
  readonly title: string;
  readonly detail: string | null;
  readonly errors: FieldError[];
  /** True when the same request may succeed if repeated unchanged (network failure, 5xx). */
  readonly retryable: boolean;

  constructor(init: {
    status: number;
    code: string;
    title?: string;
    detail?: string | null;
    errors?: FieldError[];
  }) {
    super(init.detail ?? init.title ?? init.code);
    this.name = 'ApiProblem';
    this.status = init.status;
    this.code = init.code;
    this.title = init.title ?? init.code;
    this.detail = init.detail ?? null;
    this.errors = init.errors ?? [];
    this.retryable = init.status === 0 || init.status >= 500;
  }

  static is(value: unknown): value is ApiProblem {
    return value instanceof ApiProblem;
  }

  /** Any thrown value as an ApiProblem (anything that is not one becomes `unknown_error`). */
  static from(value: unknown): ApiProblem {
    return value instanceof ApiProblem
      ? value
      : new ApiProblem({
          status: -1,
          code: CLIENT_CODES.unknown,
          detail: value instanceof Error ? value.message : null,
        });
  }

  static fromHttp(err: HttpErrorResponse): ApiProblem {
    if (err.status === 0) {
      return new ApiProblem({
        status: 0,
        code: CLIENT_CODES.network,
        title: 'Network error',
        detail: 'The server could not be reached.',
      });
    }
    const body = isRecord(err.error) ? (err.error as ProblemBody) : null;
    const code =
      typeof body?.code === 'string' && body.code !== ''
        ? body.code
        : err.status >= 500
          ? CLIENT_CODES.server
          : CLIENT_CODES.unknown;
    return new ApiProblem({
      status: typeof body?.status === 'number' ? body.status : err.status,
      code,
      title: typeof body?.title === 'string' ? body.title : undefined,
      detail: typeof body?.detail === 'string' ? body.detail : null,
      errors: Array.isArray(body?.errors) ? body.errors.filter(isFieldError) : [],
    });
  }
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function isFieldError(v: unknown): v is FieldError {
  return isRecord(v) && typeof v['field'] === 'string' && typeof v['message'] === 'string';
}

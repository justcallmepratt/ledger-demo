import { HttpParams } from '@angular/common/http';

export interface PageQuery {
  limit?: number;
  cursor?: string | null;
}

/** Builds query params, dropping undefined, null and empty values. */
export function toParams(query: Record<string, string | number | null | undefined>): HttpParams {
  let params = new HttpParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') {
      params = params.set(key, String(value));
    }
  }
  return params;
}

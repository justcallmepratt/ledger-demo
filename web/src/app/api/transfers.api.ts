import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { API_BASE } from './api-base';
import { Page, PendingStatus, Transfer, TransferCreate, Written } from './models';
import { PageQuery, toParams } from './params';

export interface TransferListQuery extends PageQuery {
  pendingStatus?: PendingStatus;
}

@Injectable({ providedIn: 'root' })
export class TransfersApi {
  private readonly http = inject(HttpClient);

  /** 201 = created, 200 = idempotent replay. Rejections arrive as an ApiProblem. */
  create(body: TransferCreate): Observable<Written<Transfer>> {
    return this.http
      .post<Transfer>(`${API_BASE}/transfers`, body, { observe: 'response' })
      .pipe(map((res) => ({ value: res.body as Transfer, replay: res.status === 200 })));
  }

  get(id: string): Observable<Transfer> {
    return this.http.get<Transfer>(`${API_BASE}/transfers/${encodeURIComponent(id)}`);
  }

  list(query: TransferListQuery = {}): Observable<Page<Transfer>> {
    return this.http.get<Page<Transfer>>(`${API_BASE}/transfers`, {
      params: toParams({
        pendingStatus: query.pendingStatus,
        limit: query.limit,
        cursor: query.cursor,
      }),
    });
  }
}

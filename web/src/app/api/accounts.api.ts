import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { EMPTY, Observable, expand, map, reduce } from 'rxjs';
import { API_BASE } from './api-base';
import { Account, AccountCreate, BalanceSnapshot, Page, Transfer, Written } from './models';
import { PageQuery, toParams } from './params';

@Injectable({ providedIn: 'root' })
export class AccountsApi {
  private readonly http = inject(HttpClient);

  list(query: PageQuery = {}): Observable<Page<Account>> {
    return this.http.get<Page<Account>>(`${API_BASE}/accounts`, {
      params: toParams({ limit: query.limit, cursor: query.cursor }),
    });
  }

  /** Follows nextCursor until the end. Used to fill the transfer form's account pickers. */
  listAll(pageSize = 200): Observable<Account[]> {
    return this.list({ limit: pageSize }).pipe(
      expand((page) =>
        page.nextCursor ? this.list({ limit: pageSize, cursor: page.nextCursor }) : EMPTY,
      ),
      reduce((all, page) => all.concat(page.items), [] as Account[]),
    );
  }

  get(id: string): Observable<Account> {
    return this.http.get<Account>(`${API_BASE}/accounts/${encodeURIComponent(id)}`);
  }

  /** 201 = created, 200 = idempotent replay of an identical request. 409 arrives as an ApiProblem. */
  create(body: AccountCreate): Observable<Written<Account>> {
    return this.http
      .post<Account>(`${API_BASE}/accounts`, body, { observe: 'response' })
      .pipe(map((res) => ({ value: res.body as Account, replay: res.status === 200 })));
  }

  transfers(id: string, query: PageQuery = {}): Observable<Page<Transfer>> {
    return this.http.get<Page<Transfer>>(
      `${API_BASE}/accounts/${encodeURIComponent(id)}/transfers`,
      { params: toParams({ limit: query.limit, cursor: query.cursor }) },
    );
  }

  balances(id: string, query: PageQuery = {}): Observable<Page<BalanceSnapshot>> {
    return this.http.get<Page<BalanceSnapshot>>(
      `${API_BASE}/accounts/${encodeURIComponent(id)}/balances`,
      { params: toParams({ limit: query.limit, cursor: query.cursor }) },
    );
  }
}

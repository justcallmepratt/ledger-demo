import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from './api-base';
import { Ledger } from './models';

@Injectable({ providedIn: 'root' })
export class LedgersApi {
  private readonly http = inject(HttpClient);

  list(): Observable<Ledger[]> {
    return this.http.get<Ledger[]>(`${API_BASE}/ledgers`);
  }
}

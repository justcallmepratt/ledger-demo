import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from './api-base';
import { Reconciliation } from './models';

@Injectable({ providedIn: 'root' })
export class ReconciliationApi {
  private readonly http = inject(HttpClient);

  check(): Observable<Reconciliation> {
    return this.http.get<Reconciliation>(`${API_BASE}/reconciliation`);
  }
}

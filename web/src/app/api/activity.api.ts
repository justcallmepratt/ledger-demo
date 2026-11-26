import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from './api-base';
import { ActivityItem } from './models';
import { toParams } from './params';

export const ACTIVITY_STREAM_URL = `${API_BASE}/activity/stream`;

@Injectable({ providedIn: 'root' })
export class ActivityApi {
  private readonly http = inject(HttpClient);

  /** Newest first. */
  recent(limit?: number): Observable<ActivityItem[]> {
    return this.http.get<ActivityItem[]>(`${API_BASE}/activity`, {
      params: toParams({ limit }),
    });
  }
}

import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { catchError, throwError } from 'rxjs';
import { ApiProblem } from './api-problem';
import { API_BASE } from './api-base';

/** Asks for JSON (and problem+json) on every API call. */
export const acceptInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith(API_BASE) || req.headers.has('Accept')) return next(req);
  return next(req.clone({ setHeaders: { Accept: 'application/json, application/problem+json' } }));
};

/** Turns any failed API call (problem+json, plain HTTP error, network failure) into an ApiProblem. */
export const errorInterceptor: HttpInterceptorFn = (req, next) =>
  next(req).pipe(
    catchError((err: unknown) =>
      throwError(() => (err instanceof HttpErrorResponse ? ApiProblem.fromHttp(err) : err)),
    ),
  );

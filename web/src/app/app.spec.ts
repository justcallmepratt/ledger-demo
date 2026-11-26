import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { expectNoA11yViolations } from '../testing/axe';
import { q, qa, settle } from '../testing/dom';
import { FakeEventSource } from '../testing/fake-event-source';
import { sequentialUuids } from '../testing/providers';
import { App } from './app';
import { appConfig } from './app.config';
import { routes } from './app.routes';
import { EVENT_SOURCE_FACTORY } from './core/activity-feed';
import { NEW_UUID } from './core/ids';
import { NotFound } from './features/not-found/not-found';

describe('App shell and routing', () => {
  beforeEach(() => {
    FakeEventSource.reset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(routes, withComponentInputBinding()),
        { provide: NEW_UUID, useValue: sequentialUuids() },
        { provide: EVENT_SOURCE_FACTORY, useValue: (url: string) => new FakeEventSource(url) },
      ],
    });
  });

  afterEach(() => TestBed.inject(HttpTestingController).match(() => true));

  it('renders landmarks, a skip link, the nav and the fan-design credit', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(q(el, 'a.skip-link').getAttribute('href')).toBe('#main');
    expect(q(el, 'main#main')).toBeTruthy();
    expect(q(el, 'header nav[aria-label="Main"]')).toBeTruthy();
    expect(qa(el, 'nav a').map((a) => a.textContent?.trim())).toEqual([
      'Dashboard',
      'New transfer',
      'Pending',
      'Activity',
    ]);
    expect(q(el, 'footer').textContent).toContain(
      'Visual style inspired by the Cyberpunk 2077 UI. Unofficial fan design.',
    );
    expect(q(el, '.scanlines').getAttribute('aria-hidden')).toBe('true');
    expect(q(el, '[aria-label="Notifications"]')).toBeTruthy();
    await expectNoA11yViolations(el);
  });

  it('lazy-loads each route and marks the current page in the nav', async () => {
    const fixture = TestBed.createComponent(App);
    const router = TestBed.inject(Router);
    const el: HTMLElement = fixture.nativeElement;
    for (const [url, current, heading] of [
      ['/transfers/new', 'New transfer', 'New transfer'],
      ['/transfers/pending', 'Pending', 'Pending transfers'],
      ['/activity', 'Activity', 'Live activity'],
      ['/', 'Dashboard', 'Ledger dashboard'],
    ] as const) {
      await router.navigateByUrl(url);
      await settle(fixture);
      expect(q(el, 'nav a[aria-current="page"]').textContent?.trim()).toBe(current);
      expect(q(el, 'main h1').textContent).toBe(heading);
      expect(TestBed.inject(Location).path()).toBe(url === '/' ? '' : url);
    }
  });

  it('routes /accounts/:id with the id as a component input', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/accounts/abc-123');
    expect(harness.routeNativeElement?.textContent).toContain('abc-123');
  });

  it('shows the 404 page for unknown routes, accessibly', async () => {
    const harness = await RouterTestingHarness.create();
    const component = await harness.navigateByUrl('/nope/not-here');
    expect(component).toBeInstanceOf(NotFound);
    const el = harness.routeNativeElement as HTMLElement;
    expect(q(el, 'h1').textContent).toContain('404');
    expect(q(el, 'a[href="/"]')).toBeTruthy();
    await expectNoA11yViolations(el);
  });

  it('sets a document title per route', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/activity');
    expect(document.title).toBe('Live activity · Ledger');
    await harness.navigateByUrl('/nope');
    expect(document.title).toBe('Not found · Ledger');
  });
});

describe('appConfig', () => {
  it('provides HttpClient with the Accept and error interceptors', async () => {
    TestBed.configureTestingModule({
      providers: [...appConfig.providers, provideHttpClientTesting()],
    });
    const { LedgersApi } = await import('./api/ledgers.api');
    const { ApiProblem } = await import('./api/api-problem');
    let err: unknown;
    TestBed.inject(LedgersApi)
      .list()
      .subscribe({ error: (e) => (err = e) });
    const req = TestBed.inject(HttpTestingController).expectOne('/api/v1/ledgers');
    expect(req.request.headers.get('Accept')).toContain('application/problem+json');
    req.flush({ code: 'x' }, { status: 400, statusText: 'Bad' });
    expect(err).toBeInstanceOf(ApiProblem);
  });
});

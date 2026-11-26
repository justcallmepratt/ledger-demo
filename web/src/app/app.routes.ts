import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Dashboard · Ledger',
    loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
  },
  {
    path: 'accounts/:id',
    title: 'Account · Ledger',
    loadComponent: () => import('./features/accounts/account-detail').then((m) => m.AccountDetail),
  },
  {
    path: 'transfers/new',
    title: 'New transfer · Ledger',
    loadComponent: () => import('./features/transfers/transfer-form').then((m) => m.TransferForm),
  },
  {
    path: 'transfers/pending',
    title: 'Pending transfers · Ledger',
    loadComponent: () =>
      import('./features/pending/pending-transfers').then((m) => m.PendingTransfers),
  },
  {
    path: 'activity',
    title: 'Live activity · Ledger',
    loadComponent: () => import('./features/activity/activity-page').then((m) => m.ActivityPage),
  },
  {
    path: '**',
    title: 'Not found · Ledger',
    loadComponent: () => import('./features/not-found/not-found').then((m) => m.NotFound),
  },
];

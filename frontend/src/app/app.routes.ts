import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';

export const routes: Routes = [
  // ---- Pages publiques (hors coquille) ----
  {
    path: '',
    loadComponent: () => import('./landing/landing.component').then((m) => m.LandingComponent),
  },
  {
    path: 'login',
    loadComponent: () => import('./auth/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'register',
    loadComponent: () =>
      import('./auth/register/register.component').then((m) => m.RegisterComponent),
  },
  {
    path: 'auth/verify',
    loadComponent: () =>
      import('./auth/verify-email/verify-email.component').then((m) => m.VerifyEmailComponent),
  },
  {
    path: 'auth/forgot',
    loadComponent: () =>
      import('./auth/forgot-password/forgot-password.component').then(
        (m) => m.ForgotPasswordComponent,
      ),
  },
  {
    path: 'auth/reset',
    loadComponent: () =>
      import('./auth/reset-password/reset-password.component').then(
        (m) => m.ResetPasswordComponent,
      ),
  },
  {
    path: 'auth/callback',
    loadComponent: () =>
      import('./auth/oauth-callback/oauth-callback.component').then(
        (m) => m.OauthCallbackComponent,
      ),
  },
  // ---- Pages légales publiques (F-29 SF-29-03) ----
  // Déclarées AVANT la route parente pathless authentifiée : Angular résout dans l'ordre
  // de déclaration, et une déclaration après ce parent les ferait passer par l'authGuard.
  // Une page légale accessible seulement aux utilisateurs connectés ne remplit pas son rôle.
  {
    path: 'mentions-legales',
    loadComponent: () =>
      import('./legal/mentions-legales.component').then((m) => m.MentionsLegalesComponent),
  },
  {
    path: 'confidentialite',
    loadComponent: () =>
      import('./legal/confidentialite.component').then((m) => m.ConfidentialiteComponent),
  },
  {
    path: 'cgu',
    loadComponent: () => import('./legal/cgu.component').then((m) => m.CguComponent),
  },
  {
    path: 'contact',
    loadComponent: () => import('./legal/contact.component').then((m) => m.ContactComponent),
  },

  // Onboarding : flux authentifié dédié, volontairement hors coquille.
  {
    path: 'onboarding',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./onboarding/onboarding.component').then((m) => m.OnboardingComponent),
  },

  // ---- Zone authentifiée : enveloppée par la coquille de navigation (F-19) ----
  // Route parente pathless : les URLs des enfants restent inchangées (/chat, /billing, …)
  // et l'authGuard est centralisé sur le parent.
  {
    path: '',
    loadComponent: () => import('./layout/shell/shell.component').then((m) => m.ShellComponent),
    canActivate: [authGuard],
    children: [
      {
        path: 'chat',
        loadComponent: () => import('./chat/chat.component').then((m) => m.ChatComponent),
      },
      {
        path: 'atelier',
        loadComponent: () => import('./atelier/atelier.component').then((m) => m.AtelierComponent),
      },
      {
        // Route additive (F-30 SF-30-10) : ouvrir un projet précis, et éventuellement son terminal.
        // Placée APRÈS `atelier` pour ne pas la masquer — `/atelier` seul reste valide.
        path: 'atelier/:id',
        loadComponent: () => import('./atelier/atelier.component').then((m) => m.AtelierComponent),
      },
      {
        // F-68 / SF-68-01 — **l'accueil de la Forge** : la vue des missions (livrée par F-49 /
        // SF-49-02) devient la porte d'entrée, et l'onglet « Postes » disparaît de la barre. Le
        // composant est le même, à l'identique — F-68 réorganise la navigation, il ne refait pas
        // l'écran. Chemin d'un seul segment, disjoint de `atelier/:id`.
        path: 'forge',
        loadComponent: () => import('./postes/postes.component').then((m) => m.PostesComponent),
      },
      {
        // F-76 / SF-76-03 — **voir travailler ses terminaux**. Un écran à PART, et non un panneau
        // de l'accueil : on l'ouvre quand on surveille, et la page d'accueil doit rester lisible
        // sur un portable. Placée AVANT la redirection de `postes` et après `forge` : deux
        // segments, elle ne masque ni `forge` (un segment), ni `atelier/:id` (autre préfixe).
        path: 'forge/supervision',
        loadComponent: () =>
          import('./supervision/supervision.component').then((m) => m.SupervisionComponent),
      },
      {
        // F-83 / SF-83-02 — **la mosaïque** : quatre vrais terminaux, vivants, en même temps. La
        // supervision de F-76 montre des aperçus ; celle-ci montre le CONTENU RÉEL des flux, ce
        // que le PO demandait depuis le début. Les deux coexistent : on survole l'une, on regarde
        // travailler dans l'autre.
        // Deux segments, comme `forge/supervision` : elle ne masque ni `forge` (un segment), ni
        // `atelier/:id` (autre préfixe), ni `forge/supervision` (segment final différent).
        path: 'forge/mosaique',
        loadComponent: () =>
          import('./mosaique/mosaique.component').then((m) => m.MosaiqueComponent),
      },
      {
        // L'ancienne adresse (F-49 / SF-49-02) continue de répondre : un onglet resté ouvert ou un
        // lien collé la veille s'ouvre sur la même page. `pathMatch: 'full'` pour ne capter que le
        // chemin exact, cible absolue pour ne pas dépendre de la résolution du parent pathless.
        path: 'postes',
        redirectTo: '/forge',
        pathMatch: 'full',
      },
      {
        // F-51 / SF-51-05 — le catalogue de gouvernance. Un seul segment, disjoint de toutes les
        // routes existantes : il n'en masque aucune, et aucune ne le masque.
        path: 'gouvernance',
        loadComponent: () =>
          import('./governance/governance.component').then((m) => m.GovernanceComponent),
      },
      {
        path: 'atelier/:id/fichiers',
        loadComponent: () =>
          import('./atelier/files/atelier-files.component').then((m) => m.AtelierFilesComponent),
      },
      {
        path: 'documents',
        loadComponent: () =>
          import('./documents/documents.component').then((m) => m.DocumentsComponent),
      },
      {
        path: 'ask',
        loadComponent: () => import('./ask/ask.component').then((m) => m.AskComponent),
      },
      {
        path: 'templates',
        loadComponent: () =>
          import('./templates/templates.component').then((m) => m.TemplatesComponent),
      },
      {
        path: 'reports',
        loadComponent: () => import('./reports/reports.component').then((m) => m.ReportsComponent),
      },
      {
        path: 'billing',
        loadComponent: () => import('./billing/billing.component').then((m) => m.BillingComponent),
      },
      {
        path: 'settings',
        loadComponent: () =>
          import('./settings/settings.component').then((m) => m.SettingsComponent),
      },
      {
        path: 'profile',
        loadComponent: () =>
          import('./auth/profile/profile.component').then((m) => m.ProfileComponent),
      },
      {
        path: 'admin',
        loadComponent: () => import('./admin/admin.component').then((m) => m.AdminComponent),
      },
    ],
  },

  { path: '**', redirectTo: '' },
];

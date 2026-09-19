import { inject } from '@angular/core';
import { RedirectFunction, Router, Routes, UrlMatchResult, UrlSegment } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';

/**
 * Redirection d'une ancienne adresse de la Forge vers `/forge/voir` à la densité donnée (F-98 /
 * SF-98-04), en gardant les autres paramètres de requête.
 */
export function forgeDensityRedirect(densite: 'apercus' | 'flux'): RedirectFunction {
  return ({ queryParams }) =>
    inject(Router).createUrlTree(['/forge', 'voir'], { queryParams: { ...queryParams, densite } });
}

/**
 * Segments réservés sous `/forge` : ce ne sont pas des postes. Un poste n'a pour référence qu'un
 * identifiant (UUID) ou `heberge` ; ces écrans-là ont leur propre route.
 */
export const FORGE_RESERVED_SEGMENTS: readonly string[] = ['supervision', 'mosaique', 'voir', 'clients'];

/**
 * **`/forge` et `/forge/:hostRef`** (F-98 / SF-98-01) — une seule configuration de route.
 *
 * <p>Deux routes distinctes feraient détruire et recréer l'écran à chaque changement de poste : le
 * routeur ne réemploie un composant que pour la même configuration. Ici, `/forge` (poste par
 * défaut) et `/forge/<id>` partagent la même, et l'écran suit le paramètre.</p>
 */
export function forgeMatcher(segments: UrlSegment[]): UrlMatchResult | null {
  if (segments.length === 0 || segments[0].path !== 'forge') {
    return null;
  }
  if (segments.length === 1) {
    return { consumed: segments };
  }
  if (segments.length === 2 && !FORGE_RESERVED_SEGMENTS.includes(segments[1].path)) {
    return { consumed: segments, posParams: { hostRef: segments[1] } };
  }
  return null;
}

/**
 * **`/vigie` et `/vigie/:hostRef`** (F-106 / SF-106-02) — une seule configuration de route, comme la
 * Forge : passer d'un client à l'autre ne recrée pas l'écran.
 */
export function vigieMatcher(segments: UrlSegment[]): UrlMatchResult | null {
  if (segments.length === 0 || segments[0].path !== 'vigie') {
    return null;
  }
  if (segments.length === 1) {
    return { consumed: segments };
  }
  if (segments.length === 2) {
    return { consumed: segments, posParams: { hostRef: segments[1] } };
  }
  return null;
}

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
  // ---- Page partagée, ouverte sans compte (F-109 / SF-109-05) ----
  // Déclarée AVANT la route parente authentifiée, pour la raison des pages légales : après elle, un lien
  // partagé passerait par l'authGuard et le destinataire, qui n'a pas de compte, ne verrait rien.
  {
    path: 'p/:token',
    loadComponent: () => import('./pages/shared-page.component').then((m) => m.SharedPageComponent),
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
        // F-76 / SF-76-03 — **voir travailler ses terminaux**. Un écran à PART, et non un panneau
        // de l'accueil : on l'ouvre quand on surveille, et la page d'accueil doit rester lisible
        // sur un portable. Placée AVANT la redirection de `postes` et après `forge` : deux
        // segments, elle ne masque ni `forge` (un segment), ni `atelier/:id` (autre préfixe).
        path: 'forge/supervision',
        // F-98 / SF-98-04 : la supervision vit désormais dans « Voir travailler », densité Aperçus.
        // L'ancienne adresse redirige — les liens collés et les onglets restés ouverts ne cassent pas.
        redirectTo: forgeDensityRedirect('apercus'),
      },
      {
        // F-83 / SF-83-02 — **la mosaïque** : quatre vrais terminaux, vivants, en même temps. La
        // supervision de F-76 montre des aperçus ; celle-ci montre le CONTENU RÉEL des flux, ce
        // que le PO demandait depuis le début. Les deux coexistent : on survole l'une, on regarde
        // travailler dans l'autre.
        // Deux segments, comme `forge/supervision` : elle ne masque ni `forge` (un segment), ni
        // `atelier/:id` (autre préfixe), ni `forge/supervision` (segment final différent).
        path: 'forge/mosaique',
        // F-98 / SF-98-04 : la mosaïque vit désormais dans « Voir travailler », densité Flux entiers.
        redirectTo: forgeDensityRedirect('flux'),
      },
      {
        // F-98 / SF-98-04 — **Voir travailler, un seul écran** : les aperçus (F-76) et les flux entiers
        // (F-83) derrière une porte et un sélecteur, `?densite=apercus|flux`. Deux segments, déclarée
        // avant le matcher de la Forge (qui réserve `voir` de toute façon).
        path: 'forge/voir',
        loadComponent: () =>
          import('./forge-voir/voir-travailler.component').then((m) => m.VoirTravaillerComponent),
      },
      {
        // F-124 / SF-124-05 — **Mes clients, la Vitrine** : le revenu par client (SF-124-02) mis en
        // valeur (bandeau « fierté » + grandes cartes). Écran autonome, sur le patron de « Voir
        // travailler ». Segment `clients` réservé par le matcher de la Forge (aucune référence de
        // poste ne vaut `clients` : UUID ou `heberge`).
        path: 'forge/clients',
        loadComponent: () =>
          import('./forge-clients/mes-clients.component').then((m) => m.MesClientsComponent),
      },
      {
        // F-68 / SF-68-01 — **l'accueil de la Forge**, et depuis F-98 / SF-98-01 **le poste ouvert** :
        // `/forge` et `/forge/:hostRef` sont UNE seule route (un `matcher`), pour que passer d'un poste
        // à l'autre ne détruise pas l'écran — pas de rechargement, pas de clignotement. Déclarée APRÈS
        // `forge/supervision` et `forge/mosaique` : le matcher les écarte de toute façon, mais l'ordre
        // le rend vrai deux fois.
        matcher: forgeMatcher,
        loadComponent: () => import('./postes/postes.component').then((m) => m.PostesComponent),
      },
      {
        // F-106 / SF-106-04 — **l'adresse d'un sujet** de la Vigie, et depuis F-103 / SF-103-01 **la
        // page sujet** elle-même. Déclarée AVANT `vigieMatcher` (qui n'avale pas quatre segments de
        // toute façon) : changer de sujet dans l'adresse réemploie la page, qui suit ses paramètres.
        path: 'vigie/:hostRef/sujets/:subjectId',
        loadComponent: () =>
          import('./vigie/radar-subject/radar-subject-page.component').then((m) => m.RadarSubjectPageComponent),
      },
      {
        // F-128 / SF-128-10 — **le détail d'une réunion capturée** (audio + deck ; exploitation à venir
        // SF-128-05). Déclarée AVANT `vigieMatcher` (qui n'avale pas quatre segments), même forme que la
        // page sujet ci-dessus : changer de réunion dans l'adresse réemploie la page.
        path: 'vigie/:hostRef/reunions/:meetingId',
        loadComponent: () =>
          import('./vigie/meeting-detail/meeting-detail-page.component').then((m) => m.MeetingDetailPageComponent),
      },
      {
        // F-106 / SF-106-02 — **la Vigie**, l'espace du pilotage : la même forme maître–détail que la
        // Forge. Préfixe disjoint de `forge` et d'`atelier` : elle n'en masque aucune.
        matcher: vigieMatcher,
        loadComponent: () => import('./vigie/vigie.component').then((m) => m.VigieComponent),
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
        // F-109 / SF-109-03 — **une page en plein écran**, ouverte dans un nouvel onglet depuis le terminal.
        // Préfixe `pages` disjoint de toutes les routes existantes ; sous le parent authentifié.
        path: 'pages/:id',
        loadComponent: () =>
          import('./pages/page-viewer.component').then((m) => m.PageViewerComponent),
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
        // F-112 / SF-112-03 — l'écran « IA connectées » : jetons personnels MCP et journal.
        // Un segment disjoint de toutes les routes existantes.
        path: 'ia-connectees',
        loadComponent: () =>
          import('./mcp-connections/mcp-connections.component').then(
            (m) => m.McpConnectionsComponent,
          ),
      },
      {
        // F-112 / SF-112-08 — l'écran « Connecter une IA » : adresse du serveur et pas-à-pas.
        path: 'connecter-une-ia',
        loadComponent: () =>
          import('./connect-ai/connect-ai.component').then((m) => m.ConnectAiComponent),
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

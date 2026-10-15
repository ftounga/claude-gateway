# Mini-spec — [F-204 / SF-204-01] Notifications actives par défaut, sans bannière

## Identifiant
`F-204 / SF-204-01` — Feature parente `F-204` — Statut `in-progress` — Créée 2026-10-16
Branche : `feat/SF-204-01-notifs-par-defaut`

## Objectif
Retirer la bannière « Activer les notifications » du terminal et abonner l'appareil sans effort : silencieusement si le navigateur a déjà donné la permission, sinon par une demande unique greffée sur le premier envoi.

## Comportement nominal
1. La bannière `app-terminal-push-invite` disparaît (composant et spec supprimés).
2. **Au démarrage de la coquille authentifiée** (`ShellComponent`), `PushActivationService.ensureSubscribed()` :
   push supporté + permission `granted` + appareil non désactivé volontairement ⇒ `enable()` sans aucune UI
   (`requestSubscription` rend l'abonnement existant ou en crée un ; l'enregistrement serveur est un upsert
   `user_id` + endpoint ⇒ réabonnement si la ligne a disparu, idempotent sinon).
3. **Premier « Envoyer » d'un tour** dans le terminal : `askOnceOnGesture()` — si permission `default`, pas
   déjà demandée sur cet appareil, pas désactivée volontairement ⇒ `Notification.requestPermission()` appelé
   **synchrone dans le geste** (exigence navigateur, iOS compris), mémorisé « demandé » (localStorage) ;
   si `granted` ⇒ `enable()`. L'envoi n'attend pas : la demande court en parallèle.
4. `denied` ⇒ rien. Paramètres > Notifications (inchangé) explique déjà comment réautoriser.
5. « Désactiver » dans Paramètres pose un drapeau d'appareil `cg.push.optOut` : ni abonnement silencieux, ni
   demande ensuite. « Activer » dans Paramètres le lève.

## Cas d'erreur
- Push non supporté / pas de service worker ⇒ no-op.
- Clé VAPID absente (`not-configured`) ou échec réseau ⇒ silencieux, aucune bannière ni snackbar.
- `localStorage` indisponible (navigation privée) ⇒ try/catch ; au pire la demande se reproduit dans un autre onglet.
- Ancien drapeau `cg.pushInvite.never` (« Ne plus proposer » de F-185) ⇒ vaut « déjà demandé » (pas de demande
  de permission) ; l'abonnement silencieux reste possible si la permission est déjà accordée.

## Critères d'acceptation
- [ ] CA1 Aucune bannière de notifications dans le terminal (sélecteur absent du gabarit, composant supprimé).
- [ ] CA2 Permission `granted`, non désactivé ⇒ `ensureSubscribed` s'abonne + POST `/api/push/subscriptions`, sans UI.
- [ ] CA3 Permission `default` ⇒ `askOnceOnGesture` demande la permission une seule fois (2e appel = no-op).
- [ ] CA4 Refus ⇒ pas d'abonnement, pas de nouvelle demande.
- [ ] CA5 `denied`, non supporté, ou `optOut` ⇒ aucun appel.
- [ ] CA6 `disable()` pose `optOut` ; `enable()` (Paramètres) le lève.

## Plan de test
- Unitaires `push-activation.service.spec.ts` : CA2 à CA6 (`Notification` bouchonné).
- Terminal : `submit()` déclenche `askOnceOnGesture` (spy).
- Isolation utilisateur : inchangée — l'enregistrement passe par l'API existante scellée `currentUser.requireId()` (aucun changement backend).
- Non-régression : `npm run build && npm test` complet.

## Impacts
- Tables / endpoints : aucun (API `/api/push/*` existante, upsert idempotent).
- Composants : `PushActivationService`, `ShellComponent`, `AtelierTerminalComponent` (gabarit + `submit`), suppression `TerminalPushInviteComponent`.
- Préoccupations transversales : aucune (ni auth, ni tenant, ni plans, ni routing).
- Design system : rien de visuel ajouté (retrait uniquement).

## Hors périmètre
- Backend (préférences serveur déjà actives par défaut).
- Paramètres > Notifications (inchangé hormis le drapeau `optOut` posé/levé par le service).
- Demande sur « ouverture d'un terminal par clic » : arbitrage — le premier envoi suffit (geste unique, toujours présent, moins de couplage) ; le cadrage le donnait en alternative.

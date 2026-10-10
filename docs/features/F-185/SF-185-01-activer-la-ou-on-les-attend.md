# Mini-spec — [F-185 / SF-185-01] Activer les notifications là où on les attend

## Identifiant

`F-185 / SF-185-01`

## Feature parente

`F-185` — Rien ne vous attend en silence

## Statut

`in-progress`

## Date de création

2026-10-10

## Branche Git

`feat/SF-185-01-activer-notifications`

---

## Objectif

Proposer l'activation des notifications dans le terminal, au moment où elle sert, et rendre visible sur cet appareil si elles sont actives, bloquées ou indisponibles.

---

## Comportement attendu

### Cas nominal

1. **Le bandeau d'invitation** (`app-terminal-push-invite`) s'affiche dans le terminal, au-dessus de la saisie, à côté de la bande des attentes, quand **toutes** ces conditions sont vraies :
   - le navigateur supporte le push (`PushActivationService.supported`) ;
   - cet appareil n'est pas abonné (`enabled() === false`) ;
   - la permission du navigateur n'est pas `denied` ;
   - l'utilisateur n'a pas choisi « Ne plus proposer » sur cet appareil (`localStorage`) ;
   - le bandeau n'est pas en sommeil (« Plus tard » dans cet onglet).
2. Texte : « Soyez prévenu quand une réponse est prête ou qu'une décision vous attend, même l'application fermée. » Trois gestes :
   - **Activer** : appelle `PushActivationService.enable()`, puis affiche le résultat dans le bandeau ;
   - **Plus tard** : met le bandeau en sommeil pour cet onglet ;
   - **Ne plus proposer** : l'écarte définitivement sur cet appareil.
3. **Rappel au premier tour long** : si le bandeau est en sommeil et qu'un tour dure **20 s**, il réapparaît une fois avec « Ce travail prend du temps : soyez prévenu quand la réponse arrive. ». Un nouveau « Plus tard » le rendort jusqu'à la fin de l'onglet.
4. **État visible** après « Activer » :
   - `enabled` : « Notifications actives sur cet appareil. » pendant 6 s, puis le bandeau disparaît (puisque l'appareil est abonné) ;
   - `denied` : « Le navigateur bloque les notifications de ce site : autorisez-les dans les réglages du site, puis réessayez depuis Paramètres > Notifications. » Le bandeau reste jusqu'à fermeture, puis ne revient plus tant que la permission est `denied` ;
   - `not-configured` / `unsupported` : message lisible, même règle.
5. **Paramètres > Notifications** montre aussi l'état « bloquées par le navigateur » (permission `denied`) avec la marche à suivre, au lieu d'un bouton « Activer » qui échouerait. Le texte cite les trois événements existants : réponse prête, autorisation demandée, question posée.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Navigateur sans push (Safari iOS hors PWA installée) | Bandeau jamais affiché ; les paramètres gardent le message existant « ajoutez l'application à l'écran d'accueil » | — |
| Permission déjà `denied` | Pas de bandeau ; état « bloquées par le navigateur » dans les paramètres | — |
| VAPID non configurée côté serveur | Message « pas configurées sur le serveur » dans le bandeau | — |
| `localStorage` indisponible (navigation privée) | Lecture et écriture protégées par try/catch ; « Ne plus proposer » vaut alors pour l'onglet seulement | — |
| Terminal en lecture seule | Pas de bandeau (la zone de saisie n'existe pas) | — |

---

## Critères d'acceptation

- [ ] Le bandeau s'affiche si supporté, non abonné, permission différente de `denied` et non écarté ; il est absent dans chacun des autres cas.
- [ ] « Activer » appelle `enable()` une fois ; en cas de succès, la confirmation s'affiche puis le bandeau disparaît.
- [ ] « Plus tard » le masque ; il réapparaît une seule fois quand un tour dépasse 20 s.
- [ ] « Ne plus proposer » le masque et le garde masqué après rechargement (`localStorage`).
- [ ] Un refus de permission affiche la marche à suivre et ne relance pas l'invitation.
- [ ] Les paramètres affichent l'état « bloquées par le navigateur » quand la permission est `denied`.
- [ ] Charte : jetons `--cg-*`, Material, lisible en thème clair et sombre, sans débordement à 360 px.

---

## Plan de test

- **Unitaires** (`terminal-push-invite.component.spec.ts`) :
  - visibilité selon les 5 conditions ;
  - « Activer » avec succès → confirmation puis masquage ;
  - « Activer » refusé → marche à suivre ;
  - « Plus tard » → masqué, puis réapparition une fois quand `running` reste vrai 20 s (horloge `fakeAsync`), sans seconde réapparition ;
  - « Ne plus proposer » → écrit en `localStorage`, une nouvelle instance reste masquée ;
  - `localStorage` qui lève → pas d'exception.
- **Unitaires** (`notifications-settings.component.spec.ts`) : état « bloquées » quand la permission est `denied`.
- **Intégration** : aucune (pas de backend modifié).
- **Isolation utilisateur** : inchangée. L'abonnement reste scellé `user_id` par `PushSubscriptionController` (F-153), et le choix « Ne plus proposer » est local à l'appareil.

---

## Composants impactés

- **Nouveau** : `frontend/src/app/atelier/terminal/terminal-push-invite.component.ts` (+ spec).
- `atelier-terminal.component.html` : insertion du bandeau avec `[running]="submitting"`.
- `core/services/push-activation.service.ts` : `permission()` (lecture de `Notification.permission`, `'default'` si absente).
- `settings/notifications-settings/*` : état « bloquées » et texte des événements.
- Aucune table, aucun endpoint.

## Préoccupations transversales

- Auth / Principal : non.
- Contexte tenant : non (aucun accès aux données).
- Plans / limites : non.
- Navigation / routing : non (aucune route ni garde ajoutée).

---

## Périmètre

### Hors scope (explicite)

- Nouveaux événements et titres : SF-185-02.
- Ne pas notifier quand le terminal est regardé (D7) : SF-185-03.
- Centre de notifications et cloche : SF-185-04.
- Guide d'installation de la PWA sur iOS : la carte des paramètres l'évoque déjà.

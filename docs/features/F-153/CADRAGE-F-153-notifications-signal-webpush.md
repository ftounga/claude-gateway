# F-153 — Notifications (signal + Web Push, comme une vraie notif téléphone)

> Cadrage du 2026-09-24, à la demande du PO (« recevoir des notifications comme toute notification de
> téléphone : la réponse est prête / une autorisation est demandée »).
> Source : audit `docs/audits/AUDIT-2026-09-24-version-mobile-remote-control.md` (§2 Lot B, §5 paliers 2
> et 3, §6 gouvernance).
>
> **Ce document cadre ; il ne livre aucun code.** Palier 3 (final) des 3 paliers de la version mobile
> (F-151 responsive → F-152 PWA installable → **F-153 notifications**).

## 0. Objectif

Alerter le PO **quand il n'a pas l'écran ouvert** — c'est la seule vraie brique nouvelle (il n'existe
**aucune** notification aujourd'hui, même en onglet ouvert). Deux transitions d'état comptent, et
**l'état existe déjà côté serveur** (F-84) : un tour **se termine**, ou un tour **passe en attente
d'autorisation**. Ce second cas est **critique** : une demande d'autorisation a un `timeoutMs` et le
**silence vaut refus** — sans alerte en déplacement, une commande peut être **refusée par expiration**.
F-153 livre (a) un **signal in-tab** quasi gratuit, puis (b/c) le **Web Push** de bout en bout, « comme
une vraie notif téléphone » : bannière système même app fermée / téléphone verrouillé.

## 1. Ce qui existe déjà (vérifié — NE PAS reconstruire)

- **Les deux transitions à notifier sont déjà des états serveur** : F-84 (« le tour vit côté serveur ») +
  **SF-84-03** exposent le tour et l'**état d'autorisation** de façon interrogeable, y compris cross-pod.
  La gateway **a déjà l'état à notifier** — F-153 ne calcule aucun état, elle **s'y branche**.
- **Aucune notification n'existe** (grep front : zéro `Notification`, `document.title=`,
  `visibilitychange`, favicon dynamique ; zéro VAPID/web-push/FCM/service worker/endpoint d'abonnement).
- **F-126** = navigation intra-page (rail « Vos questions », ancres), **pas** une notification. Ne pas
  confondre.
- **Service worker** : posé par **F-152 / SF-152-02** — F-153 y ajoute les handlers `push` et
  `notificationclick`. **F-153 dépend de F-152.**
- **Dernière migration = `129-atelier-thread-mode-plan.xml`** → la table d'abonnements sera la **130**.

## 2. Décisions de conception

- **D1 — Trois blocs, valeur croissante.** (a) signal in-tab (sans PWA, gratuit) ; (b) backend Web Push ;
  (c) frontend Web Push. Chaque bloc apporte de la valeur seul.
- **D2 — Se brancher sur F-84, ne rien recalculer.** L'émetteur push écoute les **deux transitions
  d'état** déjà exposées (**tour terminé** / **autorisation demandée**). Aucun nouveau moteur d'état,
  Gateway-First.
- **D3 — Scellé par `user_id` (Auth/tenant).** La table `push_subscriptions` est **filtrée `user_id`** ;
  on ne notifie **que les appareils du propriétaire** ; abonnements **révocables** (unsubscribe + purge
  des endpoints morts renvoyés `404/410` par le service push).
- **D4 — Aucun contenu sensible dans la notification.** **Titre neutre** (ex. « Une réponse est prête »,
  « Une autorisation est demandée ») ; **aucun** contenu de tour, nom de projet, ou commande dans la
  charge push. Le détail n'apparaît qu'**après ouverture authentifiée** de l'app.
- **D5 — Clés VAPID par secret d'environnement.** `app.push.vapid-public` / `app.push.vapid-private`
  (+ `app.push.subject`) lus depuis l'environnement (`${APP_PUSH_VAPID_*}`), **aucune valeur en dur**,
  **aucune clé committée** — même patron que `APP_IMAGE_API_KEY`. La clé publique est servie au frontend
  par un endpoint/config, jamais la privée.
- **D6 — Provider Independence.** L'envoi Web Push est un **transport de notification** (protocole W3C
  Web Push / VAPID), **pas** un appel LLM : il ne passe pas par `AIProvider` et n'introduit aucune
  dépendance directe Anthropic. Le déclencheur (état de tour F-84) reste provider-agnostique.
- **D7 — Émission bornée, pas de spam.** Une notification par transition pertinente et par abonnement ;
  débounce/idempotence par (tour, transition) ; pas de notification si l'onglet de ce tour est déjà au
  premier plan (le signal in-tab suffit alors).
- **D8 — Charte.** UI d'activation et favicon dynamique conformes DESIGN_SYSTEM (jetons `--cg-*`, or de
  marque réservé aux gestes).

## 3. Découpage en subfeatures (≈ 3)

| SF | Titre | Bloc | Contenu | Impact |
|----|-------|------|---------|--------|
| **SF-153-01** | Signal in-tab (titre d'onglet + favicon dynamique) | (a) | Quand `document.hidden` **et** qu'un tour **se termine** ou **passe en attente d'autorisation** (état F-84/SF-84-03), allumer le **titre d'onglet** (préfixe/compteur) et un **favicon dynamique** ; rétablir au retour au premier plan (`visibilitychange`). **Sans PWA, quasi gratuit.** | Frontend uniquement (service de signal + favicon) |
| **SF-153-02** | Web Push — backend | (b) | Migration **130** `push_subscriptions` (**filtrée `user_id`**, endpoint/clé/auth, `created_at`, révocable) ; endpoints **subscribe/unsubscribe** ; **émetteur push** branché sur les **deux transitions** de tour de F-84 (**terminé** / **autorisation demandée**), **titre neutre** (D4) ; clés **VAPID par secret d'env** (D5) ; purge des endpoints morts (410/404). | Backend : migration 130, entité/repo, contrôleur, service émetteur, config VAPID |
| **SF-153-03** | Web Push — frontend | (c) | Handlers **`push`** (afficher la notification système) + **`notificationclick`** (ouvrir **le bon terminal** — deep-link authentifié vers le tour) **dans le service worker** (F-152) ; **UI d'activation** des notifications + **demande de permission** navigateur + appel subscribe/unsubscribe. | Frontend : service worker (handlers), UI d'activation, service d'abonnement |

**Ordre** : SF-153-01 (indépendante, livrable même sans F-152) → SF-153-02 → SF-153-03.
SF-153-03 **dépend de** SF-153-02 (endpoints/clé publique) **et de** F-152/SF-152-02 (service worker).

## 4. Dépendances et ordre inter-features

- **F-153 dépend de F-152** (service worker pour b/c). SF-153-01 (in-tab) est la seule livrable sans
  F-152.
- **Ordre recommandé (paliers de l'audit)** : F-151 (confort) → F-152 (installable) → F-153
  (notifications), avec au sein de F-153 : 01 → 02 → 03.

## 5. Préoccupations transversales

- **Auth / tenant** : la table et l'émission sont **scellées `user_id`** (D3) ; lister les endpoints
  d'abonnement et vérifier l'isolation en mini-spec (test de non-régression : un utilisateur ne peut ni
  lire ni notifier les abonnements d'un autre).
- **Plans / limites** : émission bornée (D7) ; pas de fuite de contenu (D4). Vérifier s'il faut un gate
  par plan à l'activation — à trancher en mini-spec.
- **Navigation / routing** : `notificationclick` ouvre un **deep-link authentifié** vers le bon terminal
  — lister les chemins de navigation impactés et le comportement si non connecté (redirection /login puis
  reprise).

## 6. Garde-fous

Gateway-First (branchement sur l'état F-84, pas de moteur neuf), Provider Independence (transport push
≠ LLM, D6), isolation `user_id` (D3), titre neutre / détail derrière l'ouverture authentifiée (D4),
DESIGN_SYSTEM strict, **aucun composant cluster**, aucune clé en dur (D5).

## 7. Hors périmètre

- Notifications **natives** / app de store (l'audit tranche : PWA + Web Push, pas de natif).
- Push **FCM propriétaire** : on reste sur **Web Push standard (VAPID)**, provider-agnostique.
- Le confort tactile (F-151) et l'installabilité/service worker (F-152).
- Un centre de notifications in-app / historique persistant des alertes (au-delà des deux transitions).

## 8. Drapeaux

- **DRAPEAU DÉPLOIEMENT — clé VAPID à poser en prod** : `APP_PUSH_VAPID_PUBLIC` /
  `APP_PUSH_VAPID_PRIVATE` (+ `APP_PUSH_SUBJECT`) à générer et poser en secret d'environnement au
  déploiement de SF-153-02, **comme `APP_IMAGE_API_KEY`**. Sans ces secrets, l'émission push est inactive
  (repli propre : le signal in-tab SF-153-01 continue de fonctionner).
- **Décision iOS-managé (héritée de F-152)** : sur iPhone géré par MDM, le Web Push exige la PWA sur
  l'écran d'accueil et peut être bridé — à confirmer sur le parc cible **avant** de s'engager sur ce
  palier.

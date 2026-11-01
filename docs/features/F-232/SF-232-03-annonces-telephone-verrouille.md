# Mini-spec — F-232 / SF-232-03 — Les annonces téléphone verrouillé, l'appairage et le jeton dédié

## Identifiant

`F-232 / SF-232-03`

## Feature parente

`F-232` — Le compagnon Android de Jarvis (cadrage `CADRAGE-F-232-compagnon-android.md`, D1b, D1c,
préoccupation Auth corrigée le 2026-11-01)

## Statut

`in-review`

## Date de création

2026-11-01

## Branche Git

`feat/SF-232-03-annonces-compagnon`

---

## Objectif

> Quand un tour lancé via Jarvis se termine ou qu'une veille alerte, le compagnon Android **le dit à voix
> haute, même téléphone verrouillé**, puis écoute la réponse quelques secondes sans mot d'éveil ; il reçoit
> ces annonces grâce à un **appairage par code** et à un **jeton dédié**, limité à cette seule route et
> révocable depuis l'application.

---

## Comportement attendu

### Cas nominal

**1. Appairer (D1c)** — écran Jarvis, encart « Compagnon Android » :
- « Appairer un téléphone » → `POST /api/jarvis/companion/pairing-code` → code de 8 caractères
  (alphabet sans `I O 0 1`), valable 10 minutes, affiché une fois.
- Dans le compagnon (étape « 2. Appairer ce téléphone »), le PO tape le code → `POST /api/companion/pair`
  `{ code, name }` → `{ deviceId, token }`. Le jeton (`jcp_` + 32 octets aléatoires base64url) est gardé
  dans les préférences **privées** de l'application ; la gateway n'en garde que l'empreinte SHA-256.
- L'encart liste les téléphones appairés (nom, appairé le, vu le) : `GET /api/jarvis/companion/devices` ;
  « Révoquer » → `DELETE /api/jarvis/companion/devices/{id}` (204) : le jeton cesse aussitôt de valoir.

**2. Produire les annonces** (seulement si le compte a au moins un téléphone appairé non révoqué) :
- **Fin d'un tour né à la voix** (`atelier_messages.via = 'JARVIS'`, réponse enregistrée) :
  « Le message pour {destinataire} est prêt. » si la réponse porte un encart mail/message ; sinon
  « C'est fait : {phrase orale} » (`JarvisReplies.oralLine`) ; sinon « C'est fait. » Lien
  `/jarvis?sujet={id}`.
- **Alerte de veille** (F-230) : la phrase de l'alerte, lien de l'alerte (`/jarvis?alerte=…`).
- Rangées dans `jarvis_companion_announcements` (texte ≤ 600 caractères, lien interne). Best-effort :
  jamais d'échec du tour ni de l'alerte.

**3. Recevoir (D1b)** — `GET /api/companion/announcements?after={curseur}&wait={s}` avec l'en-tête
`X-Companion-Token` (attente longue : la réponse part dès qu'une annonce existe, sinon au bout de `wait`
secondes, 25 par défaut, 30 au plus). Rend `{ cursor, items: [{ id, kind, text, link, at }] }`, au plus
10 éléments, plus anciens d'abord, jamais plus vieux de 15 minutes (une annonce périmée ne se dit pas).
Premier appel sans `after` : curseur = maintenant, aucun élément (rien n'est rejoué). Met à jour
`last_seen_at` au plus une fois par minute.

**4. Dire (D1b)** — le service du compagnon (déjà au premier plan, type micro) tient la boucle d'attente
longue tant qu'il est appairé et que l'écoute est active (pas en pause, pas en batterie faible). À chaque
annonce : il relâche le micro, la **dit avec la synthèse vocale de l'appareil** (aucun service distant),
écran éteint compris, puis ouvre une **fenêtre de réponse de 8 secondes** : le premier mot entendu (sans
mot d'éveil) ouvre l'écran Jarvis sur le lien de l'annonce (`…&source=compagnon&compagnon={version}`),
exactement comme le mot d'éveil. Sans réponse, l'écoute du mot reprend.

**5. Permission réseau** : le compagnon demande désormais `INTERNET` — il ne parle **qu'à l'adresse de la
gateway** réglée, et seulement une fois appairé. Rien du micro n'est envoyé (seuls le code, le nom du
téléphone et les requêtes d'annonces partent).

**6. Parcours d'installation** (D1c), affiché dans l'encart et le README : télécharger l'APK depuis l'écran
Jarvis → autoriser « sources inconnues » pour Chrome → ouvrir Jarvis → autorisations (micro, notifications,
batterie, plein écran) → apprendre « Jarvis » → appairer avec le code → démarrer l'écoute.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Code inconnu, expiré ou déjà utilisé | `companion_pairing_invalid` ; le compagnon affiche « Code refusé » | 400 |
| Plus de 5 téléphones actifs sur le compte | `companion_limit` à la création du code | 409 |
| Jeton absent, inconnu ou révoqué | `companion_token_invalid` ; le compagnon efface son jeton et affiche « Appairage révoqué » | 401 |
| Jeton compagnon présenté sur une autre route (`/api/**` utilisateur) | Refusé (pas un JWT) | 401 |
| Jeton compagnon sur une route `/companion/**` non déclarée | Refusé (`denyAll`) | 403 |
| Révoquer le téléphone d'un autre compte | `not_found` | 404 |
| `after` illisible | Traité comme absent (curseur = maintenant) | 200 |
| Réseau coupé côté téléphone | Nouvel essai avec attente croissante (5 s → 5 min) ; l'écoute du mot continue | — |
| Synthèse vocale indisponible | L'annonce s'affiche en notification ; pas de fenêtre de réponse | — |

---

## Critères d'acceptation

- [x] Code d'appairage : 8 caractères, 10 minutes, usage unique, haché en base ; jeton haché, jamais rendu deux fois.
- [x] Le jeton compagnon n'ouvre **que** `GET /api/companion/announcements` ; un JWT n'ouvre pas cette route et le jeton n'ouvre aucune route utilisateur.
- [x] Révoquer rend le jeton invalide immédiatement (401 au prochain appel).
- [x] Isolation `user_id` : liste, révocation et annonces ne montrent que celles du compte ; 404 chez autrui.
- [x] Fin d'un tour via Jarvis ⇒ annonce rangée (texte selon les trois cas) ; tour tapé ⇒ aucune annonce ; aucun téléphone appairé ⇒ aucune annonce.
- [x] Alerte de veille ⇒ annonce avec la phrase de l'alerte et son lien.
- [x] Attente longue : rend dès qu'une annonce arrive, sinon après `wait` ; ≤ 10 éléments ; rien de plus vieux que 15 min ; premier appel sans rejeu.
- [x] Compagnon : appairage par code, jeton dans les préférences privées, boucle d'attente longue dans le service, synthèse vocale, fenêtre de réponse de 8 s qui ouvre `/jarvis` sur le lien ; 401 ⇒ jeton effacé.
- [x] Encart « Compagnon Android » de l'écran Jarvis : code, liste, révocation, parcours d'installation ; jetons `--cg-*`, Angular Material, aucune `window.confirm`.

---

## Contraintes de validation

| Champ | Règle |
|---|---|
| `code` | 8 caractères `A-Z2-9` sans `I O 0 1`, espaces et casse ignorés ; TTL 10 min ; usage unique |
| `name` (téléphone) | 1 à 80 caractères après `strip` ; absent ⇒ « Téléphone Android » |
| Téléphones actifs | 5 au plus par compte |
| `wait` | entier 0 à 30 (défaut 25) ; hors bornes ⇒ borné |
| `after` | ISO-8601 avec décalage ; illisible ⇒ ignoré |
| Texte d'annonce | ≤ 600 caractères (coupé avec « … ») |
| `link` | chemin interne (`/` sans `//`), ≤ 300 caractères, sinon `/jarvis` |
| Fraîcheur | annonces de moins de 15 minutes seulement ; purge des annonces de plus de 2 jours |

---

## Plan de test minimal

- **Unitaires backend** : `JarvisCompanionAnnouncementsTest` (texte : message prêt / phrase orale / « C'est fait. » ; coupe à 600 ; lien invalide) ; `JarvisCompanionPairingServiceTest` (code haché, TTL, usage unique, limite 5, jeton haché).
- **Intégration** (`JarvisCompanionApiIntegrationTest`) : appairage complet code → jeton → annonces ; 401 sans jeton / jeton révoqué ; JWT refusé sur `/companion/announcements` ; jeton compagnon refusé sur une route utilisateur ; attente longue rend tout de suite quand une annonce existe ; premier appel sans rejeu ; fin d'un tour via Jarvis ⇒ annonce (crochet `AtelierChatService`) ; alerte ⇒ annonce.
- **Isolation utilisateur** : B ne voit ni la liste ni les annonces de A, révocation chez A ⇒ 404.
- **Android** (`./gradlew :app:testDebugUnitTest`) : `ReplyWindow` (ouverte 8 s, premier mot, expiration), `Backoff` (5 s → 5 min), `AnnouncementFeed` (lecture de la réponse, curseur), `PairingCode` (normalisation).
- **Frontend** (`jarvis-companion-panel.component.spec.ts`) : code affiché, liste, révocation (confirmation Material), erreur 409.

---

## Impacts

- **Tables** (migration `192-jarvis-companion.xml`) : `jarvis_companion_devices`, `jarvis_companion_pairing_codes`, `jarvis_companion_announcements`.
- **Endpoints** : `POST /api/jarvis/companion/pairing-code`, `GET /api/jarvis/companion/devices`, `DELETE /api/jarvis/companion/devices/{id}` (JWT) ; `POST /api/companion/pair`, `GET /api/companion/announcements` (chaîne dédiée).
- **Backend** : `JarvisCompanion*` (paquet `jarvis.companion`), `CompanionSecurityConfig`, crochets dans `AtelierChatService` (réponse via Jarvis) et `JarvisWatchAlerter` (alerte) par mutateur optionnel.
- **Frontend** : `JarvisCompanionPanelComponent` (dans l'écran Jarvis), `JarvisService`, modèles.
- **Android** : `Prefs` (jeton), `MainActivity` (appairage), `CompanionService` (boucle, synthèse, fenêtre de réponse), `AnnouncementClient`, `WakeActivity` (lien), manifeste (`INTERNET`), cœur pur `core/`.

### Préoccupations transversales

- **Auth / Principal** — cochée. Composants impactés et vérifiés :
  - nouvelle chaîne `CompanionSecurityConfig` (`@Order(2)`, `securityMatcher("/companion/**")`) : `POST /companion/pair` et `GET /companion/announcements` déclarées une par une, `denyAll` pour le reste, rien posé dans le `SecurityContext` (même patron que D9 du runner) ;
  - `SecurityConfig` (chaîne principale, JWT) : inchangée ; les trois routes `/jarvis/companion/**` y sont authentifiées comme toute route utilisateur ;
  - `RunnerSecurityConfig` (`@Order(1)`, `/runner/**`), `RelaySecurityConfig` (`@Order(0)`, `/internal/**`), `McpResourceServerConfig` (`@Order(4)`, `/mcp/**`) : préfixes disjoints de `/companion/**`, inchangés ;
  - `JwtAuthenticationFilter`, `McpPersonalTokenAuthenticationFilter` : un jeton `jcp_…` n'est ni un JWT ni un jeton MCP ⇒ rejeté (test de non-régression).
- **Contexte tenant** — cochée : le jeton compagnon résout le `user_id` du téléphone (table `jarvis_companion_devices`) ; seul `JarvisCompanionFeedController` le fait ; toutes les lectures d'annonces sont filtrées par ce `user_id`.
- **Plans / limites** : non (aucun quota consommé : aucun appel modèle, synthèse sur l'appareil).
- **Navigation / routing** — cochée : le compagnon ouvre `/jarvis?sujet=…` ou `/jarvis?alerte=…` avec `source=compagnon&compagnon=…` ; ces paramètres existent déjà (F-231, SF-232-02) ; aucune route ni garde ajoutée.

---

## Hors périmètre

- Firebase / FCM (aucun service tiers : attente longue sur la gateway).
- Reconnaissance de la réponse sur le téléphone : la réponse est dictée dans l'écran Jarvis (F-231), ouvert par la fenêtre de réponse.
- iPhone, montre.
- Annonces d'autres événements que fin de tour via Jarvis et alerte de veille.

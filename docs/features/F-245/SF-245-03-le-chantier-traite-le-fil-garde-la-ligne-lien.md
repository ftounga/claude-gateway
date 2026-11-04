# Mini-spec — F-245 / SF-245-03 Le chantier traite, le fil garde la ligne-lien

## Identifiant
`F-245 / SF-245-03`

## Feature parente
`F-245` — Le fil central de Jarvis (cadrage `CADRAGE-F-245-le-fil-central-de-jarvis.md`, D5, D6, D10, §6)

## Statut
`done` (2026-11-04, PR #1573 backend + PR front) · **Estimation :** 1 j · **Branches :** `feat/SF-245-03-ligne-lien` (backend), `feat/SF-245-03-ligne-lien-front` (frontend) · **Dépend de :** SF-245-02

## Objectif
Une demande routée vers un sujet ou un chantier y est traitée, et le fil Jarvis en garde une ligne-lien vivante qui s'ouvre d'un toucher sur la réponse.

## Comportement attendu

### Cas nominal
1. **Création (D5)** : quand le cerveau rapide route une délégation vers un sujet (ou un chantier) **autre que le fil Jarvis**, et que ce fil n'a pas de tour vivant, un message `role = LINK` est écrit dans le **fil Jarvis** du compte : `ref_workspace_id` = le sujet cible (vérifié `findByIdAndUserId`), `ref_chantier_number` = le chantier ou `null`, `link_status = EN_COURS`, contenu `↗ Helios · chantier 3 — « relancer le déploiement » · en cours`. `via` et `brain` restent `null`.
2. **Fin du tour (D5)** : le tour Opus qui prend la reformulation de cette délégation (registre F-235) met la ligne-lien à jour à la fin : `link_status = FAIT`, `ref_message_id` = la réponse d'Opus, contenu `↗ Helios · chantier 3 — « … » · fait` suivi de la ligne `🎙` de la réponse (F-240, ≈ 25 mots) quand elle existe.
3. **Échec** : tour interrompu ⇒ `ECHEC` (« · interrompu ») avec `ref_message_id` ; tour en erreur (exception) ⇒ `ECHEC` (« · échec »), sans `ref_message_id`.
4. **Rejeu court (D6)** : dans le fil Jarvis, une ligne-lien est rejouée à Opus comme un texte utilisateur court `[Transmis à Helios · chantier 3 — « … » · fait — 🎙 …]` ; le contenu du chantier n'y est jamais. Le sanitiseur existant fusionne les messages `user` consécutifs.
5. **Chantier propre** : rien n'est ajouté au sujet cible (la demande reformulée y figure déjà comme message utilisateur, F-235).
6. **Mémoire du jour (D10)** : `via` nul ⇒ la ligne-lien n'entre ni dans `JarvisFastMemory` ni dans « Pendant ton absence ». Le contexte du cerveau rapide (bloc du fil Jarvis, D11) la montre étiquetée « Lien ».
7. **Vue** : `AtelierMessageResponse` porte un champ additif `link { workspaceId, chantier, messageId, status }` (absent hors `LINK`).
8. **Front** : dans l'Atelier (fil Jarvis), une ligne-lien est rendue comme une ligne cliquable (pas une bulle) ; un toucher ouvre `/atelier/{id}?chantier=N&message=M`. L'écran du sujet lit `?chantier=` (place ce chantier dans le volet de gauche) et `?message=` (fait défiler jusqu'à la réponse).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Sujet cible d'autrui / introuvable | aucune ligne-lien (le routage vérifie déjà le sujet) | — |
| Délégation vers le fil Jarvis lui-même | aucune ligne-lien | — |
| Délégation devenue précision d'un tour vivant | aucune nouvelle ligne-lien (la délégation en cours a la sienne) | — |
| Service du fil indisponible / écriture en panne | délégation inchangée, pas de ligne (best-effort, jamais bloquant) | 200 |
| Mise à jour d'une ligne d'un autre compte | ignorée (`findByIdAndUserId`) | — |
| `?chantier=` d'un sujet sans ce chantier | ignoré, le sujet s'ouvre normalement | — |
| `?message=` absent de l'historique affiché | aucun défilement | — |

## Contraintes de validation
| Champ | Règle |
|---|---|
| `link_status` | `EN_COURS` \| `FAIT` \| `ECHEC` (varchar 16) |
| libellé du lien | ≤ 120 caractères (coupé « … ») |
| ligne 🎙 recopiée | ≤ 300 caractères |

## Critères d'acceptation
- [x] Une délégation vers Helios, chantier 3, crée une ligne-lien `EN_COURS`, puis `FAIT` avec la ligne 🎙 et `ref_message_id` de la réponse.
- [x] L'historique du chantier 3 ne contient que la demande reformulée et la réponse, sans « bonjour » ni ligne-lien.
- [x] Un toucher ouvre le chantier 3 à la réponse (`/atelier/{id}?chantier=3&message=…`).
- [x] Un tour d'Opus suivant dans le fil Jarvis voit « [Transmis à Helios… fait…] » (rejeu).
- [x] Une ligne-lien vers un sujet d'autrui est impossible (`findByIdAndUserId` vide ⇒ rien).
- [x] Isolation A/B : la ligne-lien d'Alice n'apparaît pas chez Bob ; Bob ne peut pas la terminer.

## Plan de test
- **Unitaires** `JarvisLinkServiceTest` (création, sujet d'autrui, fil Jarvis cible, fin FAIT/ECHEC/interrompu, ligne 🎙, coupe du libellé, rendu du rejeu, mise à jour d'autrui ignorée) ; `JarvisFastBrainServiceTest` (lien créé à la délégation ciblée, aucun lien pour une délégation générale ou une précision).
- **Intégration** `JarvisLinkIntegrationTest` (Spring + H2 : cycle complet, échec, isolation A/B, chantier propre).
- **Rejeu** : test de `replayableHistory` avec une ligne `LINK`.
- **Frontend** : spec du terminal (ligne-lien rendue et cliquable) ; spec de l'Atelier (`?chantier=` place le chantier, `?message=` défile).
- **Non-régression** : suites complètes back et front.

## Impact
- **Table** `atelier_messages` : colonnes `ref_workspace_id uuid`, `ref_chantier_number int`, `ref_message_id uuid`, `link_status varchar(16)` — migration `245-atelier-messages-link.xml` (réversible).
- **Endpoints** : `GET /api/atelier/workspaces/{id}/chat` (champ additif `link`). Aucun nouvel endpoint.
- **Backend** : `AtelierMessage`, `AtelierMessageResponse`, `JarvisLinkService` (nouveau, `jarvis/thread`), `JarvisDelegationRegistry` (la reformulation porte l'id du lien), `JarvisFastBrainService.route`, `AtelierChatService` (prise de la reformulation, fin de tour, rejeu), `JarvisFastContextBuilder` (étiquette « Lien »).
- **Frontend** : `atelier.models.ts`, `thread-item.ts`, `atelier-terminal.component.*`, `atelier.component.ts`.

**Préoccupations transversales** : **Navigation / routing — oui**. Composants impactés : `app.routes.ts` (`atelier/:id`, inchangé : paramètres de requête seulement) ; `atelier.component.ts` (`ngOnInit` lit `?chantier=` / `?message=` ; la règle F-39 « l'URL ouvre, ne referme jamais » est conservée) ; `terminal-lane-pane` (inchangé, reçoit le chantier par la disposition) ; `?vue=fichiers` (inchangé, lu dans le même abonnement) ; sélecteur `/jarvis` (inchangé) ; `readAlert` (inchangé). Contexte tenant : toute lecture/écriture de lien filtre le `user_id` du principal. Auth, plans/limites : non.

## Arbitrages
- **Pas de ligne-lien pour une précision** (délégation versée dans un tour vivant) : la délégation en cours a déjà la sienne ; un tour vivant lancé au clavier n'en a pas. Réversible.
- **Registre en mémoire** : l'id du lien voyage avec la reformulation (patron F-235) ; un redémarrage laisse la ligne `en cours`. Une colonne de délégation aurait été plus robuste mais sans besoin mesuré. Réversible.
- **Contenu = texte affiché** ; le rejeu en dérive un texte court (pas de colonne de libellé). Réversible.
- **« Dis-m'en plus » (F-240 D9)** : l'écran garde la réponse du tour délégué qu'il a lui-même lancé ; aucun changement nécessaire ici.

## Hors périmètre
L'écran « Fil » de `/jarvis` (SF-245-04) ; le banc de routage et la compaction sans `FAST` (SF-245-05) ; la reprise d'une ligne restée `en cours` après un redémarrage ; la migration des messages passés.

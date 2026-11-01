# SF-225-03 — Correctif du point de situation

**Feature :** F-225 · **Statut :** done (2026-11-01) · **Estimation :** < 1 j · **Branche :** `feat/SF-225-03-situation-correctif` · **Dépend de :** SF-225-01, SF-225-02 (mergées), SF-227-03 (mergée)

## Objectif
Le point de situation s'ouvre au bon moment (absence réelle, jamais par-dessus un tour ni dans un volet), dit chaque mail une seule fois, se resserre sur un chantier, et le geste [Marquer envoyé] se défait.

## Comportement nominal
1. **Inactivité** = heures depuis le plus récent de :
   - votre **dernier message écrit** (`lastUserMessageAt`, nouveau) — les demandes **automatiques** enregistrées au rôle utilisateur (course autonome F-219, routine F-180 : marque « sans écran ouvert ») sont écartées ;
   - votre **dernière visite de ce terminal sur cet appareil** (`localStorage` `cg.situation.visits`, 100 terminaux au plus) — notée à l'entrée (après lecture de la précédente), en quittant le terminal et quand l'onglet passe en arrière-plan ; seul l'écran principal la note.

   `idleHours` (serveur) part désormais du dernier message écrit ; `lastActivityAt` (tous rôles) reste pour « Dernier échange il y a… ».
2. **Pas d'ouverture d'elle-même** : jamais dans un **volet** du split (F-221) ; jamais si un **tour tourne** dans le fil montré — `turnLive` (nouveau, serveur : fil commun ou chantier au focus, tour vivant ici ou chez un pair, course autonome) ou déjà visible à l'écran (tour, autorisation, question). Menu et `/situation` restent.
3. **Mails regroupés** par (type, destinataire, objet) — casse et espaces ignorés ; un encart sans destinataire ni objet reste seul. Une ligne par groupe : « proposé N fois » ; **envoyé** dès qu'une proposition l'est ; [Voir] et [Marquer envoyé] visent la proposition la plus récente.
4. **[Marquer envoyé] annulable** : une ligne « envoyé » porte **[Annuler]** (geste F-211 existant `DELETE …/sends/{index}`). La carte fait le geste elle-même et suit la réponse (plus de mise à jour optimiste) ; le terminal relit ses envois marqués et le compte des attentes.
5. **Filtre par chantier** : puces « Tout · Ch.N · titre · Au fil de l'eau » (si au moins un chantier ouvert). Il resserre **Chantiers**, **Mails et messages** (fil du message qui l'a proposé : `chantierNumber`, nouveau) et **Ce qui attend**. Ouverte dans un volet, la carte part sur le chantier du volet.
6. **« Ce qui attend » par chantier** : déjà livré par SF-227-03 (groupes par chantier puis « Au fil de l'eau ») — conservé, filtrable.
7. **Coupe à 4 000 caractères** : une réponse longue dont le bloc `<<essentiel>>` finit au-delà de la coupe est lue **depuis son ouverture** (l'essentiel F-126 n'est plus perdu) ; la coupe ne sépare jamais un émoji (paire de substitution).

## Contrat API (additif, rétrocompatible)
`GET /api/workspaces/{id}/situation` — inchangé, champs ajoutés :
| Champ | Type | Sens |
|---|---|---|
| `lastUserMessageAt` | ISO \| null | dernier message écrit par l'utilisateur (automatiques écartés) |
| `turnLive` | boolean | un tour tourne dans le fil que l'écran principal montre |
| `envois[].chantierNumber` | int \| null | fil du message qui a proposé l'encart |
| `idleHours` | int \| null | **sens modifié** : depuis `lastUserMessageAt` |

Un écran d'avant SF-225-03 garde un comportement sain (il lit `idleHours`).

## Erreurs
| Situation | Comportement |
|---|---|
| Stockage de l'appareil indisponible / illisible | inactivité = dernier message écrit seul |
| Fils actifs illisibles (serveur) | `turnLive = false` (best-effort) |
| [Marquer envoyé] / [Annuler] refusés | « Impossible de marquer ce message comme envoyé. » / « Impossible d'annuler. » ; la carte ne change pas |
| Terminal d'autrui | 404 (inchangé) |

## Contraintes de validation
Aucune saisie. Bornes : 20 derniers messages utilisateur relus ; 100 terminaux retenus par appareil ; 4 000 caractères par texte.

## Critères d'acceptation
- [x] Une course autonome qui a écrit cette nuit ne remet pas l'inactivité à zéro ; une visite récente sur l'appareil, si.
- [x] Aucune ouverture d'elle-même dans un volet, ni pendant un tour du fil montré.
- [x] Un mail reproposé 3 fois = une ligne « proposé 3 fois » ; envoyé si l'une l'est.
- [x] [Annuler] défait « envoyé » ; un geste refusé le dit sans rien changer.
- [x] Le filtre resserre chantiers, mails et attentes ; « Au fil de l'eau » montre ce qui n'a pas de chantier ouvert.
- [x] L'essentiel d'une réponse longue survit à la coupe.

## Plan de test
- Backend : `SituationServiceTest` (4 — coupe essentielle, coupe inchangée, émoji, demandes automatiques) ; `SituationApiIntegrationTest` (+1 : inactivité depuis le dernier message écrit, `turnLive`, `chantierNumber` ; isolation Bob → 404 inchangée). Suite complète.
- Frontend : `terminal-situation.component.spec` (11 : geste + annulation, refus, regroupement, filtre, inactivité, visites) ; `atelier-terminal.component.spec` (+4 : ouverture après 30 h, visite récente, tour en cours, volet). Suite complète + build.

## Impact
- Backend : `Situation` (+3 champs), `SituationService` (dernier message écrit, `turnLive` via `ChantierLanesService`, coupe), `AtelierMessageRepository` (+`findTop20ByWorkspaceIdAndUserIdAndRoleOrderByCreatedAtDesc`). **Aucune migration.**
- Frontend : `situation.models` (visites, inactivité, regroupement), `terminal-situation.component` (filtre, groupes, [Annuler], gestes faits par la carte), `atelier-terminal` (ouverture conditionnée, visites, relecture des envois).
- Préoccupations transversales : **navigation** — aucune route ; **tenant** — lectures sous `user_id` inchangées (`requireOwned` d'abord). Auth, plans : aucune.

## Arbitrages (gates réversibles, décidés par défaut)
- **Groupe « envoyé » dès qu'une proposition l'est** (plutôt que l'état de la plus récente) : l'objet du correctif est de ne plus relancer pour un mail déjà parti ; un mail réellement différent porte un autre objet. Réversible (une ligne).
- **Le filtre ne touche pas** dernier échange, pièces et suites (ils ne sont pas rangés par chantier côté serveur). Réversible.
- **`turnLive` = fil commun ou chantier au focus** (pas les autres chantiers) : une course d'un autre chantier pendant l'absence est précisément ce que la carte doit raconter.
- **Demandes automatiques reconnues par leur marque** « sans écran ouvert » (texte posé par `AutonomyTurnRunner` / `RoutineTurnRunner`), sans migration ; une relance de routine sans marque compte encore comme activité (risque résiduel, mineur).

## Hors périmètre
Récit rédigé par le modèle ; inactivité partagée entre appareils (la visite reste par appareil, par décision du cadrage).

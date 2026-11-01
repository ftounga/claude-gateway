# Mini-spec — F-234 / SF-234-07 — S1 messages à envoyer

## Identifiant
`F-234 / SF-234-07`

## Feature parente
`F-234` — L'écran vivant de Jarvis (cadrage §5, §8.2 ; vague B, famille 6 du §2 : 217 messages à envoyer sur 30 j)

## Statut
`in-review`

## Date de création
2026-11-01

## Branche Git
`feat/SF-234-07-messages-a-envoyer`

---

## Objectif

> Un message pour un tiers se voit naître : l'enveloppe (ou la bulle) se forme, le destinataire et l'objet
> s'écrivent, puis elle file dans « À envoyer (n) » ; `email_me` part « pour vous » ; une preuve jointe montre
> l'objectif photo — le corps n'est jamais rendu.

## Comportement attendu

- **Blocs ` ```mail ` / ` ```message `** lus au fil des deltas (SF-234-02), **balise avec attributs** acceptée
  (` ```mail attente=<key> `, ` ```message preuves=<id1>,<id2> `, MESSAGE_BLOCK_DOCTRINE F-203/F-212) — l'ancienne
  lecture exigeait une balise nue et manquait ces blocs.
- Scène **S1** : enveloppe (mail) ou bulle (message) qui se forme ; « À : Zahi » (nœud de la personne allumé s'il est
  connu), « Objet : … » ; à la fin du bloc : « Mail prêt · à envoyer », l'enveloppe file, chip **« À envoyer (n) »**
  = `toSendCount` de l'univers + messages finis pendant le tour, avec « +1 ». **Le corps n'est ni lu ni rendu.**
- `preuves=` sur la balise ⇒ **objectif photo** (F-212) et « Preuve jointe ».
- `email_me` (« Courriel · objet ») ⇒ S1 « Courriel pour vous », « Pour vous » + objet (pas de chip : il ne passe
  pas par « À envoyer »).
- `capturer_preuves` (étape `bash` « captures de preuve : titre ») ⇒ S1 « Preuves capturées » + titre, pas S3.
- Registre : `email_me`, `capturer_preuves` → `S1`.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Bloc sans « À : » ni « Objet : » | enveloppe seule, chip |
| Destinataire inconnu de l'univers | écrit, aucun nœud allumé |
| Tour coupé avant la fin du bloc | « en préparation », aucun +1 |

## Critères d'acceptation
1. Bloc ` ```mail ` avec « À : Zahi » ⇒ enveloppe + nœud Zahi + compteur +1 à la fin du bloc.
2. Corps jamais rendu (test : ni dans l'état, ni dans le DOM).
3. `preuves=` ⇒ objectif photo ; `email_me` ⇒ « pour vous ».

## Plan de test
- `jarvis-scenes-messages.spec.ts` : bloc mail avec attributs, personne allumée, +1, corps absent ; bulle et preuve ;
  `email_me` ; captures de preuve ; rendu (enveloppe, À, Objet, « À envoyer (2) » → « (3) » +1, corps absent du DOM).
- Non-régression : `jarvis-scene-reader.spec.ts` (bloc mail SF-234-02), suite `jarvis/**`, garde passerelle, build.

## Impacts
- **Tables / endpoints** : aucun. **Back** : aucun.
- **Front** : lecteur (balise à attributs, données S1, captures de preuve), scène (rendu S1, compteur), registre.

## Préoccupations transversales
Aucune.

## Arbitrages (réversibles)
- Balise à attributs reconnue (correctif du lecteur SF-234-02, sans quoi la plupart des blocs réels étaient manqués).
- `email_me` sans chip « À envoyer » (il ne passe pas par la liste à envoyer).

## Hors périmètre
« Marquer envoyé » (F-211) : vu au retour par l'univers relu ; ouvrir le message depuis Jarvis.

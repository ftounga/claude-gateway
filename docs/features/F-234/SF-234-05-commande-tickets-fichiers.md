# Mini-spec — F-234 / SF-234-05 — S3 commande, S4 tickets, S5 feuillets et loupe (D11)

## Identifiant
`F-234 / SF-234-05`

## Feature parente
`F-234` — L'écran vivant de Jarvis (cadrage §5, §6.2, §8.2 ; vague B, familles 1 et 2 du §2 : 3 834 commandes dont
≈ 777 Jira/Confluence, ≈ 950 fichiers sur 30 j)

## Statut
`in-review`

## Date de création
2026-11-01

## Branche Git
`feat/SF-234-05-commande-tickets-fichiers`

---

## Objectif

> Les actions les plus fréquentes de l'agent ont leur scène dédiée : la commande en filigrane avec ses deux dernières
> lignes masquées (S3), les tickets Jira / Confluence en cartes (S4), les fichiers lus ou écrits qui s'empilent et
> la loupe d'une recherche (S5).

## Comportement attendu

- **Registre** : `bash`, `bash_output`, `kill_shell`, étape `bash` → `S3` ; `read_file`, `list_files`, `write_file`,
  `edit_file`, `multi_edit`, `grep`, `glob`, `search_files`, étapes `read`/`write`/`list`/`search` → `S5`.
- **S3 — Terminal en filigrane** : la commande (déjà visible au terminal, 80 car., masquée) puis, relayées par
  `onOutput` (F-38), les **2 dernières lignes** non vides de la sortie, **masquées** (`maskSecrets` : `Authorization`,
  `Bearer`, `password=`, `sk-…`, jetons GitHub/Slack, clés AWS, `-u user:mdp`), 80 car. au plus. Une nouvelle commande
  repart de zéro. Seule la fin de la sortie est gardée (2 000 car.) — jamais tout.
- **S4 — Cartes de tickets** : une commande `bash` qui vise `jira`, `atlassian`, `confluence`, `acli`, `/rest/api/`
  ou `/wiki/` devient « Jira · consultation » ou « Confluence · page » ; les clés `ABC-123` de la commande — et de la
  réponse tant que la scène est là — en cartes (6 au plus), rafale regroupée.
- **S5 — Feuillets / loupe** : lecture, écriture (filet pointillé), liste : les noms courts des fichiers s'empilent
  (6 au plus, le plus récent souligné), libellé de rafale « 3 fichiers lus » ; recherche : la loupe et le motif.
- **Mode sobre** (retirer S3) : porté par SF-234-11 (interrupteur unique pour tout l'écran, D9).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Sortie sans saut de ligne final | la ligne en cours compte |
| Commande Jira sans clé | carte absente, la commande s'écrit (détail masqué) |
| Chemin vide | scène sans feuillet, libellé seul |
| Sortie après une autre scène | ignorée (seule la commande en cours a sa sortie) |

## Critères d'acceptation
1. Sortie avec `Authorization: Bearer abc` ⇒ masquée ; 2 lignes au plus ; 80 car. au plus.
2. `curl …/rest/api/2/issue/FORGE-41` ⇒ carte FORGE-41 ; URL `…/wiki/…` ⇒ « Confluence ».
3. Trois lectures rapprochées ⇒ une scène « 3 fichiers lus » avec les trois noms.
4. Une recherche ⇒ la loupe et le motif.

## Plan de test
- Unitaires `jarvis-scenes-commande.spec.ts` : registre, S3 (masquage, coupe, remise à zéro), S4 (Jira,
  Confluence, clés, réponse), S5 (pile, loupe), rendu des trois scènes.
- Non-régression : suite `jarvis/**` ; garde passerelle `JarvisSceneRegistryGuardTest` ; `npm run build`.

## Impacts
- **Tables / endpoints** : aucun. **Back** : aucun.
- **Front** : registre (scènes S3/S5), lecteur (`SceneData`, `commandOutput`), fil (`output`), scène (rendus S3/S4/S5),
  `jarvis.component.ts` (relais `onOutput`).

## Préoccupations transversales
Aucune (pas d'auth, de tenant, de plan, de route ; préfixe non touché, rien ne part au modèle).

## Arbitrages (réversibles)
- S4 déduit de la commande (heuristique du cadrage) plutôt que d'un type passerelle ; clés lues aussi dans la réponse
  pendant la scène.
- La garde passerelle seule (et non la suite backend complète) est rejouée : aucun fichier backend modifié.

## Hors périmètre
Mode sobre (SF-234-11) ; titres de tickets (non disponibles sans appel) ; contenu des fichiers (jamais).

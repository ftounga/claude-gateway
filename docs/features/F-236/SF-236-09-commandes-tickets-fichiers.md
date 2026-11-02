# Mini-spec — [F-236 / SF-236-09] Commandes, tickets, fichiers (S3 + N6, S4, S5 + N9 + N11)

## Identifiant
SF-236-09

## Feature parente
F-236 — Jarvis en 3D (cadrage §9.2 S3/S4/S5, §11 N6/N9/N11, §15 itération 2 ; familles 1-2 de l'usage F-234,
≈ 4 800 appels / 30 j)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-09-commandes-tickets-fichiers`

---

## Objectif

Donner leur rendu 3D dédié aux trois scènes les plus fréquentes — la commande sur le poste, les tickets, les
fichiers — sur le socle SF-236-08.

## Comportement attendu

### Cas nominal

1. **S3 Commande** : la caméra cadre le **poste** (client du sujet) ; un **flux de particules remonte** du poste au
   cœur (N6), d'autant plus dense que la commande parle ; un panneau mono en filigrane montre la commande (`$ …`) et
   ses **2 dernières lignes**, **80 c.**, **secrets masqués** (D11 ; défense en profondeur : `maskSecrets` est
   réappliqué).
2. **S4 Tickets** : **cartes holographiques en éventail** (≤ 6) sorties du client en arc (90 ms d'écart, `rebond`),
   tournées vers l'extérieur ; la carte **nommée dans la phrase dite** par Jarvis s'illumine (liseré blanc chaud,
   ligne en gras) ; **mémoire de session** : les tickets vus plus tôt sur ce poste (< 30 min) restent avec elles, en
   gris (les plus récents d'abord retenus).
3. **S5 Fichiers** : feuillets translucides **empilés** (≤ 6) près du sujet ; l'écriture = le dernier feuillet en
   blanc vif ; la recherche = **loupe** qui balaie, le motif s'écrit ; **nuage du dépôt** (N9) : les fichiers de la
   **session** pour ce sujet, un amas stable par dossier, lecture orange / écriture blanc chaud ; un **document** lu
   (pdf, docx, md…) se lit en **pages qui défilent** (N11).
4. **Mémoire de session** (`SessionMemory`, pure) : tenue par la scène 3D le temps de l'ouverture ; elle ne retient que
   des noms déjà affichés par le lecteur (clés de tickets, noms de fichiers) ; aucun appel réseau, rien ne sort de
   l'appareil.
5. Les scènes S3, S4, S5 quittent `SGEN_3D_IDS` (garde SF-236-08 respectée).

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Poste ou sujet inconnu / non posé | Panneau autour du cœur ; aucun flux, aucun nuage. |
| Ligne de sortie trop longue ou contenant un secret | Coupée à 80 c. (« … ») ; secret masqué (« ••• »). |
| Plus de 6 tickets / fichiers | Les 6 plus récents. |
| Ticket non mentionné depuis 30 min | Il quitte la mémoire de session. |
| Recherche ou liste | N'entre pas dans le nuage du dépôt. |

---

## Critères d'acceptation

- [x] CA1 — S3 : 2 lignes au plus, 80 c., secrets masqués ; flux poste → cœur dont le débit croît avec la sortie (tests).
- [x] CA2 — S4 : ≤ 6 cartes, clés exactes ; carte nommée illuminée ; mémoire de session bornée à 30 min (tests).
- [x] CA3 — S5 : pile, écriture en blanc, loupe, pages qui défilent pour un document ; nuage limité à la **session**
  et au sujet courant, **aucun appel réseau** (objet pur, aucune injection HTTP) (tests).
- [x] CA4 — Captures téléphone + PC de chaque scène (commande, tickets, écriture, lecture d'un document, recherche),
  non vides, sans erreur console.

## Contraintes de validation

| Élément | Règle |
|---|---|
| Sortie de commande | 2 lignes, 80 c., `maskSecrets` (D11). |
| Tickets / feuillets | ≤ 6 à l'écran ; mémoire ≤ 24 tickets, TTL 30 min ; nuage ≤ 64 points. |
| Particules | Pool fixe PC 1 024 / téléphone 128 (T9) ; flux ≤ 64 / 20. |

## Plan de test

- Unitaires : `scenes3d/jarvis-scenes-poste.spec.ts` (S3, S4, S5, N6, N9, N11, mémoire de session, registre).
- Garde du registre (SF-236-08) verte.
- Isolation utilisateur : sans objet (aucune lecture nouvelle) ; aucun appel réseau (mémoire pure).
- Visuel : `scene-commande`, `scene-tickets`, `scene-fichiers`, `scene-lecture`, `scene-recherche`.

## Périmètre

### Impacté
`scenes3d/jarvis-scenes-poste.ts`, `scenes3d/jarvis-session-memory.ts` (nouveaux), `scenes3d/index.ts`, modèle,
fixture ; `engine/jarvis-effects-3d.ts` (flux, cartes, loupe, nuage, pool de particules), `engine/jarvis-panels-*`
(pages qui défilent, panneaux au-dessus des étiquettes), `engine/jarvis-engine.ts` (la caméra cadre le nœud de la
scène) ; `jarvis-stage-director.ts`, `jarvis-stage-3d.component.ts` (mémoire), `jarvis.component.ts` (clés de tickets
de la phrase dite) ; banc.

### Hors scope (explicite)
- Le **mur de tickets** permanent et détachable (SF-236-18) ; ici, la mémoire s'exprime dans la scène S4.
- Aucun endpoint, aucune table, aucun appel IA, aucune consigne touchée.

## Préoccupations transversales
Aucune cochée.

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | La caméra cadre le **nœud de la scène** (S3/S4 : le poste ; S5 : le sujet), sinon le premier nœud cité. | Cadrage « zoom sur le poste » ; sans cela la caméra restait sur le sujet. | Garder le seul nœud cité. | Oui. |
| A2 | La carte nommée = clé de ticket présente dans la **phrase dite** par Jarvis (`spoken`). | Le lecteur ne signale que des nœuds de l'univers ; la phrase est déjà à l'écran. | Illuminer la dernière carte. | Oui. |
| A3 | Cartes en verre discret (liseré, fond à 6-14 %) plutôt qu'en aplat lumineux. | Sous le bloom, l'aplat devenait une tache blanche (vu au banc). | Aplat opaque. | Oui. |
| A4 | Le dossier d'un fichier est lu dans le **détail** de la scène (chemin déjà masqué/tronqué). | Le lecteur ne garde que le nom court dans `files`. | Ajouter un champ au lecteur (hors périmètre §19). | Oui. |

# F-141 — L'aiguilleur de sujet à la racine : dire où va l'info, créer le sujet, reclasser

> Cadrage du 2026-09-22, à la demande du PO. **Cadrage seul : livraison sur go.** Né d'un cas réel CAGIP
> (21/09) : en travaillant **à la racine**, l'agent a **mal rangé** un journal (`lzi/` au lieu de
> `data-platform/`), et le PO ne l'a su qu'en demandant « où as-tu rangé ? ».

## 1. Le problème (constat réel)
- Le PO **débute la mission** : il ne sait pas toujours si une info est **transverse** (carte du poste),
  relève d'un **sujet existant**, ou est un **nouveau sujet** — et parfois c'est **un mix** (le fil avec
  Rémi touchait à la fois `data-platform` ET `lzi`).
- En travaillant **à la racine**, l'agent **devine** la destination et **range en silence** (héritage
  F-125). Résultat : **mauvais aiguillage** (`lzi/` vs `data-platform/`) **découvert tard**.
- **La racine devine et se tait** — c'est ça le défaut, pas le nombre de terminaux (les terminaux par
  sujet, eux, n'ont **aucune ambiguïté** de routage ; on les garde — cf. audit).

## 2. Principe
> **À la racine, l'aiguilleur ne devine plus en silence : il propose une destination, la rend visible,
> demande si c'est ambigu, et n'agit qu'après validation. Il sait créer un sujet (avec sa gouvernance) ou
> répartir un mix.**

Ne casse **pas** F-125 (« la carte se tient en silence ») : la *plomberie* (marqueurs, comptabilité de
promotion) reste invisible. Mais **la DESTINATION d'un fait durable — quel sujet — est une information qui
concerne le PO**, pas de la plomberie : elle devient **visible et validable**. C'est la nuance clé.

## 3. Rappel du modèle existant (on s'appuie dessus, on ne réinvente pas)
- **Projets = dossiers** sous la racine du poste ; chacun a **`STATE.md`** (jetable) + **`PLAN-ACTION.md`**
  (carte du projet). Racine = **carte du poste** (`README/acces/reseau/plateformes/donnees/exploitation`).
- **Semis de gouvernance** : `GovernancePackageSeeder` + `GovernanceActivationService` créent
  `STATE.md`/`PLAN-ACTION.md`/skills dans un projet, **idempotent** (« crée ce qui manque, n'écrase jamais »).
- **Sujets Radar (F-99)** = topics transverses (alias, preuves), **différents** des dossiers projet.
  `RadarSubjectProjectService` relie déjà sujet Radar ↔ projet.
- Doctrine `savoir-durable/regles.md` : « ce qui survit au projet → carte du poste ; ce qui meurt avec lui
  → projet ».

## 4. Comportement attendu
### 4.1 Aiguillage d'un sujet (au fil de l'eau, depuis la racine)
Quand le PO amène un **nouveau sujet** (ou une info dont la place est floue) au terminal **racine**,
l'agent **propose une destination**, **visible**, parmi :
- **Sujet existant** : « ça relève de `data-platform` » (nommé, avec pourquoi) ;
- **Transverse** : « c'est un fait du poste → carte racine (`plateformes.md`…) » ;
- **Nouveau sujet** : « je propose de créer le dossier `X` » ;
- **Mix** : « ça touche `data-platform` ET `lzi` → voici la répartition proposée (A→data-platform,
  B→lzi, C→racine) ».
Puis **il attend la validation** du PO (ou une correction) **avant d'écrire**. En cas d'ambiguïté réelle,
il **pose la question** au lieu de deviner.

### 4.2 Création d'un sujet sur validation
Si le PO valide « nouveau sujet » : l'agent **crée le dossier** et **hérite la gouvernance**
(`STATE.md`/`PLAN-ACTION.md` + skills) via le **semis existant** (`GovernancePackageSeeder`, idempotent) —
comme un projet créé par l'écran. Le sujet est prêt, gouverné, et l'info y est déposée.

### 4.3 Annonce de destination (tout rangement durable)
À chaque **fait durable** rangé, l'agent **dit où** en une ligne factuelle (« rangé dans
`data-platform/PLAN-ACTION.md` »). Pas de plomberie, juste la **destination**. Le PO voit, à chaud, si
c'est bon.

### 4.4 Reclassement en un geste
Une entrée mal rangée se **déplace** vers le bon sujet d'un geste (« reclasser vers `data-platform` »),
avec trace. (L'agent le proposait déjà à la main dans le cas réel — on l'outille.)

## 5. Ce qu'on NE fait pas (issu de l'audit)
- **On ne supprime PAS les terminaux par sujet** : ils sont la **garantie anti-mauvais-rangement** (dans un
  sujet, zéro décision de routage) et le **contexte focalisé/bon marché** (évite le coût N² + le mélange
  clients). L'aiguilleur est un **complément de capture/route à la racine**, pas un remplacement.
- **On ne rétablit pas** la plomberie bruyante de fin de tour (F-125) — seule la **destination** devient
  visible.

## 6. Découpage
| SF | Titre | Contenu |
|---|---|---|
| **SF-141-01** | **Annonce de destination + demande si ambigu** | À chaque fait durable rangé, l'agent nomme la **destination** (sujet/fichier) dans sa réponse ; si la destination est **ambiguë**, il **demande** au lieu de deviner. Prompt/doctrine (`savoir-durable`) + rendu. Ne narre pas la plomberie (F-125 intact). |
| **SF-141-02** | **Aiguillage : proposer sujet existant / transverse / nouveau / mix** | À la racine, sur un sujet nouveau/flou, l'agent **classe et propose** (existant nommé, transverse, nouveau, ou **répartition d'un mix**), et **attend validation**. S'appuie sur les sujets/dossiers connus + sujets Radar. |
| **SF-141-03** | **Créer le sujet + gouvernance héritée sur validation** | Sur « oui, nouveau sujet » : créer le **dossier** + semer `STATE.md`/`PLAN-ACTION.md`/skills via `GovernancePackageSeeder` (idempotent), puis y déposer l'info. |
| **SF-141-04** | **Reclasser une entrée** | Déplacer un fait mal rangé vers le bon sujet en un geste, avec trace (annulable si possible). |

**Ordre** : 141-01 (visibilité, faible risque) → 141-02 (aiguillage) → 141-03 (création) → 141-04 (reclasser).

## 7. Critères d'acceptation
- À la racine, un fait durable est rangé **avec sa destination annoncée** ; une destination ambiguë
  **déclenche une question**, pas une supposition.
- Un nouveau sujet peut être **proposé, validé, créé** avec sa **gouvernance héritée** (STATE/PLAN-ACTION).
- Un **mix** est présenté comme une **répartition** validable (pas un rangement muet dans un seul).
- Une entrée mal rangée se **reclasse** en un geste.
- **Non-régression** : F-125 (pas de plomberie narrée), terminaux par sujet inchangés, semis idempotent
  (jamais d'écrasement), coût/contexte des terminaux de sujet inchangés.

## 8. Préoccupations transversales
- **Confidentialité/cloisonnement** : création de dossier et rangement restent sous le poste
  (`user_id`+`host_id`) ; pas de mélange entre clients.
- **Coût** : l'aiguilleur route/annonce — il ne fait pas tenir tout le contexte à la racine (on évite le
  N²). Le travail profond reste dans le terminal du sujet.
- **Composants** : doctrine `savoir-durable` (annonce destination + aiguillage), `buildSystemPrompt`,
  contrôles de fin de tour (F-125, ne pas casser), `GovernancePackageSeeder`/`GovernanceActivationService`
  (création+semis), `WorkspaceService` (dossier projet), lien sujets Radar (`RadarSubjectProjectService`),
  rendu du fil (destination visible), geste de reclassement (front + écriture carte).

## 9. Références
- Audit racine vs terminaux par sujet (cette conversation, 2026-09-22).
- F-48 (poste = unité, projets = dossiers), F-51/F-52 (paquets + semis), F-99 (sujets Radar),
  F-125 (la carte se tient en silence — nuance : destination = info, pas plomberie).

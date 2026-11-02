# Mini-spec — [F-236 / SF-236-19] Inspecteur de nœud + recherche « montre-moi »

## Identifiant
SF-236-19

## Feature parente
F-236 — Jarvis en 3D (cadrage §13 NF-7 / NF-8, §15 ; itération 3 « Le cockpit PC »)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-19-inspecteur-recherche`

---

## Objectif

Toucher n'importe quel nœud ouvre sa fiche complète (inspecteur), et taper « edenred bloqué » applique localement
filtres et caméra — sans tour, au clavier.

## Comportement attendu

### Cas nominal

1. **Inspecteur (NF-7)** — `inspect` (pur) : la fiche d'un nœud **lue dans l'univers** : poste (état, mission,
   sujets hors terminal, chantiers, à envoyer ; liens sujets et personnes), sujet (poste, terminal, chantiers avec
   leur météo ; « Passer sur ce sujet »), chantier / étape (sujet, phase, avancement, étape en cours, météo ; liens
   sujet, personnes attendues, chantier), personne (poste, rôle, attentes, message prêt ; liens chantiers).
2. Ouvert par : un nœud touché dans l'univers 3D (`touched3d`), une ligne des panneaux (univers, météo, personnes,
   étagère), un lien de la fiche, un résultat de recherche. Un lien ou une recherche fait aussi **regarder** le
   nœud (`look` : même cadrage qu'un toucher, tenu 8 s puis retour, T-Z4). Rien ne navigue ; seul « Passer sur ce
   sujet » choisit (D12). Panneau droit, en tête ; « Fermer ».
3. **Recherche (NF-8)** — `search` (pur) : un champ en tête du panneau gauche. Les mots désignant un filtre fermé de
   « Montrer » (bloqué, connecté, déconnecté, à envoyer, étapes en cours, clients) donnent un résultat « Montrer »
   **résolu exactement par `resolveShow`**, restreint au client nommé s'il existe (« edenred bloqué ») ; les mots
   restants cherchent les noms (postes, sujets, chantiers par titre ou numéro, personnes ; accents et casse
   ignorés). ≤ 8 résultats. Un « Montrer » choisi est appliqué **localement** (comme SF-236-16) ; un nœud choisi ⇒
   fiche + caméra.
4. **Clavier complet** : `/` ou Ctrl+K (⌘K) donne le focus (le panneau se déplie s'il était replié), ↓ ↑ parcourent,
   Entrée applique, Échap efface puis rend le focus ; combobox ARIA (`aria-activedescendant`, `listbox`).

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Nœud disparu de l'univers (relu) | Fiche absente. |
| Recherche < 2 caractères | Aucun résultat. |
| Aucun résultat | « Rien ne correspond. » |
| Filtre sans élément (« edenred bloqué » sans blocage) | Le « Montrer » est omis ; les noms restent. |
| `/` tapé dans un champ de saisie | Ignoré (aucun détournement). |

---

## Critères d'acceptation

- [x] CA1 — Fiche fidèle à l'univers pour chaque type de nœud ; chaque nœud posé par l'univers 3D a une fiche
  (test sur la disposition).
- [x] CA2 — Recherche = filtres `jarvis-show.ts` (compte identique à `resolveShow`) + noms, sans tour (tests).
- [x] CA3 — Clavier complet (test : `/`, ↓, Entrée, Échap).
- [x] CA4 — Un lien / un résultat de nœud fait regarder la caméra (`look`, test) ; rien ne navigue.
- [x] CA5 — Captures `cockpit-inspecteur`, `cockpit-recherche` non vides, sans erreur console.

## Contraintes de validation

| Élément | Règle |
|---|---|
| Résultats | ≤ 8 |
| Raccourcis | `/` hors saisie ; Ctrl+K / ⌘K partout |

## Plan de test

- Unitaires : `hud/jarvis-inspector.spec.ts` (fiches des 5 types, couverture des nœuds posés, recherche).
- Composant : `hud/jarvis-cockpit.component.spec.ts` (inspecteur, liens, caméra, fermeture ; clavier).
- Isolation utilisateur : sans objet (univers déjà filtré `user_id`, aucun appel).
- Visuel : banc `cockpit-inspecteur`, `cockpit-recherche`.

## Périmètre

### Impacté
`hud/jarvis-inspector.ts`, `hud/jarvis-cockpit.component.{ts,html,scss}`, `jarvis-engine.contract.ts` (`look`),
`engine/jarvis-engine.ts`, `jarvis-stage-3d.component.ts`, `jarvis-stage3d.testing.ts`,
`jarvis.component.{ts,html}` (`touched3d` ⇒ fiche, `showLocal(filter, client)`), banc.

### Hors scope (explicite)
- Fiche au téléphone (volets : SF-236-20). Recherche plein texte dans les conversations.
- Aucun endpoint, table, appel IA ni consigne.

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Navigation / routing | Non | Aucune route ; seul « Passer sur ce sujet » passe par `touchSubject` existant. |

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | La recherche reconnaît des **mots** de filtre (bloqué, connecté…) plutôt que la syntaxe `🖥 montrer:`. | Langage naturel au clavier ; même résolution. | Syntaxe exacte de l'agent. | Oui. |
| A2 | Un mot restant qui ne désigne aucun client n'annule pas le filtre. | « zahi bloqué » montre les blocages et propose Zahi. | Aucun résultat. | Oui. |
| A3 | `look` tenu 8 s puis retour (T-Z4), comme un toucher. | Cohérence des gestes. | Caméra figée. | Oui. |
| A4 | Fiche du nœud touché en 3D aussi pour un sujet (en plus du choix). | Le PO voit ce qu'il vient de choisir. | Fiche hors sujets. | Oui. |

# F-31 — Cadrage : unifier les deux chemins d'écriture

> Statut : **arbitré le 2026-08-31**. Demande du PO : « il y a une incohérence entre la façon dont
> les fichiers sont modifiés via l'explorateur et via le terminal. Ça doit être la même façon.
> Limite, si je modifie dans un, je le vois dans l'autre. »

---

## 1 — L'état réel, lu dans le code

Un projet Git a **trois lieux** où un fichier peut vivre :

| Lieu | Qui y écrit | Qui le lit |
|---|---|---|
| **Clone** dans la sandbox | Claude, pendant un tour | Claude seul |
| **Stockage objet** | la resynchronisation, après chaque tour | l'explorateur (en priorité) |
| **GitHub** | le push de Claude (SF-31-04) · le commit de l'écran (SF-31-08) | l'explorateur, à défaut du stockage |

La circulation est **à sens unique** :

- Claude écrit → resync → stockage → **l'explorateur le voit**. ✅
- L'écran écrit → GitHub, sur une branche → **Claude ne le voit jamais**. ❌

Le clone est monté à l'ouverture de session depuis `workspace.gitBranch`
(`AtelierSessionService.openGitSession`) et n'est **jamais** réalimenté depuis le stockage. Les
fichiers du stockage ne sont montés que pour les projets **d'archive** (`FileMount`).

## 2 — Pourquoi on en est là

SF-31-03 a interdit l'écriture dans le stockage sur un projet Git, pour une bonne raison : écrire
dans le stockage pendant que Claude travaille sur le clone crée deux vérités divergentes. SF-31-08 a
contourné l'interdiction en écrivant **ailleurs** — sur GitHub. Le problème de divergence est résolu
vu du dépôt ; il est déplacé, pas supprimé, vu de l'utilisateur.

## 3 — Les options

### Option A — Le stockage redevient le lieu du travail en cours *(retenue)*
L'écran écrit dans le stockage, **comme Claude après resync**. Le clone d'une session Git reçoit, au
montage, les fichiers du stockage qui diffèrent. Une seule publication : un commit qui prend tout ce
qui est non publié, quelle qu'en soit l'origine.

- **Ce qu'on gagne** : un modèle mental unique — *le stockage porte le travail en cours, la branche
  porte le publié* — et la phrase du PO devient vraie dans les deux sens.
- **Ce qu'on paie** : les éditions de l'écran n'atteignent une session **déjà ouverte** qu'à sa
  prochaine ouverture. Le fournisseur ne permet pas d'injecter un fichier dans une session en cours.
- **Sur l'objection de SF-31-03** : elle tombe, parce que le clone n'est plus une source concurrente
  — il est **alimenté** par le stockage au montage, au lieu de l'ignorer.

### Option B — GitHub comme source unique
Claude ferait un `git pull` en début de tour ; l'écran continuerait de commiter directement.

- **Rejetée** : elle n'unifie que le travail **publié**. Le travail non publié de Claude (dans le
  stockage, avant push) resterait invisible à l'écran — ou obligerait Claude à pousser à chaque
  tour, ce qui transformerait chaque essai en commit public.

### Option C — La sandbox comme source unique
L'écran écrirait dans la session en cours.

- **Rejetée** : imposerait une session ouverte — donc facturée — pour éditer une ligne de texte, et
  interdirait toute édition hors session.

## 4 — Décision

**Option A.** Trois conséquences assumées :

1. **L'interdiction d'écriture de SF-31-03 est levée** pour l'explorateur, et remplacée par
   l'alimentation du clone au montage. C'est un renversement explicite, pas un oubli.
2. **Une seule publication** : le commit via l'API GitHub (SF-31-08) devient le chemin unique et
   prend *tout* le non-publié. Le push depuis la sandbox (SF-31-04) reste disponible pour Claude,
   mais l'écran ne l'utilise plus.
3. **Les éditions n'atteignent pas une session déjà ouverte.** L'écran doit le dire — et proposer la
   réinitialisation, qui remontera le clone à jour.

## 5 — Découpage

| ID | Contenu |
|----|---------|
| SF-31-12 | L'explorateur écrit dans le **stockage** sur un projet Git (levée du refus `git_workspace_read_only` pour l'écriture de fichier) ; la publication prend tout le non-publié, écran et Claude confondus. |
| SF-31-13 | Au montage d'une session Git, **déposer dans le clone** les fichiers du stockage qui en diffèrent, après le clone et avant le premier tour. |

## 6 — Ce qui reste hors périmètre

- Injecter un fichier dans une session **déjà ouverte** : le fournisseur ne l'expose pas.
- La résolution automatique d'un conflit quand Claude et l'utilisateur modifient le même fichier
  entre deux publications : le dernier écrit gagne dans le stockage, et le diff du tour le montre.
- La suppression de fichier depuis l'explorateur sur un projet Git.

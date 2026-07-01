# Skill : feature-splitter

---

## 1. Nom

`feature-splitter`

---

## 2. Mission

Découper une feature en sous-fonctionnalités (subfeatures) petites, indépendantes, testables, mergeables et ordonnées par priorité et dépendances.

---

## 3. Quand utiliser ce skill

- Quand une feature est définie à un niveau trop macro pour être développée directement
- Quand un fichier `feature-template.md` vient d'être rempli et que la liste des subfeatures est vide ou vague
- Quand le delivery-orchestrator doit séquencer le développement d'une feature
- Quand une subfeature semble trop large (> 2 jours estimés)

---

## 4. Quand ne pas utiliser ce skill

- Quand la feature est déjà découpée en subfeatures claires et indépendantes
- Quand il s'agit d'un bug isolé sans composante feature
- Quand la demande concerne une subfeature déjà définie — utiliser `story-writer` à la place

---

## 5. Inputs attendus

```
- Titre de la feature
- Objectif fonctionnel (en une phrase)
- Périmètre V1 : ce qui est inclus, ce qui est exclu
- Stack concernée : backend / frontend / les deux
- Tables et endpoints pressentis (optionnel, si déjà identifiés)
- Contraintes ou dépendances connues
```

Le fichier `project-governance/templates/feature-template.md` peut être fourni en entrée s'il est déjà partiellement rempli.

---

## 6. Préconditions

- [ ] L'objectif fonctionnel de la feature est formulé en une phrase claire et non ambiguë
- [ ] Le périmètre V1 est délimité (inclus / exclus)
- [ ] La stack cible est identifiée
- [ ] La feature est cohérente avec `docs/ARCHITECTURE_CANONIQUE.md`
- [ ] Les questions ouvertes bloquantes de `docs/OPEN_QUESTIONS.md` sont identifiées

Si une précondition est manquante → signaler et demander les informations avant de continuer.

---

## 7. Processus d'exécution

**Étape 0 — Contrôle préalable : une feature ou plusieurs ?**

Avant tout découpage, vérifier que la demande ne couvre pas plusieurs features déguisées en une seule.

Appliquer les signaux de détection suivants :

| Signal | Exemple | Action |
|--------|---------|--------|
| Plusieurs comportements visibles distincts et indépendants | "upload ET consultation ET notification" | MULTI-FEATURES |
| Plusieurs responsabilités métier séparables | "validation des fichiers ET déclenchement analyse IA" | MULTI-FEATURES |
| Plusieurs écrans indépendants sans flux commun | "page upload ET page résultat ET page historique" | MULTI-FEATURES |
| Plusieurs entités principales impactées indépendamment | "documents ET analyses ET jobs" en flux disjoints | MULTI-FEATURES |
| Un seul flux avec plusieurs étapes séquentielles | "upload → validation → persistance" | FEATURE UNIQUE |
| Un seul objectif utilisateur avec plusieurs sous-étapes | "créer un dossier (titre + domaine + description)" | FEATURE UNIQUE |

**Si multi-features détectée :**
```
REFUS [feature-splitter]
Motif : La demande couvre plusieurs features distinctes.
Features identifiées :
  - [Feature A — description courte]
  - [Feature B — description courte]
Action requise : Traiter chaque feature séparément.
Référence : CLAUDE.md — Détection des demandes multi-features
```

Ne pas produire de découpage avant que la séparation soit effectuée.
Si la séparation est ambiguë → escalader au delivery-orchestrator.

---

**Étape 1 — Analyser la feature**
Lire l'objectif, le périmètre et les contraintes. Identifier les grandes responsabilités fonctionnelles.

**Étape 2 — Identifier les axes de découpage**
Appliquer les critères suivants pour découper :
- Une subfeature = une responsabilité fonctionnelle unique (créer, lire, modifier, déclencher, afficher...)
- Une subfeature est développable sans bloquer les autres (ou ses dépendances sont explicites)
- Une subfeature produit quelque chose de testable et de mergeable seul
- Estimation max : 2 jours de dev

**Étape 3 — Lister les subfeatures**
Pour chaque subfeature :
- Attribuer un ID : `SF-XX-YY`
- Rédiger un titre court (action + objet : "Créer un dossier", "Lister les dossiers du workspace")
- Identifier les dépendances sur d'autres subfeatures
- Estimer la taille (S / M — jamais XL)
- Identifier la stack concernée (backend / frontend / les deux)

**Étape 4 — Ordonner**
Proposer un ordre d'implémentation basé sur :
- Dépendances techniques (la persistance avant l'API, l'API avant l'UI)
- Valeur livrée (les cas nominaux avant les cas d'erreur avancés)
- Risques de couplage

**Étape 5 — Signaler les risques**
Identifier et signaler :
- Les subfeatures couplées qui risquent de créer des conflits
- Les questions ouvertes de `docs/OPEN_QUESTIONS.md` qui bloquent une subfeature
- Les subfeatures qui touchent à des zones sensibles (auth, isolation workspace, pipeline IA)

---

## 8. Output attendu

Un tableau de subfeatures ordonné + un commentaire sur les risques.

```markdown
## Découpage — [FEAT-XX] Titre de la feature

### Subfeatures

| ID | Titre | Stack | Taille | Dépendances | Ordre |
|----|-------|-------|--------|-------------|-------|
| SF-XX-01 | [titre] | Backend | S | — | 1 |
| SF-XX-02 | [titre] | Backend | M | SF-XX-01 | 2 |
| SF-XX-03 | [titre] | Frontend | S | SF-XX-02 | 3 |
| SF-XX-04 | [titre] | Backend + Frontend | M | SF-XX-01 | 4 |

### Ordre d'implémentation recommandé

1. SF-XX-01 — [raison]
2. SF-XX-02 — [raison]
3. SF-XX-03 — [raison]
4. SF-XX-04 — [raison]

### Risques et signalements

- [Couplage entre SF-XX-02 et SF-XX-04 : attention aux conflits sur la table X]
- [SF-XX-03 dépend de la question ouverte "mécanisme de notification" — voir docs/OPEN_QUESTIONS.md]
- [SF-XX-04 touche à l'isolation workspace — vérification obligatoire]
```

---

## 9. Règles strictes

- Une subfeature ne peut pas couvrir plus d'une responsabilité fonctionnelle
- Une subfeature estimée XL (> 2 jours) doit être redécoupée — aucune exception
- Les subfeatures qui touchent à l'auth ou à l'isolation workspace sont toujours signalées
- Si une question ouverte de `docs/OPEN_QUESTIONS.md` bloque une subfeature, elle est signalée explicitement et la subfeature est marquée `blocked`
- Le skill ne produit pas de mini-spec — il produit uniquement le découpage

---

## 10. Critères de qualité

- Chaque subfeature a un titre formulé comme une action (verbe + objet)
- Chaque subfeature est testable seule
- L'ordre d'implémentation est justifié
- Les dépendances sont explicites et non circulaires
- Les risques sont signalés, pas ignorés

---

## 11. Cas de refus ou d'escalade

| Situation | Action |
|-----------|--------|
| Objectif fonctionnel absent ou flou | REFUS — demander l'objectif en une phrase |
| Périmètre non délimité | REFUS — demander inclus / exclus |
| Feature trop vague pour être découpée | ESCALADE vers delivery-orchestrator — clarifier la feature avant |
| Question ouverte bloquante non tranchée | SIGNALEMENT + marquer la subfeature `blocked` |
| Découpage demandé sur un bug isolé | REFUS — utiliser directement une mini-spec |

---

## 12. Exemple d'utilisation

**Input :**
```
Feature : Gestion des dossiers juridiques
Objectif : Permettre à un avocat de créer, consulter et archiver ses dossiers
Périmètre V1 inclus : création, listage, détail, changement de statut
Périmètre V1 exclus : suppression, export, partage entre workspaces
Stack : Backend + Frontend
```

**Output attendu :**
```
| SF-01-01 | Créer un dossier (endpoint + persistance) | Backend | S | — | 1 |
| SF-01-02 | Lister les dossiers du workspace | Backend | S | SF-01-01 | 2 |
| SF-01-03 | Consulter le détail d'un dossier | Backend | S | SF-01-01 | 3 |
| SF-01-04 | Changer le statut d'un dossier | Backend | S | SF-01-01 | 4 |
| SF-01-05 | Interface liste des dossiers | Frontend | M | SF-01-02 | 5 |
| SF-01-06 | Interface détail d'un dossier | Frontend | M | SF-01-03 | 6 |
```

---

## 13. Format de réponse attendu

```markdown
## Découpage — [FEAT-XX] [Titre]

### Subfeatures
[tableau]

### Ordre d'implémentation recommandé
[liste numérotée avec justification]

### Risques et signalements
[liste des couplages, questions ouvertes, zones sensibles]

### Prochaine étape
→ Utiliser `story-writer` pour produire la mini-spec de chaque subfeature, en commençant par SF-XX-01.
```

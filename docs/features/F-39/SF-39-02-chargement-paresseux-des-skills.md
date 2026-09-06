# Mini-spec — F-39 / SF-39-02 — Les skills annoncés, pas déversés

## Identifiant

`F-39 / SF-39-02`

## Feature parente

`F-39` — L'Atelier comme harnais

## Statut

`ready`

## Date de création

2026-09-06

## Branche Git

`feat/SF-39-02-skills-paresseux`

---

## Objectif

> Ne plus déverser le **corps** des skills dans la consigne système : n'y annoncer que leur
> **chemin et leur description**, le corps étant lu à la demande par l'agent avec `read_file`.

---

## Déclencheur

Lot 1 du cadrage F-39 (`docs/features/F-39/CADRAGE.md` §5). SF-39-01 a mis la consigne système en
cache ; il reste que cette consigne **contient tout**, jusqu'à 40 000 caractères de corps de skills
dont l'agent n'utilise en général aucun. Deux effets :

1. **Le préfixe caché est cher à écrire.** L'écriture de cache se paie une fois par tour ; l'écrire
   sur 10 000 tokens de skills jamais lus est une dépense sans contrepartie.
2. **Le préfixe est instable.** La consigne est plafonnée à 40 000 caractères et tronquée
   *au fil de l'arborescence* : ajouter un fichier au projet peut changer l'ordre de parcours, donc
   le point de coupe, donc invalider le cache d'un tour à l'autre — sans que rien ne le signale.

Le catalogue (chemin + description) est, lui, court et stable : c'est le préfixe qu'on veut cacher.

---

## Comportement attendu

### Cas nominal

La consigne système contient, à la place des corps de skills :

```
--- Skills du projet (lis le fichier pour le mode d'emploi complet) ---
- .claude/skills/deploy.md : Déploie le projet sur l'environnement cible.
- skills/review.md : Passe la checklist de revue avant toute PR.
```

suivie d'une phrase de mode d'emploi : un skill se lit avec `read_file` au moment où il sert.

**La description** est cherchée dans cet ordre, sur le fichier lu :

1. clé `description:` d'un entête YAML (`---` … `---`) en tête de fichier ;
2. sinon, première ligne non vide qui n'est ni un délimiteur d'entête, ni un titre Markdown ;
3. sinon, aucune description — seul le chemin est annoncé.

Elle est ramenée à une ligne (retours et espaces multiples réduits) et bornée à
**200 caractères** (suffixe `…` si coupée).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Aucun skill dans le projet | Aucune section « Skills », consigne inchangée par ailleurs | 200 |
| Skill illisible (droits, binaire, runner muet) | Skill **ignoré** ; les autres sont annoncés | 200 |
| Skill sans description exploitable | Chemin seul, sans `: description` | 200 |
| Plus de 50 skills | Les 50 premiers annoncés, puis une ligne `… et N autres` | 200 |
| Description contenant des retours à la ligne | Aplatie en une ligne — le catalogue reste une ligne par skill | 200 |

### Observabilité

Le compteur d'amorçage journalisé (F-38 / SF-38-08, une ligne par tour) compte **les mêmes
lectures qu'avant** : le fichier est toujours lu pour en extraire la description. Ce qui change
est ce qu'on **envoie au modèle**, pas ce qu'on lit sur la machine.

---

## Critères d'acceptation

- [ ] Le corps d'un skill n'apparaît **jamais** dans la consigne système.
- [ ] Chaque skill trouvé apparaît sur une ligne `- <chemin> : <description>`.
- [ ] La description vient de l'entête YAML `description:` quand il existe.
- [ ] À défaut, elle vient de la première ligne utile, titres Markdown exclus.
- [ ] Une description est toujours sur **une seule ligne** et ne dépasse pas 200 caractères.
- [ ] Un skill illisible est ignoré sans faire échouer la construction de la consigne.
- [ ] Le catalogue est borné à 50 entrées, le surplus étant annoncé en une ligne.
- [ ] `CLAUDE.md` reste inliné intégralement — ce sont les conventions, pas un mode d'emploi.
- [ ] La consigne reste bornée à 40 000 caractères.
- [ ] Isolation `user_id` inchangée : toutes les lectures passent par les mêmes chemins qu'avant.

---

## Périmètre

### Hors scope (explicite)

- Le retrait de `list_files` / `search_files` et la lecture paginée — **SF-39-05 / SF-39-06**.
- Toute notion de skill « activable » côté produit (déclaration, marketplace) : hors périmètre F-39.
- Le chemin Managed Agents, qui construit sa consigne ailleurs (`AgentSystemPrompt`) — non touché.

---

## Contraintes de validation

| Champ | Obligatoire | Valeurs | Normalisation |
|-------|-------------|---------|---------------|
| Description d'un skill | Non | <= 200 caractères | Aplatie en une ligne, coupée avec `…` |
| Nombre de skills annoncés | — | <= 50 | Surplus résumé en une ligne |
| Consigne système | — | <= 40 000 caractères | Tronquée (inchangé) |

---

## Technique

### Endpoint(s)

Aucun. La forme de l'appel sortant ne change pas ; seul le **texte** de la consigne change.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] **Non applicable.**

### Classes impactées

| Classe | Changement |
|--------|-----------|
| `atelier/AtelierChatService` | `buildSystemPrompt` annonce un catalogue de skills au lieu d'inliner les corps ; extraction de description |

### Composants Angular

Aucun.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés et vérification |
|--------------|-----------|-----------------------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | Mêmes appels `readOptional` / `safeTree`, mêmes filtres `user_id` |
| Plans / limites | Non | Le décompte de tokens ne change pas de règle ; le volume baisse, ce qui est l'objet |
| Navigation / routing | Non | — |

---

## Plan de test

### Tests unitaires

- [ ] `AtelierChatServiceSystemPromptTest` — le corps d'un skill n'est pas dans la consigne.
- [ ] `AtelierChatServiceSystemPromptTest` — la description YAML est reprise.
- [ ] `AtelierChatServiceSystemPromptTest` — à défaut d'entête, la première ligne utile sert de
      description ; un titre `#` ne compte pas.
- [ ] `AtelierChatServiceSystemPromptTest` — description multi-ligne aplatie et bornée à 200.
- [ ] `AtelierChatServiceSystemPromptTest` — skill illisible ignoré, les autres restent annoncés.
- [ ] `AtelierChatServiceSystemPromptTest` — aucun skill ⇒ aucune section « Skills ».
- [ ] `AtelierChatServiceSystemPromptTest` — `CLAUDE.md` toujours inliné.

### Tests d'intégration

- [ ] Couvert par `AtelierChatApiIntegrationTest` : contrat d'endpoint inchangé.

### Isolation workspace

- [x] Applicable — aucun nouveau chemin d'accès ; tests d'isolation existants verts.

---

## Dépendances

### Subfeatures bloquantes

- `SF-39-01` — done.

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

**D1 — On lit toujours le fichier, on n'en envoie qu'une ligne.** Extraire la description impose
de lire l'entête ; sans lecture partielle côté runner (SF-39-06), on lit le fichier entier et on
jette le corps. Le coût réseau est inchangé, le coût **token** — le seul qui croît en N² — s'effondre.

**D2 — `CLAUDE.md` reste inliné.** Ce sont les conventions que l'agent doit respecter sans qu'on
les lui demande, pas un mode d'emploi qu'il irait chercher. Le rendre paresseux le rendrait
facultatif de fait.

**D3 — Le catalogue est borné à 50 entrées.** Une borne explicite vaut mieux qu'une troncature
au caractère près au milieu d'une ligne : le point de coupe devient prévisible, donc le préfixe
cacheable.

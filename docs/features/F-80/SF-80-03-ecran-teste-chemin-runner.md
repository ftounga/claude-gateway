# Mini-spec — F-80 / SF-80-03 — L'écran teste le chemin du runner

## Identifiant

`F-80 / SF-80-03`

## Feature parente

`F-80` — Le runner derrière un proxy qui déchiffre le TLS

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-80-03-ecran-teste-chemin-runner`

---

## Objectif

Le test d'accès réseau de la mise en service ne conclut plus « la voie est libre » sur la foi d'un
`curl` : la commande porte une **mise en garde nommée**, et le runner reçoit un drapeau `--check`
qui rend enfin possible un test empruntant **exactement** son chemin.

---

## Comportement attendu

### Cas nominal

1. **Étape 1 — Vérifier l'accès réseau.** Sous la commande `curl`, une mise en garde permanente,
   adaptée au système d'où la page est consultée :

   > Sous Windows, `curl.exe` valide le certificat avec le magasin **Windows**, le runner avec celui
   > de la **JVM**. Un `200` ici ne garantit donc pas le runner : sur un poste dont le proxy
   > déchiffre le TLS, `curl` passe et le runner échoue.

   Sous macOS/Linux la même mise en garde nomme les magasins de ce système (`/etc/ssl/certs`).

2. **Branche `200`.** La conclusion « ce terminal atteint la passerelle » est complétée : elle dit
   ce qu'elle ne prouve pas, et renvoie au contrôle de vol du runner (étape 5) comme **seul** test
   de référence.

3. **Étape 5 — Lancer le runner.** Une troisième commande apparaît, « Pour vérifier sans appairer » :
   le contrôle de vol seul, `--check`, qui **ne consomme aucun code d'appairage**.

4. **Fiche « Pour votre DSI » (SF-45-03).** Sa section `INTERCEPTION TLS` est réécrite : le runner
   lit désormais le magasin du poste, donc **il n'y a rien à demander** quand la racine
   d'inspection y est installée — ce qui est le cas habituel sur un poste géré.

5. **Runner — `--check`** : contrôle de vol, diagnostic TLS et déclaration de transparence, puis
   sortie. Aucun appairage, aucune connexion, aucun jeton. Code `0` si la gateway répond, `5` sinon.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| `--check` sans `--gateway` | Erreur d'usage, code `2` — inchangé |
| `--check` sans `--root` | **Accepté** : le dossier courant fait office de racine, aucune racine n'étant requise pour un contrôle de vol |
| `--check` avec un `--root` inexistant | Erreur d'usage, code `2` — la validation ordinaire s'applique dès que la racine est fournie |
| `--check` et gateway injoignable | Code `5`, avec le diagnostic TLS de SF-80-01 s'il s'applique |
| Système inconnu (ni Windows, ni macOS) | La mise en garde reste affichée, formulée sans nommer de magasin Windows |

---

## Critères d'acceptation

- [ ] La mise en garde est affichée **en permanence** sous la commande, pas seulement en cas d'échec.
- [ ] Elle **nomme** les deux magasins : celui de `curl` sur ce système, et celui de la JVM.
- [ ] Sous Windows elle nomme le magasin **Windows** ; ailleurs, `/etc/ssl/certs`.
- [ ] La branche `200` dit ce qu'un `200` ne prouve pas et renvoie au contrôle de vol du runner.
- [ ] La commande `--check` est proposée à l'étape de lancement, et **ne porte aucun code
      d'appairage**.
- [ ] `--check` n'exige pas `--root`.
- [ ] `--check` sort sans appairer, sans ouvrir de connexion et sans écrire de jeton.
- [ ] La fiche DSI porte une section « inspection TLS » qui dit que le runner lit le magasin du
      poste, et ce qui reste à faire quand la racine n'y est pas.
- [ ] Aucun registre de couleur nouveau : uniquement les jetons `--cg-*` existants et les classes
      déjà présentes dans le composant.

---

## Périmètre

### Hors scope (explicite)

- Faire exécuter le test depuis le navigateur : une page ne lit pas le terminal (limite assumée de
  F-45).
- Désactiver la vérification TLS.
- Modifier le parcours en étapes, ou l'ordre des étapes.
- Le proxy à authentification NTLM/Kerberos (F-55, F-59).

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs | Normalisation |
|---|---|---|---|
| `--check` | Non | drapeau seul, ou `true`/`1`/`yes`/`oui` | minuscules, `trim` |
| `CLAUDE_RUNNER_CHECK` | Non | idem | idem |
| Racine par défaut sous `--check` | — | dossier courant du processus | chemin absolu normalisé |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants impactés

| Composant | Modification |
|---|---|
| `frontend/.../runner-pairing-dialog.component.ts` | `tlsWarning()`, `checkCommand()`, fiche DSI réécrite sur l'inspection TLS |
| `frontend/.../runner-pairing-dialog.component.html` | Mise en garde sous la commande, complément de la branche `200`, commande `--check` à l'étape 5 |
| `runner/.../RunnerConfig.java` | `--check` / `CLAUDE_RUNNER_CHECK`, racine facultative dans ce mode |
| `runner/.../RunnerMain.java` | Sortie après le contrôle de vol quand `--check` est posé |

### Composants Angular

- `RunnerPairingDialogComponent` — mise en garde TLS, commande de contrôle de vol, fiche DSI.

---

## Plan de test

### Tests unitaires (frontend)

- [ ] `tlsWarning('windows')` nomme le magasin Windows **et** celui de la JVM
- [ ] `tlsWarning('macos')` / `('other')` nomment `/etc/ssl/certs`, jamais « Windows »
- [ ] `checkCommand()` porte `--check` et `--gateway`, et **aucun** `--code`
- [ ] `itDepartmentSheet()` décrit l'inspection TLS avec la lecture du magasin du poste
- [ ] `itDepartmentSheet()` ne contient toujours **aucune** donnée du projet

### Tests unitaires (runner)

- [ ] `--check` seul n'avale pas l'argument suivant
- [ ] `--check` sans `--root` est accepté ; la racine vaut le dossier courant
- [ ] `--check` avec un `--root` inexistant reste une erreur d'usage
- [ ] `--check` n'est pas actif par défaut
- [ ] `CLAUDE_RUNNER_CHECK=true` vaut le drapeau

### Tests d'intégration (frontend)

- [ ] Le dialogue affiche la mise en garde dès l'ouverture de l'étape 1
- [ ] La branche `200` porte le renvoi au contrôle de vol du runner
- [ ] La commande `--check` est rendue à l'étape de lancement

### Isolation utilisateur

- [x] Non applicable — la fiche DSI et les commandes sont construites côté écran, sans aucune donnée
      du projet (D6 de SF-45-03), et le runner reste un client local.

---

## Dépendances

### Subfeatures bloquantes

- `SF-80-01` — **done** (le diagnostic que `--check` affiche)
- `SF-80-02` — **done** (ce que la fiche DSI dit désormais de l'inspection TLS)

### Questions ouvertes impactées

- [x] `OQ-17` — tranchée le 2026-09-12 ; la fiche DSI est réécrite en conséquence.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | — |
| Navigation / routing | Non | — |
| **Codes de sortie du runner** | **Oui** | `RunnerMain.execute` — `--check` réutilise les codes existants (`0` joignable, `2` usage, `5` injoignable). Aucun code nouveau, aucun code réaffecté : les appelants existants (lanceurs `.cmd`/`.command`, qui testent `errorlevel 1`) ne changent pas de comportement. |

---

## Notes et décisions

- **D1 — La mise en garde est permanente, pas conditionnelle.** Elle sert précisément dans le cas où
  l'utilisateur obtient `200` et conclut que le réseau est hors de cause. L'afficher seulement en
  cas d'échec la rendrait invisible à qui en a besoin.
- **D2 — `--check` est ajouté, et c'est un arbitrage.** La note du cadrage est exacte : le runner
  n'avait **pas** de drapeau `--check`, et l'écran ne devait pas prétendre le contraire. Sans lui,
  le seul test empruntant le chemin du runner consommait un **code d'appairage**, qui expire en
  5 minutes — donc le test de référence coûtait un aller-retour à chaque essai. `--check` le rend
  gratuit et répétable. Réversible : c'est un drapeau en plus, aucun comportement existant ne change.
- **D3 — `--check` ne rend pas `--root` obligatoire.** Un contrôle de vol ne touche aucun fichier ;
  exiger une racine pour le lancer aurait ajouté un obstacle à l'outil censé en retirer un. Dès que
  `--root` est fourni, il est validé comme d'habitude — on n'assouplit rien, on n'exige pas.
- **D4 — La commande `--check` vit à l'étape 5, pas à l'étape 1.** L'étape 1 précède le
  téléchargement : le runner n'est pas encore sur la machine, la commande y serait inapplicable. La
  mise en garde, elle, est bien à l'étape 1 — c'est là qu'on conclut à tort.
- **D5 — La fiche DSI change de conclusion.** Elle prescrivait
  `-Djavax.net.ssl.trustStore=<fichier>`. Depuis SF-80-02 le runner lit le magasin du poste : sur un
  poste géré où la racine d'inspection est installée, **il n'y a plus rien à demander**. La fiche le
  dit, et ne garde une demande que pour le cas contraire.
- **D6 — Aucun registre de couleur nouveau.** La mise en garde réutilise `pairing-note` et
  `pairing-hint`, déjà présentes, et les jetons `--cg-*` du design system.

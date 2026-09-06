# Mini-spec — F-38 / SF-38-18 — Dire avec quels droits le runner agit

## Identifiant

`F-38 / SF-38-18`

## Feature parente

`F-38` — Exécution sur machine connectée (runner local)

## Statut

`livrée` — PR #252, mergée le 2026-09-06

## Date de création

2026-09-06

## Branche Git

`feat/SF-38-18-privileges-du-runner`

---

## Objectif

> Rendre visible — en console, à l'écran et dans la documentation — le fait que **le runner agit avec
> les droits de l'utilisateur qui l'a lancé**, et signaler explicitement le cas où ces droits sont
> ceux de l'administrateur.

---

## Déclencheur

Question posée pendant la préparation du banc d'essai : *« même si je suis sur une machine qui a les
droits admin, il refusera quand même ? »*

Vérification faite : **rien ne le dit nulle part**. Ni `runner/README.md`, ni le cadrage F-38, ni
l'écran d'appairage ne mentionnent les privilèges. Et la seule phrase qui s'en approche est
ambiguë — le README annonce « aucun droit administrateur », ce qui parle de l'**installation**, pas
de ce que le runner peut faire une fois lancé.

Or le comportement réel est simple et mérite d'être dit :

| Situation | `sudo …` |
|---|---|
| Lancé par un utilisateur, `sudo` demande un mot de passe | **échoue** — le runner ferme l'entrée standard du processus (`BashTool`), donc pas de tty, pas de saisie |
| `sudo` configuré `NOPASSWD` | **passe** |
| Runner lancé en `root` | **passe**, et `sudo` n'est même pas nécessaire |

L'échec du premier cas est un **effet de bord** de la fermeture de stdin, pas une garde. Le présenter
comme une protection serait faux, et cette subfeature existe pour ne pas laisser croire l'inverse.

---

## Ce qui est délibérément écarté

**Refuser de démarrer en `root`.** Tentant, mais faux : dans un **conteneur**, `root` est
l'utilisateur par défaut. Or poser un runner dans un conteneur — sur un cluster, dans une CI — est un
usage naturel de F-38, et un refus le casserait pour protéger d'une erreur que l'avertissement suffit
à signaler. On informe, on n'interdit pas.

---

## Comportement attendu

### Au démarrage du runner

La console affiche déjà l'espace de travail et la gateway. Elle affiche désormais aussi **avec quels
droits** le runner tourne :

```
runner-claude — /home/francky/dev/runner-claude
utilisateur : francky
```

Et lorsque ces droits sont ceux de l'administrateur, l'avertissement est explicite et se voit :

```
⚠  Ce runner tourne en root : Claude agira avec les droits de l'administrateur
   sur cette machine. Lancez-le plutôt avec votre compte habituel, sauf si vous
   savez pourquoi vous faites autrement (conteneur, projet appartenant à root).
```

### À l'appairage

Le runner déclare `elevated` (booléen) en plus de son `label` et de son `rootName`. **Champ
additif** : un runner antérieur ne l'envoie pas, et l'appairage reste valide.

### À l'écran

Là où la machine est nommée — l'en-tête du projet et la **porte de confirmation** — l'information
apparaît quand elle est vraie. C'est au moment où l'on autorise une commande qu'elle compte : savoir
qu'elle s'exécutera en `root` change la décision.

### Détection

Lecture de `/proc/self/status` (champ `Uid:`, premier entier = uid réel) : exacte, sans processus
externe, sans dépendance. Repli sur `user.name` quand ce fichier n'existe pas (macOS, Windows).
Sur Windows, la notion diffère et n'est pas traitée : la détection rend simplement « non élevé ».

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `/proc/self/status` illisible | Repli sur `user.name` ; jamais d'exception |
| `Uid:` absent ou malformé | Considéré comme non élevé ; le runner démarre |
| Runner antérieur (pas de champ `elevated`) | L'écran n'affiche rien de particulier |
| Détection impossible | Aucun avertissement plutôt qu'un faux avertissement |

**Aucun chemin de cette subfeature ne peut empêcher le runner de démarrer.** Une information sur les
droits ne doit jamais coûter la connexion.

---

## Critères d'acceptation

- [ ] La console affiche l'utilisateur sous lequel le runner tourne, à chaque démarrage.
- [ ] En `root`, un avertissement explicite s'affiche, qui dit **ce que ça implique** et **quoi faire**.
- [ ] La détection lit `/proc/self/status` et retombe sur `user.name` si absent.
- [ ] Une détection en échec ne produit **aucun** avertissement et n'empêche pas le démarrage.
- [ ] L'appairage transmet `elevated` ; un runner qui ne l'envoie pas reste accepté.
- [ ] L'écran signale la machine élevée à l'endroit où l'on autorise une commande.
- [ ] `README.md` du runner distingue « aucun droit admin pour **installer** » de « le runner agit
      avec **vos** droits ».
- [ ] Isolation `user_id` inchangée.

---

## Périmètre

### Hors scope

- Refuser le démarrage en `root` (voir §Ce qui est délibérément écarté).
- Toute restriction de privilège dans le runner (`setuid`, capabilities, bac à sable local) : ce
  serait une autre feature, et une promesse qu'on ne pourrait pas tenir sur toutes les plateformes.
- Windows et sa notion d'élévation.

---

## Contraintes de validation

| Champ | Obligatoire | Valeurs | Normalisation |
|-------|-------------|---------|---------------|
| `elevated` (appairage) | Non | booléen | absent ⇒ `false` |

---

## Technique

### Endpoint(s)

`POST /runner/pair` accepte un champ additif `elevated`. Aucun endpoint créé.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `workspaces` | UPDATE | Nouvelle colonne `runner_elevated` (booléen, nullable) |

### Migration Liquibase

- [x] **Oui** — `053-workspaces-runner-elevated.xml` : colonne nullable, rollback inclus, aucune
      donnée existante touchée.

### Classes impactées

| Classe | Changement |
|--------|-----------|
| `runner/Privileges` | **Nouveau** — détection, sans exception possible |
| `runner/RunnerMain` | Affichage au démarrage, avertissement en `root` |
| `runner/PairingClient` | Transmet `elevated` |
| `backend runner/dto/PairRequest` | Champ additif |
| `backend runner/RunnerPairingService` | Enregistre l'élévation sur le workspace |
| `backend atelier/Workspace` | Colonne `runnerElevated` |
| `frontend atelier-terminal` | Mention dans l'en-tête et dans la demande d'autorisation |
| `runner/README.md` | Section « Avec quels droits » |

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| **Contexte tenant** | **Oui** | L'élévation est écrite sur le workspace **du jeton appairé**, jamais sur un identifiant venu du client : même chemin que `rootName` (SF-38-15), déjà lié au couple utilisateur/workspace par le code d'appairage. |
| Plans / limites | Non | Aucune consommation, aucun gate |
| Navigation / routing | Non | — |

---

## Plan de test

### Tests unitaires (runner)

- [ ] `PrivilegesTest` — `Uid:\t0\t0\t0\t0` ⇒ élevé ; `Uid:\t1000\t…` ⇒ non élevé.
- [ ] Fichier absent ⇒ repli sur le nom d'utilisateur (`root` ⇒ élevé).
- [ ] Contenu malformé ⇒ non élevé, aucune exception.
- [ ] La détection ne lève jamais, quel que soit l'état du système.

### Tests unitaires (backend)

- [ ] L'appairage enregistre `elevated` sur le workspace.
- [ ] Un appairage sans le champ laisse la valeur à `false` et reste accepté.

### Tests frontend

- [ ] La mention n'apparaît que lorsque la machine est élevée.

### Isolation workspace

- [x] Applicable — l'écriture passe par le workspace du code d'appairage, jamais par un identifiant
      fourni.

---

## Dépendances

- `SF-38-15` (source `LOCAL`, `rootName` à l'appairage) — done : cette subfeature emprunte le même
  canal, déjà éprouvé.

---

## Notes et décisions

**D1 — Informer, ne pas interdire.** Voir §Ce qui est délibérément écarté. Un refus de démarrer en
`root` casserait l'usage en conteneur, qui est légitime.

**D2 — L'avertissement dit quoi faire, pas seulement ce qui ne va pas.** « Vous tournez en root » ne
sert à rien seul ; « lancez-le avec votre compte habituel, sauf si vous savez pourquoi vous faites
autrement » se traduit en geste.

**D3 — La mention remonte jusqu'à la porte de confirmation.** C'est le seul endroit où elle change
une décision : autoriser `rm -rf build` n'a pas le même poids selon les droits sous lesquels la
commande s'exécutera. L'afficher uniquement au démarrage du runner reviendrait à la dire une fois,
loin du moment où elle compte.

**D4 — La fermeture de stdin n'est pas une garde de sécurité, et le dire.** Elle fait échouer un
`sudo` interactif, ce qui est heureux, mais c'est un effet de bord. La documentation doit décrire ce
qui protège réellement — la porte de confirmation, l'audit, les exclusions, le coupe-circuit — et ne
pas laisser croire que le runner bride les privilèges.

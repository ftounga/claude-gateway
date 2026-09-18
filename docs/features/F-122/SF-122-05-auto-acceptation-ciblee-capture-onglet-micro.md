# Mini-spec — [F-122 / SF-122-05] Auto-acceptation ciblée capture d'onglet + micro Teams sur le Chrome managé

> Template : `project-governance/templates/subfeature-template.md`
> Ce document doit être validé AVANT de démarrer le dev.

---

## Identifiant

`F-122 / SF-122-05`

## Feature parente

`F-122` — Mise en service automatique et guidée de la Vigie

## Statut

`ready`

## Date de création

2026-09-18

## Branche Git

`feat/SF-122-05-auto-accept-capture-micro`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Permettre à la capture de réunion (F-128) de démarrer **mains-libres** sur le Chrome managé de F-122, en auto-acceptant **de façon ciblée** la capture de l'onglet courant et en pré-autorisant le micro **pour la seule origine `teams.microsoft.com`** — sans que la fenêtre discrète (hors champ) ne bloque sur des invites impossibles à cliquer.

---

## Contexte / problème

Le Chrome managé (SF-122-01) est lancé **hors champ** (`--window-position=-32000,-32000`). Or la capture SF-128-02 déclenche dans la page **deux invites à cliquer** :
1. le sélecteur « partager cet onglet (avec l'audio) » de `getDisplayMedia({preferCurrentTab:true})` ;
2. l'autorisation **micro** de `getUserMedia`.

Hors champ, ces boîtes ne peuvent pas être cliquées → la capture **bloque**. Le PO a tranché l'**auto-acceptation CIBLÉE** (jamais un auto-accept média général).

---

## Comportement attendu

### Cas nominal

- **Capture d'onglet auto** : la ligne de commande du Chrome managé porte `--auto-accept-this-tab-capture` (auto-accepte `getDisplayMedia` quand `preferCurrentTab:true`, exactement le cas SF-128-02) et, en repli, `--auto-select-tab-capture-source-by-title=Microsoft Teams` (auto-sélection de l'onglet Teams dans le sélecteur d'onglet, au cas où le premier ne suffit pas). Le partage d'onglet démarre alors **sans dialogue**.
- **Micro pré-autorisé, origine unique** : au **provisionnement du profil dédié** (avant lancement), on amorce le fichier `Preferences` du profil managé (`<user-data-dir>/Default/Preferences`) pour poser le content setting `media_stream_mic = allow` (valeur Chrome `setting: 1`) pour `https://teams.microsoft.com` **et** le motif sous-domaines `https://[*.]teams.microsoft.com`. `getUserMedia` sur Teams est alors accordé **sans invite**.
- L'amorçage est **idempotent** : relancer n'ajoute pas de doublon et ne modifie pas une valeur déjà posée ; il **ne touche à aucune autre clé** du `Preferences` (autres origines, autres réglages, session existante préservée).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| `Preferences` inexistant (premier lancement) | Le créer avec la seule arborescence `profile.content_settings.exceptions.media_stream_mic` + les deux origines Teams ; ne rien inventer d'autre. |
| `Preferences` présent mais JSON illisible/corrompu | Ne pas écraser silencieusement des données valides : laisser le fichier tel quel, signaler via `say`, poursuivre le lancement (la capture micro pourra rester à invite, mais la Vigie/F-122 ne casse pas). |
| Écriture du profil impossible (droits, disque) | Ne **jamais** faire échouer le cycle de vie F-122 : capter l'`IOException`, signaler via `say`, poursuivre `launchAndWait`. |
| Micro tout de même refusé côté OS | Comportement SF-128-02 inchangé (la capture garde au moins l'audio de l'onglet) — hors scope ici. |

---

## Critères d'acceptation

- [ ] La ligne de commande du Chrome managé contient `--auto-accept-this-tab-capture`.
- [ ] La ligne de commande contient `--auto-select-tab-capture-source-by-title=Microsoft Teams` (repli).
- [ ] Le dernier argument reste l'URL Teams et **aucun** argument `--headless` n'est présent (non-régression SF-122-01).
- [ ] **Aucun** flag d'auto-accept média large (`--use-fake-ui-for-media-stream`, `--auto-accept-camera-and-microphone-capture`) n'est présent.
- [ ] Après amorçage, `<profileDir>/Default/Preferences` contient `profile.content_settings.exceptions.media_stream_mic` avec une entrée `setting: 1` (allow) pour `https://teams.microsoft.com` et pour `https://[*.]teams.microsoft.com`.
- [ ] L'amorçage est idempotent : deux amorçages consécutifs produisent le même contenu, sans doublon.
- [ ] L'amorçage **préserve** les autres clés d'un `Preferences` pré-existant (une clé témoin d'une autre origine / d'un autre réglage survit).
- [ ] Un `Preferences` corrompu n'est pas écrasé ; le lancement se poursuit.
- [ ] Le cycle de vie F-122 (`ensureRunning`/`isReachable`/`relaunchIfDead`/`stop`), l'attache CDP et `TeamsAdapterV1` sont inchangés (non-régression verte).

---

## Périmètre

### Hors scope (explicite)

- La capture elle-même (scripts `getDisplayMedia`/`getUserMedia`/mix/`MediaRecorder`) = F-128 / SF-128-02, inchangée.
- Toute auto-acceptation média **hors** onglet courant / **hors** origine Teams.
- La caméra (jamais pré-autorisée).
- Backend, endpoints, base de données, frontend : **aucun** (100 % runner-side).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|-----------------|-------|
| `media_stream_mic` (teams.microsoft.com) | `setting: 1` (ALLOW) | posé au provisionnement du profil managé |
| `media_stream_mic` (`[*.]teams.microsoft.com`) | `setting: 1` (ALLOW) | idem, sous-domaines |
| capture d'onglet | auto-acceptée | via flags de lancement, uniquement onglet courant |

---

## Contraintes de validation

| Élément | Règle |
|---------|-------|
| Origine micro | **exactement** `https://teams.microsoft.com` et `https://[*.]teams.microsoft.com`, motif Chrome `origine:443,*` ; aucune autre origine. |
| Portée | **un seul** profil (le `--user-data-dir` dédié géré par F-122), jamais le profil/navigateur perso. |
| Fichier | `<user-data-dir>/Default/Preferences`, JSON, écriture atomique (fichier temporaire + rename). |
| Non-clobber | fusion dans l'objet existant, jamais de remplacement global du fichier. |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Non applicable

### Composants impactés (runner)

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `ManagedChrome` | modif | ajout des 2 flags dans `commandLine()` ; appel de l'amorçage du profil dans `launchAndWait()` (après `createDirectories`, avant `session.start`), gardé par try/catch pour ne jamais casser le lancement. |
| `ChromeProfileSeed` (nouveau) | ajout | fonction **pure** de fusion JSON (`withTeamsMicAllowed`) + I/O idempotente `seed(Path profileDir)` (lecture/fusion/écriture atomique de `Default/Preferences`). |

### Préoccupations transversales

- **Auth / Principal** : non concernée (runner-side, aucune session backend).
- **Contexte tenant** : non concernée (aucun accès données).
- **Plans / limites** : non concernée.
- **Navigation / routing** : non concernée (aucune route).
- **Sécurité (bornes)** : composants impactés listés = `ManagedChrome`, `ChromeProfileSeed` (nouveau). Portée strictement bornée au Chrome managé dédié : capture auto **onglet courant uniquement**, micro **origine Teams uniquement**. Aucune capacité média large.

---

## Plan de test

### Tests unitaires

- [ ] `ManagedChromeTest` — `commandLine()` contient `--auto-accept-this-tab-capture` et `--auto-select-tab-capture-source-by-title=Microsoft Teams`.
- [ ] `ManagedChromeTest` — `commandLine()` ne contient **aucun** flag média large et pas de `--headless` ; dernier arg = URL Teams (non-régression).
- [ ] `ChromeProfileSeedTest` — fusion pure : `media_stream_mic` allow posé pour les 2 origines Teams à partir d'un objet vide.
- [ ] `ChromeProfileSeedTest` — idempotence : deux fusions successives → contenu identique, pas de doublon.
- [ ] `ChromeProfileSeedTest` — non-clobber : une clé témoin (autre origine mic + autre section) est préservée.
- [ ] `ChromeProfileSeedTest` — `seed(@TempDir)` crée `Default/Preferences` avec l'allow Teams ; second `seed` reste idempotent.
- [ ] `ChromeProfileSeedTest` — `Preferences` corrompu : le fichier n'est pas écrasé, `seed` ne lève pas.

### Tests d'intégration

- Sans objet (aucun endpoint). Le comportement navigateur réel (invite réellement supprimée) porte le **drapeau « À VALIDER SUR CALL RÉEL »** hérité de F-128, non vérifiable en CI.

### Isolation workspace / user_id

- [ ] Non applicable — raison : runner-side, aucun accès aux données multi-tenant.

---

## Dépendances

### Subfeatures bloquantes

- `SF-122-01` — done (Chrome managé + `commandLine()` + profil dédié).
- `SF-128-02` — done (capture onglet+micro qui déclenche les invites).

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non impacté).

---

## Notes et décisions

- **Flags exacts vérifiés** : `--auto-accept-this-tab-capture` (Chromium `content_switches`, auto-accepte `getDisplayMedia` avec `preferCurrentTab`) et `--auto-select-tab-capture-source-by-title=<titre>` (auto-sélection d'onglet par titre). Rejet explicite de `--use-fake-ui-for-media-stream` (trop large, décision PO).
- **Portée sécurité** : la pré-autorisation micro vit dans les content settings du **seul** profil managé, pour la **seule** origine Teams. La capture auto est bornée à l'onglet courant. Rien ne s'applique au navigateur personnel.
- **Écriture atomique + non-clobber** : on fusionne dans le `Preferences` existant (temp file + move `ATOMIC_MOVE` avec repli), jamais de réécriture globale, pour préserver la session Teams et les réglages déjà présents.
- **Gateway-First / V1** : orchestration/provisionnement de process, aucune capacité IA réimplémentée.

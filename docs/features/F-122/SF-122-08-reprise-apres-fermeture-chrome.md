# Mini-spec — [F-122 / SF-122-08] Reprise robuste après fermeture manuelle de Chrome

## Identifiant

`F-122 / SF-122-08`

## Feature parente

`F-122` — Mise en service automatique et guidée de la Vigie

## Statut

`in-progress`

## Date de création

2026-09-19

## Branche Git

`feat/SF-122-08-reprise-apres-fermeture-chrome`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Rendre la récupération du Chrome managé (`teams_meeting_join` → `recoverManagedChrome`, SF-128-09) **robuste à une fermeture manuelle/sale de Chrome** : relancer, **nettoyer un verrou de profil resté** (SingletonLock), **attendre que le port de débogage réponde** (backoff borné) et ne conclure `browser_unreachable` **qu'après un échec réel**.

---

## Comportement attendu

### Cas nominal

1. Après une fermeture manuelle de Chrome (fenêtre fermée, process sorti), un `teams_meeting_join` (F-128) échoue d'abord à l'attache CDP (`browser_unreachable`).
2. `recoverManagedChrome` appelle `ManagedChrome.recover()` qui :
   - si le port répond déjà → `REACHABLE` (rien à faire) ;
   - sinon **oublie un handle mort**, **nettoie un verrou de profil resté** (`SingletonLock`/`SingletonSocket`/`SingletonCookie` d'une fermeture sale, qui feraient qu'un nouveau Chrome se contente de « passer la main » et sort sans ouvrir le port), **relance** hors champ, et **attend le port** (backoff borné, `PORT_WAIT_MS`/`POLL_STEP_MS` existants).
3. Le port répond → `LAUNCHED`, la navigation vers la réunion est **retentée**, le join réussit.
4. Diagnostic F-132 : `chrome/recover` (relancé, verrou nettoyé, port répond / échec).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucun exécutable de navigateur résolu | `recover()` rend `NO_BROWSER` ; `recoverManagedChrome` rend `false` → `browser_unreachable` nommé (comme avant) |
| Port ne répond jamais après relance (backoff épuisé) | `recover()` rend `UNREACHABLE` **après** avoir attendu tout le backoff → `browser_unreachable` (échec réel) |
| Chrome vivant | `recover()` rend `REACHABLE`, aucun relancement, aucun nettoyage de verrou (on ne touche jamais au verrou d'un Chrome qui tourne) |
| Aucun Chrome managé branché (repli long-polling) | comportement inchangé (`browser_unreachable` comme avant SF-128-09) |
| Nettoyage du verrou impossible (droits, fichier absent) | best-effort : avalé, la relance est tentée quand même |

---

## Critères d'acceptation

- [ ] `recover()` ne nettoie le verrou de profil **que** lorsque le port ne répond pas (jamais sur un Chrome vivant).
- [ ] Quand Chrome a été fermé, `recover()` **oublie le handle mort**, **nettoie le verrou**, **relance** et **attend** le port avant de conclure.
- [ ] `browser_unreachable` n'est rendu par le join **qu'après** un échec réel de récupération (backoff épuisé, ou `NO_BROWSER`).
- [ ] Un Chrome qui remonte **après quelques sondes** (port lent) donne un join **réussi** (pas de conclusion prématurée).
- [ ] Diagnostic F-132 `chrome/recover` clair (relancé / verrou nettoyé / port répond / échec).
- [ ] Non-régression : SF-128-09 (2ᵉ « Rejoindre & capturer »), le repli sans Chrome managé, la 1ʳᵉ capture, VigieLoop, SF-122-05/06/07, heartbeat, F-132.

---

## Périmètre

### Hors scope (explicite)

- La capture d'onglet elle-même et sa robustesse à la navigation = SF-128-12 (livrée).
- Le maintien de l'onglet Teams = SF-122-07 (livrée).
- Aucun changement backend / frontend / base de données.

---

## Technique

### Fichiers impactés (runner)

| Fichier | Opération | Notes |
|---------|-----------|-------|
| `ChromeProfileLock.java` | AJOUT | nettoyage best-effort des fichiers singleton du profil (`SingletonLock`/`SingletonSocket`/`SingletonCookie`) |
| `ManagedChrome.java` | MODIF | + `recover()` : port ? → REACHABLE ; sinon oublie handle mort + nettoie verrou + relance + attend port ; diag `chrome/recover` |
| `TeamsTools.java` | MODIF | `recoverManagedChrome()` appelle `chrome.recover()` (au lieu de `ensureRunning()`) |

### Migration Liquibase

- [x] Non applicable (fichiers runner uniquement)

### Composants Angular

- Aucun.

---

## Plan de test

### Tests unitaires (runner, sans navigateur)

- [ ] `ManagedChromeTest` — `recover()` quand le port répond ⇒ `REACHABLE`, aucun lancement, aucun nettoyage de verrou.
- [ ] `ManagedChromeTest` — `recover()` port muet + exe présent ⇒ relance + **verrou nettoyé** + `LAUNCHED` quand le port remonte.
- [ ] `ManagedChromeTest` — `recover()` port qui remonte après plusieurs sondes ⇒ attend (backoff) puis `LAUNCHED` (pas de conclusion prématurée).
- [ ] `ManagedChromeTest` — `recover()` port jamais joignable ⇒ `UNREACHABLE` après tout le backoff (sonde répétée le nombre attendu de fois).
- [ ] `ManagedChromeTest` — `recover()` sans exécutable ⇒ `NO_BROWSER`.
- [ ] `ManagedChromeTest` — `recover()` oublie un handle mort avant de relancer.
- [ ] `ChromeProfileLockTest` — supprime `SingletonLock`/`SingletonSocket`/`SingletonCookie` présents ; best-effort si absents ; ne lève jamais.

### Tests d'intégration (CDP / lifecycle simulés)

- [ ] `TeamsMeetingRecaptureToolTest` — après « Chrome fermé », un join relance + attend le port (qui remonte après quelques sondes) + réussit ; `browser_unreachable` seulement après un échec réel.

### Isolation utilisateur

- [x] Non applicable — code runner local, aucune donnée multi-tenant ; le nettoyage ne touche que le **profil managé** du runner.

---

## Préoccupations transversales

- **Navigation / routing** : non applicable au routing produit ; « navigation » ici = re-navigation CDP de la réunion, inchangée (join la retente déjà). Composants runner impactés listés ci-dessus.
- **Auth / Principal**, **Contexte tenant**, **Plans / limites** : non touchés.

---

## Dépendances

### Subfeatures bloquantes

- `SF-122-01` (cycle de vie du Chrome managé) — done
- `SF-128-09` (le join re-garantit le Chrome managé) — done

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Cause racine (prod CAGIP 2026-09-19)** : après fermeture manuelle de Chrome, un `join` échoue en `browser_unreachable`. `recoverManagedChrome` relançait via `ensureRunning()` mais concluait trop vite dans le cas d'une **fermeture sale** : le profil `--user-data-dir` garde un **verrou singleton** resté (`SingletonLock`), si bien qu'un nouveau Chrome sur le même profil **passe la main** à une instance fantôme et **sort sans ouvrir le port** → le port ne remonte jamais, `browser_unreachable`.
- **Choix** : centraliser une **récupération robuste** dans `ManagedChrome.recover()` (maison naturelle du cycle de vie, F-122) : oublier un handle mort, nettoyer le verrou **seulement** quand le port ne répond pas (jamais sur un Chrome vivant), relancer, attendre le port (backoff borné existant). `recover()` est strictement plus robuste qu'`ensureRunning()` (qui ne nettoie ni le handle mort ni le verrou).
- Le nettoyage du verrou est **best-effort strict** : un fichier absent ou non supprimable n'empêche jamais la relance.

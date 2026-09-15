# Mini-spec — [F-122 / SF-122-04] Cas d'entreprise nommés

> Base : `project-governance/templates/subfeature-template.md`.
> Cadrage : `docs/features/F-122/CADRAGE-F-122-mise-en-service-vigie-automatique.md` (SF-122-04).

---

## Identifiant

`F-122 / SF-122-04`

## Feature parente

`F-122` — Mise en service automatique et guidée de la Vigie

## Statut

`ready`

## Date de création

2026-09-15

## Branche Git

`feat/SF-122-04-cas-entreprise-nommes`

---

## Objectif

> Donner à chaque échec de mise en service un **diagnostic nommé** avec la **marche à suivre** —
> jamais un silence : remote-debugging bloqué par policy, Chrome absent, proxy NTLM.

---

## Comportement attendu

### Cas nominal

À partir des états bruts de SF-122-01 (`ManagedChrome.State`) et du système courant, produire un
**diagnostic nommé** actionnable :

1. **Chrome absent / chemin introuvable** (`NO_BROWSER`) → `CHROME_NOT_FOUND` : nomme le manque et dit
   comment le lever (installer Chrome, ou déclarer `CLAUDE_TEAMS_CHROME_PATH`).
2. **Remote-debugging bloqué par une policy** (`UNREACHABLE` : Chrome démarre mais le port ne s'ouvre
   pas) → `REMOTE_DEBUG_BLOCKED` : nomme la cause la plus probable (GPO/plist d'entreprise) et donne le
   repli (essayer Edge via `CLAUDE_TEAMS_CHROME_PATH`, vérifier la policy, contacter la DSI).
3. **Proxy NTLM** → `PROXY_NTLM` : nomme le fait que la JVM ne porte pas l'authentification NTLM du
   proxy, renvoie au relais local `px` (F-59) et aux instructions proxy du système (F-45/F-38-25).
4. Quand tout va bien (`REACHABLE`/`LAUNCHED`) → `NONE` (aucun défaut).

Chaque diagnostic porte un **titre** et un **message** non vides, avec la marche à suivre.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Port qui ne répond jamais (policy probable) | `REMOTE_DEBUG_BLOCKED` **nommé**, jamais un échec muet |
| Aucun exécutable trouvé | `CHROME_NOT_FOUND` nommé, avec la surcharge de chemin |
| Proxy d'entreprise NTLM qui avale la liaison | `PROXY_NTLM` nommé, avec `px` (F-59) et les instructions proxy du système |
| État sain | `NONE` : aucun message d'alarme |

---

## Critères d'acceptation

- [ ] `NO_BROWSER` → diagnostic `CHROME_NOT_FOUND`, message nommant Chrome et
      `CLAUDE_TEAMS_CHROME_PATH`.
- [ ] `UNREACHABLE` → diagnostic `REMOTE_DEBUG_BLOCKED`, message nommant la policy d'entreprise et le
      repli.
- [ ] `REACHABLE` et `LAUNCHED` → `NONE`, `isFault()` faux.
- [ ] `proxyNtlm(system)` → diagnostic `PROXY_NTLM`, message nommant NTLM et renvoyant à `px` (F-59)
      et aux instructions proxy du système courant.
- [ ] Chaque diagnostic de défaut a un titre et un message **non vides** portant une marche à suivre.
- [ ] Les messages sont **par système** là où c'est utile (chemins/commandes adaptés à l'OS).

---

## Périmètre

### Hors scope (explicite)

- La **détection réseau** du proxy (déjà F-38/F-45/F-59) : SF-04 **réutilise** `OperatingSystem`
  (instructions proxy) et renvoie à `px`, il ne réimplémente pas la détection.
- Le lancement/état du Chrome (SF-122-01) et la boucle (SF-122-03) : SF-04 **habille** leurs états en
  messages, il ne change pas leur logique.
- L'affichage frontend de ces messages : ils sont portés par le rapport/diagnostic runner ; leur
  rendu éventuel dans l'assistant SF-122-02 est déjà couvert par le champ `detail` de l'instantané.

---

## Technique

### Composants (runner)

- `VigieServiceDiagnostic` (nouveau) — `Fault` (`NONE`/`CHROME_NOT_FOUND`/`REMOTE_DEBUG_BLOCKED`/
  `PROXY_NTLM`), `Diagnosis(fault, title, message)`, et les fabriques `fromChromeState(State, OS)` et
  `proxyNtlm(OS)`.
- Réutilise sans les modifier : `ManagedChrome.State`, `OperatingSystem` (instructions proxy),
  `ChromePaths.PATH_ENV`.

### Tables impactées / endpoints

Aucune. Aucune migration.

## Plan de test

### Tests unitaires (runner, `./mvnw -q test`)

- [ ] `VigieServiceDiagnosticTest` — chaque état → le bon `Fault` avec un message nommé non vide ;
      `REACHABLE`/`LAUNCHED` → `NONE` ; `proxyNtlm` nomme NTLM + `px` ; messages adaptés à l'OS.

### Tests d'intégration

- Sans objet (composant runner, aucun endpoint ; la détection réseau et le lancement réel sont
  validés ailleurs / sur machine).

### Isolation utilisateur / tenant

- [x] Non applicable — diagnostic pur (aucune donnée utilisateur, aucun accès BD).

---

## Préoccupations transversales

| Préoccupation | Impact | Composants |
|---------------|--------|------------|
| **Auth / Principal** | Aucun. | — |
| **Contexte tenant** | Aucun. | — |
| **Plans / limites** | Aucun. | — |
| **Navigation / routing** | Aucun (pas de frontend). | — |

---

## Dépendances

### Subfeatures bloquantes

- `SF-122-01` (Done) — `ManagedChrome.State`.

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`. Le repli **Edge Chromium** évoqué au cadrage §5 est **nommé**
  dans le message (via `CLAUDE_TEAMS_CHROME_PATH`), pas imposé.

---

## Notes et décisions

- **Jamais un silence** : la règle F-80/F-87 (le message porte le remède ET le moyen) est appliquée —
  chaque défaut est nommé avec sa marche à suivre.
- SF-04 s'appuie sur les **états** de SF-01 plutôt que de re-sonder : une seule source de vérité pour
  « le Chrome managé répond-il ? ».

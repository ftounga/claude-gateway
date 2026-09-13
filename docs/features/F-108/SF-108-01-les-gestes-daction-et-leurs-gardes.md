# Mini-spec — F-108 / SF-108-01 — Les gestes d'action et leurs gardes

> Base : `docs/features/F-108/CADRAGE-F-108-agir-dans-microsoft-365.md` §4 (gardes NON négociables)
> et §6 (découpage). Le cadrage est validé par le PO : cette mini-spec l'applique, ne le rediscute pas.

## Identifiant

`F-108 / SF-108-01`

## Feature parente

`F-108` — Agir dans Microsoft 365 : fichiers, enregistrements, gestes

## Statut

`done` — PR #490

## Date de création

2026-09-13

## Branche Git

`feat/SF-108-01-gestes-et-gardes`

---

## Objectif

Ouvrir au runner les gestes d'action dans l'onglet Teams (naviguer, cliquer, taper, déposer un
fichier, télécharger) **uniquement sur les domaines Microsoft**, chaque geste gardé par une
vérification de domaine **avant émission**, avec refus des pages d'identification et des champs mot
de passe, et journalisation de chaque geste — sans jamais rouvrir le refus des cookies et du
stockage.

---

## Comportement attendu

### Cas nominal

1. La liste blanche CDP (`CdpCommands`) passe des cinq commandes de lecture à la liste **strictement
   nécessaire** pour agir : ajout de `Page.navigate`, `Input.dispatchMouseEvent`,
   `Input.dispatchKeyEvent`, `Input.insertText`, `DOM.getDocument`, `DOM.querySelector`,
   `DOM.setFileInputFiles`, `Browser.setDownloadBehavior`, `Target.setAutoAttach`, et les événements
   `Target.attachedToTarget` / `Target.detachedFromTarget`.
2. Un nouveau garde de domaine (`MicrosoftDomains`) porte la **liste close** du §4.1 et sait dire si
   une URL est un domaine Microsoft autorisé, une page d'identification, ou hors liste.
3. Un nouvel exécuteur de gestes (`PageActions`) réalise chaque geste d'action. **Avant chaque
   émission**, il relit l'adresse courante de l'onglet (`Runtime.evaluate` sur `location.href`,
   commande déjà autorisée) et **refuse** si l'onglet a quitté la liste des domaines Microsoft
   (garde §4.1) ou s'il est sur une page d'identification (garde §4.2).
4. `Input.insertText` / la saisie sont refusés dans un **champ de type mot de passe** (garde §4.3) :
   le champ ciblé est inspecté (`type="password"`) avant toute saisie, et sa valeur n'est jamais lue.
5. Après un geste qui déplace la vue de l'utilisateur, la vue est **restaurée** (garde §4.7), et ce
   qui a été fait est renvoyé dans le résultat.
6. L'observation est **élargie aux cadres et workers** de la page via `Target.setAutoAttach`,
   **filtrée sur les mêmes domaines** : un cadre ou un worker hors liste n'est pas attaché (garde
   §4.8).
7. Chaque geste d'action est **journalisé** (outil, domaine, action, cible nommée, résultat) par le
   canal d'audit runner existant — jamais le contenu d'un champ saisi (garde §4.6).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Navigation vers un domaine hors liste (`example.com`) | Refus **avant émission** : `BrowserLinkException(DOMAIN_REFUSED)`, rien n'est envoyé |
| Geste sur une page qui a quitté la liste (redirection vers `login.microsoftonline.com`) | Refus : `BrowserLinkException(SIGN_IN_REFUSED)` |
| Saisie dans un champ `type="password"` | Refus : `BrowserLinkException(PASSWORD_FIELD_REFUSED)`, aucune saisie, aucune lecture |
| Commande cookies/stockage (`Network.getCookies`, `Storage.*`) | Refus nommé conservé (`COMMAND_REFUSED`) |
| Cadre/worker hors domaine proposé par l'auto-attach | Non attaché, sans bruit |
| Liaison perdue | `BrowserLinkException(LINK_LOST)`, jamais un geste silencieux |

---

## Critères d'acceptation

- [ ] `CdpCommands.allowed()` contient les commandes d'action listées ; les refus nommés cookies et
      stockage restent et disent ce qui a été tenté ; le test qui garde la liste est **mis à jour**,
      pas supprimé.
- [ ] `MicrosoftDomains` reconnaît la liste close du §4.1 (dont les jokers `*.sharepoint.com`,
      `*.office.com`, `*.officeapps.live.com`, `*.cloud.microsoft`) et **refuse** tout autre hôte ;
      il distingue les trois hôtes d'identification du §4.2.
- [ ] Un geste `PageActions` vers un domaine hors liste est refusé **avant** toute émission CDP.
- [ ] Un geste sur une page d'identification est refusé (aucun clic, aucune saisie).
- [ ] Une saisie dans un champ mot de passe est refusée et la valeur du champ n'est jamais lue.
- [ ] L'auto-attach n'attache que les cadres/workers dont l'URL est un domaine Microsoft autorisé.
- [ ] Chaque geste d'action produit une entrée de journal (outil, domaine, action, cible, résultat)
      sans le contenu saisi.
- [ ] La vue de l'utilisateur est restaurée après un geste qui la déplace.
- [ ] Tous les tests de sécurité existants (aucun cookie ne remonte, rien ne sort de la machine)
      restent verts.

---

## Périmètre

### Hors scope (explicite)

- Les **capacités** fichiers (lister/lire/écrire) — SF-108-03/04.
- Le téléchargement d'un **enregistrement** et la chaîne F-90 — SF-108-05.
- La **confirmation** des écritures par le terminal — SF-108-02 (cette SF pose les gestes gardés ;
  la SF-02 décide quelles opérations exigent un accord).
- Poster un message, répondre, réagir dans Teams (hors périmètre F-108).
- Taper dans Word/Excel/PowerPoint en ligne (hors périmètre F-108).

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| URL de navigation | Oui | hôte ∈ liste close §4.1 (jokers compris), schéma `https` | minuscule, sans requête pour la comparaison |
| sélecteur DOM d'un geste | Oui | chaîne non vide, ≤ 1024 car. | trim |
| champ de saisie | — | jamais `type="password"` | — |

---

## Technique

### Composants runner impactés

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `CdpCommands` | modifié | liste blanche étendue aux commandes d'action, refus cookies/stockage conservés |
| `MicrosoftDomains` (nouveau) | créé | liste close des domaines + hôtes d'identification |
| `PageActions` (nouveau) | créé | gestes d'action gardés par domaine, refus identification/mot de passe, restauration, journal |
| `BrowserTargets` | modifié | domaines Microsoft partagés depuis `MicrosoftDomains` |
| `NetworkObserver` | modifié | auto-attach filtré sur les mêmes domaines |
| `BrowserLinkException` | modifié | codes `DOMAIN_REFUSED`, `SIGN_IN_REFUSED`, `PASSWORD_FIELD_REFUSED` |
| `FakeCdpConnection` (test) | modifié | supporte navigate/click/insertText/setFileInputFiles/setDownloadBehavior/auto-attach |

### Migration Liquibase

- [ ] Non applicable — le journal des gestes **réutilise l'audit runner existant** (cadrage : « journal des gestes : réutilise d'abord l'audit runner existant »). Aucune table nouvelle.

### Isolation

- Non applicable côté données gateway : ces gestes vivent sur la **machine** de l'utilisateur, dans
  l'onglet de sa propre session. L'identité Microsoft reste celle du navigateur (cadrage §7). La
  journalisation gateway (SF-108-02) portera l'isolation `user_id` existante de l'audit runner.

---

## Plan de test

### Tests unitaires (runner)

- [ ] `CdpCommandsTest` — la liste blanche contient les commandes d'action ; cookies et stockage
      toujours refusés (test **mis à jour**, pas supprimé).
- [ ] `MicrosoftDomainsTest` — reconnaît chaque domaine du §4.1 (jokers), refuse tout autre hôte,
      distingue les hôtes d'identification.
- [ ] `PageActionsTest` — **refus hors domaine** (navigation vers `example.com` refusée avant émission).
- [ ] `PageActionsTest` — **refus page d'identification** (geste sur `login.microsoftonline.com` refusé).
- [ ] `PageActionsTest` — **refus champ mot de passe** (saisie dans `type="password"` refusée, valeur non lue).
- [ ] `PageActionsTest` — un geste nominal restaure la vue et rend un résultat nommé.
- [ ] `PageActionsTest` — chaque geste produit une entrée de journal sans le contenu saisi.
- [ ] `NetworkObserverTest` — auto-attach filtré : un cadre hors domaine n'est pas attaché.
- [ ] Non-régression : `AucunCookieNeRemonteTest`, `RienNeSortDeLaMachineTest`,
      `TeamsReadingToolsTest.the_debug_whitelist_is_untouched`,
      `TeamsGisementsTest.the_debug_whitelist_is_still_untouched` — mis à jour et verts.

### Isolation utilisateur

- Non applicable (gestes locaux à la session navigateur de l'utilisateur) — tracé ci-dessus.

---

## Préoccupation transversale — Sécurité (§7 du cadrage)

Composants impactés listés ci-dessus : `CdpCommands`, `MicrosoftDomains`, `PageActions`,
`BrowserTargets`, `NetworkObserver`, `BrowserLinkException`. Le coupe-circuit et le journal d'audit
runner sont vérifiés en SF-108-02 (côté gateway).

---

## Notes et décisions

- **Décision (réversible)** : l'adresse courante est relue par `Runtime.evaluate` (`location.href`),
  déjà dans la liste blanche, plutôt que par `Page.getNavigationHistory` (commande non ajoutée) —
  moins de surface, même garantie. Tracé dans les arbitrages.
- **Décision (réversible)** : les nouveaux codes de refus (`DOMAIN_REFUSED`, `SIGN_IN_REFUSED`,
  `PASSWORD_FIELD_REFUSED`) réutilisent `BrowserLinkException` plutôt qu'une hiérarchie nouvelle.

# Mini-spec — [F-93 / SF-93-04] Runner hors ligne : la promotion est reportée, pas exigée

---

## Identifiant

`F-93 / SF-93-04`

## Feature parente

`F-93` — La promotion a une destination (mini-specs SF-93-01→03)

## Statut

`done` — livrée le 2026-09-13 (PR #554)

## Date de création

2026-09-13

## Branche Git

`feat/SF-93-04-runner-hors-ligne-promotion-reportee`

---

## Objectif

Quand la machine est injoignable pendant le tour, les contrôles de fin de tour qui exigent une
écriture sur la machine **ne refusent plus la clôture** : le tour se clôt avec une mention unique
« promotion reportée : poste hors ligne », et la promotion due est **réclamée** au premier tour où
le poste répond de nouveau.

---

## Contexte

Constat de production (terminal Teams CAGIP, runner macOS) : le runner est déconnecté (« Aucun
runner n'est connecté pour ce projet »). Le modèle pose un marqueur de fin de tour déclarant une
promotion ; `juge-fin-de-tour` puis `promotion-dette-bloquante` refusent la clôture et redemandent
**trois fois** d'écrire dans la carte ; le modèle répond trois fois qu'il ne peut pas écrire, jusqu'à
ce que F-50 rende la main (`MAX_END_OF_TURN_BLOCKS = 3`). Trois appels au fournisseur payés pour
rien, et un fil incompréhensible pour l'utilisateur.

La décision de F-52/F-93 (une promotion due bloque la clôture) est **conservée** quand le poste
répond. Ce qui manque : distinguer « le modèle n'a pas rangé » de « le modèle **ne pouvait pas**
ranger ».

---

## Comportement attendu

### Cas nominal

1. **L'état de la machine pendant le tour** (`AtelierChatService`) : chaque appel runner du tour met
   à jour un état propre au tour (clé `userId:workspaceId`, remis à zéro à l'ouverture) :
   - refus de transport (`runner_unavailable`, `runner_not_on_this_node`) → `OFFLINE` ;
   - réponse du runner (succès, ou erreur émise par le runner lui-même) → `REACHED` ;
   - aucun appel runner, délai dépassé, argument refusé avant émission → état inchangé
     (`UNKNOWN` par défaut). **Le dernier appel fait foi** : un runner revenu en cours de tour
     repasse `REACHED`.
2. **Le contexte de fin de tour** porte cet état (`AtelierCheckpointContext.machine()`,
   énumération `AtelierMachineReach` : `UNKNOWN`, `REACHED`, `OFFLINE`). Les fabriques existantes
   rendent `UNKNOWN` (comportement inchangé pour tout appelant existant).
3. **Un verdict « reporté »** (`AtelierCheckpointVerdict.deferred(notice)`) : ne bloque pas, porte
   une mention. `AtelierCheckpointRunner.run` et `GovernanceCheckpointDelegate.evaluate` : **le
   premier blocage l'emporte** (règle F-50 inchangée) ; à défaut, le premier verdict porteur d'une
   mention est rendu ; à défaut, « passe ».
4. **Poste hors ligne** (`machine = OFFLINE`) :
   - `juge-fin-de-tour` : marqueur **absent** → refus inchangé (poser le marqueur n'exige aucune
     écriture sur la machine) ; promotion déclarée → la promotion est **enregistrée comme due**
     (`PromotionReportee`) et le verdict est `deferred("promotion reportée : poste hors ligne")` ;
   - `promotion-dette-bloquante` : promotion sans destination, destination étrangère, ou dette > 0 →
     enregistrée comme due, verdict `deferred(…)` ;
   - `juge-independant` et `integrite-du-poste` (qui **lisent** la machine) → « passe » sans appel.
5. **Clôture** : la boucle ajoute **une seule fois** à la réponse finale le paragraphe
   « *promotion reportée : poste hors ligne* — la carte du poste n'a pas pu être écrite pendant ce
   tour ; la promotion reste due et sera réclamée au premier tour où le poste répondra. » Aucun
   blocage n'est compté, aucun appel supplémentaire au fournisseur.
6. **Poste revenu** (`machine = REACHED`) et promotion due pour ce `(userId, workspaceId)` : le
   premier des deux contrôles actifs **réclame** la dette — refus de clôture nommant les éléments
   reportés, la dette déclarée, la date du report et les fichiers de la carte — puis la retire du
   registre (réclamée **une fois** ; la suite est jugée par les contrôles ordinaires sur le marqueur).
7. **Poste inconnu** (`machine = UNKNOWN`, ex. tour sans appel runner) : aucune réclamation (on ne
   réclame pas une écriture sans savoir que le poste répond), contrôles ordinaires inchangés.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Poste hors ligne, marqueur absent | Refus « pose le marqueur » inchangé (ne demande aucune écriture) | — |
| Poste hors ligne, rien à promouvoir et dette 0 | « Passe », aucune mention | — |
| Poste hors ligne à plusieurs tours de suite | Les reports se **cumulent** (éléments sans doublon, dette maximale retenue) ; une seule mention par tour | — |
| Poste revenu mais aucune promotion due | Contrôles ordinaires, aucune réclamation | — |
| Report plus vieux que la durée de vie (7 jours) ou registre plein (500 entrées) | Entrée oubliée (la moins récente sort) — la dette physique, elle, reste dans `STATE.md` et sera recomptée par le marqueur | — |
| Un contrôle lève pendant l'évaluation | Repli passant F-50 (D2) inchangé | — |
| Autre utilisateur / autre projet | Jamais réclamé ailleurs : clé `(userId, workspaceId)` | — |

---

## Critères d'acceptation

- [x] CA1 — Runner hors ligne pendant le tour + marqueur déclarant une promotion : **zéro** refus de fin de tour, la réponse se termine par la mention unique « promotion reportée : poste hors ligne », le fournisseur n'est rappelé aucune fois après la réponse finale.
- [x] CA2 — Runner hors ligne + dette > 0 (ou promotion sans destination) : même issue via `promotion-dette-bloquante`.
- [x] CA3 — Runner hors ligne + marqueur absent : refus « pose le marqueur » conservé.
- [x] CA4 — Tour suivant où le runner répond : la clôture est refusée **une fois** avec la réclamation nommant les éléments reportés ; le tour d'après n'est plus réclamé.
- [x] CA5 — Tour suivant sans appel runner (`UNKNOWN`) : aucune réclamation.
- [x] CA6 — Isolation : un report de (A, projet 1) n'est réclamé ni pour (B, projet 1) ni pour (A, projet 2).
- [x] CA7 — Runner revenu en cours de tour (dernier appel abouti) : contrôles ordinaires (pas de report).
- [x] CA8 — Premier blocage l'emporte sur un report dans `AtelierCheckpointRunner` et `GovernanceCheckpointDelegate`.

---

## Périmètre

### Hors scope (explicite)

- Persistance du report en base (aucune migration) : registre **en mémoire du processus**, même
  choix assumé que `IntegriteMemo` / `JugeMemo` — un redémarrage ou un autre pod l'oublie ; la dette
  physique reste recomptée par le marqueur.
- Sonde active de connectivité du runner en fin de tour (on se fie aux appels réellement faits).
- Modification de `MAX_END_OF_TURN_BLOCKS`, du marqueur ou des règles de F-52/F-93 poste joignable.
- Affichage dédié à l'écran (la mention voyage dans la réponse).

---

## Valeurs initiales

`machine = UNKNOWN` à l'ouverture de chaque tour.

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| éléments reportés | Non | 10 éléments, 300 caractères cités | texte du marqueur | par clé | trim, sans doublon |
| dette reportée | Non | — | entier ≥ 0 | par clé | max des reports |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune. **Aucune migration.**

### Composants

- `atelier/checkpoint` : `AtelierMachineReach` (nouveau), `AtelierCheckpointContext` (composant
  `machine`), `AtelierCheckpointVerdict` (composant `notice`, fabrique `deferred`),
  `AtelierCheckpointRunner` (report après blocage).
- `atelier/AtelierChatService` : état machine du tour, contexte de fin de tour, mention unique.
- `governance/GovernanceCheckpointDelegate` : report après blocage.
- `governance/control` : `PromotionReportee` (nouveau registre), `JugeFinDeTourControl`,
  `PromotionDetteBloquanteControl`, `JugeIndependantControl`, `IntegritePosteControl`.

### Préoccupations transversales

- [ ] Auth / Principal — non.
- [ ] Contexte tenant — non : la clé `(userId, workspaceId)` vient du contexte F-50 construit après `requireOwned`.
- [ ] Plans / limites — non : aucun quota ni gate ; la borne F-50 est inchangée.
- [ ] Navigation / routing — non.

---

## Plan de test

### Tests unitaires

- [ ] `PromotionReporteeTest` — report puis réclamation (une fois) ; cumul ; isolation user/projet ; durée de vie ; borne.
- [ ] `EndOfTurnControlsTest` (ou classe dédiée) — OFFLINE + promotion → `deferred` ; OFFLINE + dette → `deferred` ; OFFLINE + marqueur absent → bloque ; REACHED + dû → réclamation une fois ; UNKNOWN + dû → rien ; juge indépendant / intégrité OFFLINE → passe sans appel.
- [ ] `AtelierCheckpointRunnerTest` / `GovernanceCheckpointDelegateTest` — blocage > report > passe.

### Tests d'intégration

- [ ] `AtelierChatServiceEndOfTurnCheckpointTest` (boucle réelle, contrôles réels) — runner hors ligne : aucun refus en boucle, mention unique ; runner revenu : dette réclamée ; runner revenu en cours de tour : pas de report.
- [ ] Contexte Spring (`GovernancePackageSeederTest` / démarrage) vert : le nouveau registre est un bean.

### Isolation workspace

- [x] Applicable — le report est rangé et réclamé sous `(userId, workspaceId)` ; test dédié.

---

## Dépendances

### Subfeatures bloquantes

SF-93-01→03 (Done), F-50 (Done), F-95 (Done).

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **D1 — Signal observé, pas sondé** : l'état vient des appels réellement faits dans le tour. Le cas
  rapporté (le modèle tente d'écrire, le transport refuse) est couvert dès le premier refus.
- **D2 — Le marqueur reste exigé hors ligne** : le poser n'écrit rien sur la machine.
- **D3 — Réclamée une fois** : réclamer à chaque tour jusqu'à preuve de rangement recréerait la
  boucle, à l'échelle des tours ; après la réclamation, les contrôles ordinaires jugent le marqueur.
- **D4 — Verdict « reporté » distinct** : plutôt qu'un « passe » silencieux, pour que la mention soit
  dite une fois par la boucle et que le premier blocage garde la priorité.

# Mini-spec — F-125 / SF-125-06b — Suivi promotion/dette 100 % serveur

## Identifiant
`F-125 / SF-125-06b`

## Feature parente
`F-125` — La tenue de la carte du poste : silencieuse, robuste, jamais dans la réponse

## Statut
`in-progress`

## Date de création
2026-09-18

## Branche Git
`feat/SF-125-06b-suivi-promotion-serveur`

---

## Objectif
Retirer la dépendance des contrôles de fin de tour au **marqueur émis par le modèle** : `juge-fin-de-tour`
ne sanctionne plus une réponse « sans marqueur », `promotion-dette-bloquante` cesse de s'appuyer sur le
marqueur — le suivi promotion/dette reste garanti **côté serveur** par les écritures de fichiers
réelles (`GovernanceMapGrowth`, `JugeIndependantControl`) et la dette signalée (`IntegritePosteControl`).

---

## Comportement attendu

### Cas nominal
- `JugeFinDeTourControl.evaluate` rend **`proceed()`** : une réponse **sans** marqueur n'est plus
  sanctionnée. Le contrôle reste **déclaré dans le paquet** (identité stable) mais n'impose plus rien.
- `PromotionDetteBloquanteControl.evaluate` rend **`proceed()`** : plus aucune dépendance au marqueur
  (ni promotion « sans destination », ni dette). La dette reste **comptée et signalée** ailleurs par
  `IntegritePosteControl` (`DETTE_EN_COURS` non bloquant ; `DETTE_A_LA_CLOTURE` sur clôture) — inchangé.
- La **carte reste alimentée** : `GovernanceMapGrowth` (delta de faits à la lecture) et
  `JugeIndependantControl` (audit des fichiers écrits, best-effort) constatent le durable **écrit**,
  sans aucune déclaration du modèle. Un fait écrit dans une fiche est détecté « promu » côté serveur ;
  rien écrit → aucun signal « rien à ranger » possible.
- Le marqueur `fin-de-tour` étant retiré de bout en bout, l'**infra marqueur morte** est supprimée
  (`FinDeTourMarker`, famille `PromotionReportee`, `appendNotice`). `stripTurnMetadata` est **conservé**
  (hygiène : ôte tout marqueur hérité d'un tour d'un poste activé avant re-seed).

### Cas d'erreur
| Situation | Comportement attendu |
|-----------|---------------------|
| Réponse sans marqueur de fin de tour | `juge-fin-de-tour` **proceed** (plus de sanction) |
| Contexte de tour absent / identité nulle | Les deux contrôles **proceed**, ne lèvent jamais |
| Poste hors ligne | Les deux contrôles **proceed** (plus de report/réclamation) ; le tour se clôt en une fois, sans boucle de refus |
| Un fait durable écrit dans une fiche de carte | Détecté côté serveur (`GovernanceMapGrowth`), sans marqueur |

---

## Critères d'acceptation
- [ ] `juge-fin-de-tour` **ne bloque plus** une réponse sans marqueur (test : réponse sans marqueur →
      `proceed`, bout en bout dans la boucle).
- [ ] `promotion-dette-bloquante` ne s'appuie plus sur un marqueur : `proceed` quel que soit l'entrant.
- [ ] La promotion/dette est suivie **uniquement côté serveur** : un fait écrit dans une fiche est
      détecté « promu » (`GovernanceMapGrowth`) ; aucune sortie « rien à ranger » n'est possible.
- [ ] Le crochet de fin de tour **ne relance pas** l'agent pour la tenue de carte (cohérent SF-125-04).
- [ ] Non-régression : la carte reste **alimentée** (écritures détectées) ; `IntegritePosteControl`
      (dette/intégrité) et `JugeIndependantControl` (audit fichiers) **inchangés** ; F-126 / F-119
      intacts ; les deux contrôles restent **déclarés** dans le paquet (5 contrôles).

---

## Périmètre

### Hors scope (explicite)
- Le contenu du paquet `savoir-durable` → livré en **SF-125-06a**.
- `IntegritePosteControl`, `JugeIndependantControl`, `GovernanceMapGrowth`/`GovernanceMapDestinations`
  (suivi serveur) : **non modifiés** — ils portent déjà le suivi par fichiers.
- Suppression de la table `promotion_reportee` : **non** (changeset Liquibase `106` conservé ; table
  dormante, non mappée — pas de migration destructive sur base partagée).

---

## Technique

### Endpoint(s)
Aucun.

### Tables impactées
Aucune (la table `promotion_reportee` devient dormante ; entité JPA retirée, changeset conservé).

### Migration Liquibase
- [x] Non applicable (aucune migration ajoutée ; `106-promotion-reportee.xml` conservé pour les bases
      existantes)

### Composants impactés
- `control/JugeFinDeTourControl` (neutralisé → `proceed()`)
- `control/PromotionDetteBloquanteControl` (neutralisé → `proceed()`)
- **Supprimés** : `control/FinDeTourMarker`, `control/PromotionReportee`, `control/PromotionReporteeStore`,
  `control/InMemoryPromotionReporteeStore`, `control/JpaPromotionReporteeStore`,
  `control/PromotionReporteeEntity`, `control/PromotionReporteeRepository`
- `atelier/AtelierChatService` : retrait de `appendNotice` + du branchement `verdict.hasNotice()` ;
  `stripTurnMetadata` **conservé**
- `juge/JugeVerdict` : référence de javadoc au marqueur reformulée (pas de dépendance de code)

### Préoccupation transversale — Auth / tenant / plans / routing
Aucune. Les contrôles restent bornés au couple `(userId, workspaceId)` du contexte (isolation
inchangée). Aucun endpoint, aucune route, aucun plan, aucun changement de Principal.

---

## Plan de test

### Tests unitaires
- [ ] `EndOfTurnControlsTest` (réécrit) : `juge-fin-de-tour` et `promotion-dette-bloquante`
      **proceed** — sans marqueur, avec marqueur, dette non nulle, identité absente — et
      `JugeIndependantControl` (audit serveur des fichiers) **bloque toujours** sur un durable
      cité-mais-absent (la carte reste alimentée côté serveur).
- [ ] `GovernanceMapGrowth*Test` (existants) : un fait écrit → delta constaté côté serveur (verts).

### Tests d'intégration / boucle
- [ ] `AtelierChatServiceEndOfTurnCheckpointTest` (mis à jour) : avec le **vrai** `JugeFinDeTourControl`,
      une réponse **sans** marqueur **ne repart pas** (proceed, un seul tour).
- [ ] `AtelierChatServiceOfflinePromotionTest` (réécrit) : runner **hors ligne** → **un seul tour**,
      aucune boucle de refus, réponse = texte du modèle (marqueur hérité éventuel retiré) ; +
      `stripTurnMetadata` (casse/espaces/occurrences, autre commentaire épargné, marqueur-seul → vide).
- [ ] `GovernanceSeededPackageIntegrationTest` (inchangé) : 5 contrôles toujours déclarés.
- [ ] Tests supprimés (contrepartie = mécanisme marqueur retiré) : `FinDeTourMarkerTest`,
      `PromotionReporteeTest`, `PromotionReporteeStoreJpaTest`, `OfflineEndOfTurnControlsTest`.

### Isolation workspace
- [x] Testée via le couple `(userId, workspaceId)` des contextes de contrôle (les contrôles ne lèvent
      jamais sur identité absente ; aucune donnée d'un autre couple ne remonte). Aucune route nouvelle.

---

## Notes et décisions
- **Décision (drapeau) : neutraliser plutôt que retirer du paquet.** Le cadrage conserve les deux
  contrôles (« juge-fin-de-tour ne requiert plus de marqueur », « promotion-dette-bloquante s'appuie
  sur les écritures »). On les garde **déclarés** (identité de paquet stable, pas de rupture pour un
  poste déjà activé) mais **`proceed()`** ; le suivi réel vit dans `GovernanceMapGrowth` (lectures) +
  `JugeIndependantControl` (audit fichiers) + `IntegritePosteControl` (dette) — déjà 100 % serveur.
- L'infra marqueur (`FinDeTourMarker`, `PromotionReportee`) devient morte → supprimée (dette technique
  évitée). `stripTurnMetadata` conservé pour ne pas laisser fuir un marqueur hérité.
- Rappel de dette : reste **non bloquant et hors réponse** via `IntegritePosteControl` (existant).

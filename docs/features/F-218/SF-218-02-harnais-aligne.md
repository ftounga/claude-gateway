# Mini-spec — [F-218 / SF-218-02] Le harnais aligné sur le mode offensif

## Identifiant
SF-218-02

## Feature parente
F-218 — Le mode offensif

## Statut
En cours

## Date de création
2026-10-27

## Branche Git
`feat/SF-218-02-harnais-aligne`

---

## Objectif
Retirer les accords que le harnais impose sur des actions (publier une page ou une vidéo privée, écrire dans Microsoft 365), pour que l'action aille à done sans clic, tout en gardant les choix explicites de l'utilisateur.

## Comportement attendu

### Cas nominal
- `page_publier` (F-109) : publiée sans invite d'accord.
- `video_publier` / `audio_publier` (F-213) : publiés sans invite d'accord.
- Écritures Microsoft 365 (F-108 : créer un dossier, déposer, renommer, déplacer, copier, remplacer une version, supprimer vers la corbeille) : plus d'accord **forcé** ; elles passent par la **politique de permission** comme tout outil (défaut ALLOW). Une règle `ASK` ou `DENY` posée par l'utilisateur reste respectée. Le journal d'audit trace toujours chaque appel.
- Descriptions d'outils alignées : plus de « l'utilisateur confirme d'un clic » ni « doit l'AUTORISER » ; les écritures Teams gardent « n'annonce rien comme fait avant le résultat ».
- **Coupe-circuit** `app.atelier.offensive-mode` (défaut `true`) : à `false`, les trois accords d'avant F-218 reviennent à l'identique.

### Cas d'erreur
- Règle `DENY` sur un outil d'écriture Teams → refus, rien n'est émis (inchangé, F-121).
- Règle `ASK` → invite d'autorisation (inchangé) ; refus → rien n'est émis.
- Coupe-circuit à `false` → refus ou délai sur l'accord : rien n'est publié / écrit (comportement F-108/F-109/F-213).

## Critères d'acceptation
1. En mode offensif, publier une page ne produit aucune invite et l'exécuteur range la page.
2. En mode offensif, publier une vidéo ne sollicite pas `RunnerConfirmationGate` et la vidéo est publiée.
3. En mode offensif, une écriture Teams sans règle part sans invite (`teamsRead` appelé, 0 invite).
4. Coupe-circuit à `false` : les tests historiques F-108/F-109/F-213 passent à l'identique.
5. Accords **gardés** : voix off (F-215, payant), images (F-142, payant), `agent_ask_before_bash`, règles persistées ASK/DENY, attestation « participants prévenus » de la capture de réunion.

## Périmètre
### Hors scope (explicite)
- Accords de dépense (voix off, images, devis vidéo au-dessus du seuil) : gardés (arbitrage tracé).
- Doctrine (SF-218-01), écran (SF-218-03).
- `agent_ask_before_bash` et la porte du parcours guidé : gardés.

## Valeurs initiales
`app.atelier.offensive-mode` = `true`.

## Contraintes de validation
Sans objet.

## Technique
### Endpoint(s)
Aucun.
### Tables impactées
Aucune.
### Migration Liquibase
Aucune.
### Composants Angular (si applicable)
Aucun (l'invite d'autorisation existante n'apparaît simplement plus pour ces outils).

### Préoccupation transversale — Auth / permissions
Composants impactés et vérifiés : `AtelierChatService.applyPagePublish`, `applyVideoPublish`, branche `teamsWrite` de l'exécution d'outil runner, `resolveEffect`/`defaultEffect` (inchangés), `offersAlwaysAllow` (inchangé), `TeamsToolCatalog.fileWriteTools`, `PageToolCatalog`, `VideoToolCatalog`. Isolation `user_id` : inchangée (les exécuteurs vérifient déjà la propriété du workspace).

## Plan de test
### Tests unitaires
- `AtelierChatServicePageToolTest` : +1 (mode offensif, aucune invite) ; tests historiques sous coupe-circuit.
- `AtelierChatServiceRunnerGuardTest` : +1 (écriture Teams sans invite) ; tests F-108 sous coupe-circuit.
- `AtelierChatServiceVideoRetoursTest` : +2 (sans accord ; coupe-circuit ramène l'accord).
- `TeamsToolCatalogTest` : descriptions sans « AUTORISER ».
### Tests d'intégration
- Suite backend complète verte (démarrage du contexte Spring avec la nouvelle propriété).
### Isolation workspace
Inchangée.

## Dépendances
### Subfeatures bloquantes
SF-218-01 (mergée).
### Questions ouvertes impactées
Aucune.

## Notes et décisions
- Décision PO 2026-10-27 : « envoi, suppression » vont à done → l'écriture Microsoft 365 n'est plus confirmée de force (révise la garde du cadrage F-108 §4.4).
- Choix d'implémentation : faire passer l'écriture Teams par la politique de permission (plutôt que l'autoriser sans condition) garde à l'utilisateur la possibilité d'exiger un accord par une règle — c'est sa consigne explicite, conforme à la décision.
- Coupe-circuit par propriété : réversible sans changement de code.

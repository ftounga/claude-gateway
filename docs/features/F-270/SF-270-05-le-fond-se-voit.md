# Mini-spec — [F-270 / SF-270-05] Le fond se voit

## Identifiant
`F-270 / SF-270-05`

## Feature parente
`F-270` — Parler à Jarvis pendant qu'il travaille

## Statut
`in-progress`

## Date de création
2026-11-18

## Branche Git
`feat/SF-270-05-satellites`

---

## Objectif
L'écran montre, sans phrase, combien de travaux tournent en fond, sur quoi et dans quel état : un satellite lumineux
par travail, en orbite autour du cœur, étiqueté d'un nom court et teinté par sa phase.

## Cause traitée (cadrage C5, D6)
- Le cœur passe en `reflexion` pour un tour direct (« je ne vous écoute pas ») ; les perles n'existent que pour les
  délégations, sans nom ; la liste « Recherches en cours » est du texte en phrases (contraire à la règle PO).

## Comportement attendu

### Cas nominal
1. Chaque travail en fond (délégation, tour en fond SF-270-02, commande enchaînée, course autonome F-219 — lus par
   `GET /api/jarvis/works`, complétés par ceux que l'écran vient de lancer) est un **satellite** : perle lumineuse +
   **nom court** (`LDIC-325`, `chantier 4`, `facturation`), en orbite lente autour du cœur.
2. Teinte par phase : réfléchit `#9B7BFF` (deep, existant) ; **exécute sur le poste `#E8B44A`** (or, nouveau rôle `exec`
   de `HOLO_COLORS`, défaut validé) ; attend votre oui `#5FD4FF` (wait) ; en attente de son tour : perle creuse
   violette ; fini `#4CF2A0` puis s'éteint (20 s) ; échec `#FF5A6E` puis s'éteint ; arrêté : orange atténué puis
   s'éteint.
3. Le cœur ne montre que l'échange en cours (écoute, parole, réflexion de SA réponse) : un travail en fond ne le met
   jamais en `reflexion` (déjà vrai depuis SF-270-02 en conversation).
4. La liste texte « Recherches en cours » (`jv__delegations`) est **retirée** (gabarit, styles ; code supprimé).
5. Téléphone (< 600 px) : repli allégé — perles et noms plus petits, les 6 plus récents ; en présentation : aucun satellite.
6. Accessibilité : un résumé `role=status` hors écran (« LDIC-325 : exécute, facturation : réfléchit »).
7. Mouvement réduit (`prefers-reduced-motion`) : orbite et pulsations figées.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Lecture des travaux en échec | les satellites des travaux lancés par l'écran restent |
| Plus de 12 travaux | les 12 plus récents (D8 : 8 + 4) |
| Nom long | coupé à 24 caractères (passerelle) |

## Critères d'acceptation
- [ ] Un satellite par travail, nom court, teinte de sa phase (test du composant et de `satellitesOf`).
- [ ] `HOLO_COLORS.exec = #E8B44A`, variable CSS `--cg-holo-exec`.
- [ ] La liste `jv__delegations` n'existe plus (gabarit et styles).
- [ ] Téléphone : 6 au plus, plus petits ; présentation : aucun satellite.
- [ ] Captures PC 1920×1080 et téléphone 390×844 (réfléchit, exécute, attend votre oui, en attente, fini) relues.

## Périmètre

### Hors scope (explicite)
- Halo du cœur teinté par le travail le plus fort, constellation des étapes : F-271 (SF-271-05) ; `strongestPhase` est
  fourni pour elle.
- Ordres d'écran « 🖥 montrer » : intouchés. Panneaux latéraux : chantier séparé.

## Valeurs initiales
| Paramètre | Valeur |
|---|---|
| Orbite | 60 s par tour |
| Fin visible | 20 s (passerelle) |
| Satellites max | 12 |

## Contraintes de validation
Aucune saisie.

## Technique

### Endpoint(s)
`GET /api/jarvis/works` (SF-270-01), déjà relu par l'écran (SF-270-04).

### Tables impactées
Aucune.

### Migration Liquibase
Aucune.

### Composants Angular (si applicable)
- `jarvis-satellites.component.ts/.scss` (neuf).
- `stage3d/jarvis-motion.ts` : rôle `exec`.
- `jarvis.component.html/.scss/.ts` : satellites autour du cœur, liste texte retirée.

## Plan de test

### Tests unitaires
- `jarvis-satellites.component.spec.ts` : `satellitesOf` (fusion, doublons, plafond), `strongestPhase`, rendu (nom,
  teinte, compact, résumé accessible).
- `jarvis.component.spec.ts` : plus de liste texte ; satellites présents pendant une délégation.

### Tests d'intégration
- Captures réelles (Chrome sans tête) PC et téléphone, relues.

### Isolation workspace
Sans objet (lecture déjà isolée en SF-270-01).

## Dépendances

### Subfeatures bloquantes
SF-270-01, SF-270-04 (relecture des travaux).

### Questions ouvertes impactées
Aucune (teinte « exécute » = or, défaut non objecté par le PO).

## Notes et décisions
- **D1** Les satellites sont une couche légère au-dessus du cœur (2D et 3D) plutôt que des objets de la scène 3D : un
  seul rendu pour la vue 2D, la 3D et le téléphone, sans toucher au moteur ; le champ violet des cerveaux (F-236) reste
  le signe « Opus travaille ».

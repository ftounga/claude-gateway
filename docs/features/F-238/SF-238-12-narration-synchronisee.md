# Mini-spec — [F-238 / SF-238-12] Narration synchronisée

## Identifiant
SF-238-12

## Feature parente
F-238 — Architecture en 3D, itération 2 « Jarvis la raconte » (cadrage §11, §10.1 T-A7/T-A8, §17)

## Statut
done

## Date de création
2026-11-15

## Branche Git
`feat/SF-238-12-narration`

---

## Objectif

Quand Jarvis prononce une phrase, allumer dans la scène d'architecture ce qu'elle nomme — services, zones, flux —
par un rapprochement **local et prudent** (zéro appel réseau, zéro coût), avec les transitions T-A7 (flux) et
T-A8 (service).

## Comportement attendu

### Cas nominal
1. `architecture-narration.ts` (**pur**) : `ArchitectureNarrator(layout).read(phrase, focus)` ⇒
   `{ lit: string[], flow: string[], groups: number }`, en < 5 ms, sans réseau :
   1. **Noms** : libellés des services et des zones, normalisés comme F-234 (`normalizeName`, frontière de mot,
      ≥ 3 caractères, mots courants exclus) ; un libellé porté par deux éléments n'allume rien.
   2. **Types et sigles** (égalités seulement, table d'alias F-142 reprise) : « l'ALB », « la Lambda », « le bucket
      S3 », « les instances », « EKS », « NAT »… ⇒ tous les services de ce type.
   3. **Familles** : « la base » ⇒ `database`, « le cache » ⇒ ElastiCache, « la file » ⇒ SQS, « le stockage » ⇒
      `storage` — **seulement si un seul** service de la famille est dans le focus courant (graphe entier sans
      focus), sinon rien.
   4. **Flux** : deux services cités **dans l'ordre** et reliés (chemin orienté ≤ 4 liaisons) ⇒ le chemin s'allume ;
      « entre par X » ⇒ chemin depuis l'acteur externe jusqu'à X ; « flux sortant » / « entrant » ⇒ liaisons
      sortantes / entrantes du focus (ou du service cité).
   5. **Zones** : libellé (« zone B », « sous-réseau privé A ») ou nature unique (« le VPC ») ; « les deux zones » ⇒
      toutes les AZ.
   Priorité flux > service > zone ; **au plus 3 groupes** allumés (un chemin, un type = un groupe).
2. **Scène** : `ArchitectureEngine.narrate(phrase)` applique le résultat à l'instant (état `lit` + `flow`) :
   **T-A8** (0,35 s, `sortie`) halo 0,2 → 1, socle qui pulse une fois, étiquette en blanc ; ce qui n'est plus cité
   s'éteint en 0,9 s (`souffle`) ; **T-A7** le chemin se trace de la source à la cible segment par segment
   (150 ms/segment, borné à 0,9 s, `sortie`), puis ses particules ×2 pendant 2 s (sans saut de position).
3. **Banc** `architecture-narration&t=` : une narration de 4 phrases rejouée à l'instant `t` (aucune voix, aucun
   réseau) ; captures.
4. **Branchement sur `spoken`** : le point d'entrée est `narrate(phrase)` ; l'appel depuis `jarvis.component.ts`
   (signal `spoken`, début de lecture de chaque phrase) se pose avec la visionneuse 3D de SF-238-10 — seule
   consommatrice dans `/jarvis` (aucun code mort avant).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Phrase vide, sans rien de reconnu | rien ne s'allume ; ce qui l'était s'éteint (T-A8 inverse) | — |
| Nom ambigu (deux services « Web ») | rien pour ce nom | — |
| Famille avec plusieurs services dans le focus (« la base » avec Aurora + 3 caches) | rien | — |
| Deux services cités mais non reliés dans ce sens | les services seuls, aucun chemin | — |
| Plus de 3 groupes cités | les 3 premiers selon la priorité flux > service > zone | — |
| Texte hostile (balises) | traité comme du texte (aucun DOM) | — |

## Critères d'acceptation
- [ ] Jeu de **30 phrases étiquetées** (3-tiers et « tout ») : précision ≥ 95 % (une lueur fausse au plus sur le jeu).
- [ ] Aucun allumage sur une famille ambiguë (test dédié).
- [ ] ≤ 3 groupes allumés pour toute phrase du jeu.
- [ ] Allumage ≤ 100 ms après le début de la phrase : `read` < 5 ms (mesuré), appliqué à l'image suivante, T-A8
      démarre à 0 (test sur le directeur : halo > 0,2 dès 16 ms).
- [ ] **Aucun appel réseau** : module pur, sans `HttpClient` ni `fetch` (test : `fetch` et XHR espionnés, 0 appel).
- [ ] T-A7 : chemin tracé segment par segment, 150 ms/segment borné à 0,9 s ; particules ×2 pendant 2 s ensuite,
      position continue (test) ; T-A8 et extinction 0,9 s (test).

## Périmètre — hors scope
Appel depuis `jarvis.component.ts` (avec SF-238-10) ; outil `afficher` du cerveau rapide (SF-238-10/13) ; banc à vraie
voix (pas branché à la voix réelle) ; nouvelle consigne (aucune).

## Valeurs initiales
Sans objet.

## Contraintes de validation
| Champ | Règle |
|---|---|
| phrase | ≤ 1 000 caractères lus (au-delà, tronquée) |
| groupes allumés | ≤ 3 |
| chemin de flux | ≤ 4 liaisons |

## Technique
- Endpoints, tables, migration : aucun.
- Nouveaux : `architecture/architecture-narration.ts` (+ spec, jeu de 30 phrases).
- Modifiés : `architecture-stage-director.ts` (état `flow`, transitions T-A7/T-A8 calculées par image sans
  allocation), `engine/architecture-scene-3d.ts` (`narrate`, balayage du chemin, vitesse des particules),
  `architecture-banc.ts` + `jarvis-banc.ts` (scène `architecture-narration`), `architecture-stage.component.ts`.

## Plan de test
### Tests unitaires
- Narration : 30 phrases étiquetées (précision, rappel indicatif), familles ambiguës, noms ambigus, ordre des services
  (flux A → B oui, B → A non), « entre par », sortant/entrant, zones, ≤ 3 groupes, texte hostile, durée < 5 ms,
  aucun appel réseau.
- Directeur : T-A8 (montée 0,35 s, extinction 0,9 s, pulsation), T-A7 (balayage, borne 0,9 s, ×2 pendant 2 s sans
  saut), déterminisme.
### Tests d'intégration
- Banc `architecture-narration` : captures à 0,2 s / 0,6 s / 1,2 s / 3 s après une phrase, PC et téléphone.
### Isolation utilisateur
- Non applicable (aucune donnée, rendu 100 % navigateur).

## Préoccupations transversales
- Navigation / routing : non. Préfixe de cache / consigne : **non** (aucune consigne touchée). Tenant, Auth, Plans : non.

## Dépendances
- SF-238-09 (scène) : livrée juste avant. Aucune dépendance F-237.
- Questions ouvertes : aucune.

## Notes et décisions
- **D-12-1** « les instances » allume **tous** les services du type (égalité de type), c'est un seul groupe.
- **D-12-2** Le chemin d'un flux est le plus court chemin orienté (≤ 4 liaisons) ; à égalité, le premier dans l'ordre
  du graphe (déterministe).
- **D-12-3** Résultat mesuré : sur le jeu de 30 phrases, **0 lueur fausse** (précision 100 %), ≤ 3 groupes partout,
  lecture < 5 ms par phrase, aucun `fetch` ni XHR (espionnés).
- **D-12-4** Le parcours des particules est **intégré** par le directeur (secondes de flux) : l'accélération ×2 de T-A7
  ne fait jamais sauter une particule ; le pool et l'image restent sans allocation.
- **D-12-5** La limite « 3 allumés » est portée par la narration (en **groupes**) : un type comme « les instances »
  allume ses services d'un seul geste ; le directeur n'écrête plus les ids.

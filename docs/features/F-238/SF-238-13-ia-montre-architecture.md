# Mini-spec — [F-238 / SF-238-13] L'IA montre d'elle-même

## Identifiant
SF-238-13

## Feature parente
F-238 — Architecture en 3D, itération 2 « Jarvis la raconte » (cadrage §7 I6, §11.3, §17)

## Statut
done

## Date de création
2026-11-15

## Branche Git
`feat/SF-238-13-ia-montre-architecture`

---

## Objectif

Quand le PO pose une question sur l'infrastructure (« comment c'est déployé chez Edenred ? »), Jarvis ouvre de
lui-même le schéma d'architecture le plus récent **en 3D** et cadre l'entrée du trafic ; sans schéma, il propose d'en
dessiner un ; jamais d'architecture sur une question qui ne porte pas sur l'infra.

## Comportement attendu

### Cas nominal
1. **Cerveau rapide** : la doctrine est déjà dans la **version 22** (description fixe de l'outil `afficher`, posée par
   SF-238-10 ; aucune modification ici) : appui (6) « architecture ⇒ le schéma », puis `afficher(flux, « entrée », appui)` ; sans schéma,
   « Voulez-vous que je dessine l'architecture, Monsieur ? » et, sur « oui », `deleguer` (render_diagram puis montrer).
2. **Écran** : une galerie d'architecture montrée **d'initiative** s'ouvre directement sur la plus récente
   (`select = 1`) ⇒ visionneuse 3D, T-A1 ; le premier cadrage `flux / entrée` arrivé avant le graphe attend la
   visionneuse (SF-238-10) puis allume le chemin d'entrée ; la narration (SF-238-12) allume ce que Jarvis dit.
3. **Banc du cerveau rapide** (jeu `PIECES`, contexte fixe) : 10 questions **réelles** du PO sur l'infra (relevées en
   lecture seule dans `atelier_messages`, adaptées au sujet du banc) ⇒ catégorie `ARCHI` (≥ 9/10, la pièce
   `diag:` montrée) ; 10 phrases **hors infra** ⇒ `ARCHI_NEUTRAL` (0/10 architecture montrée).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Aucun schéma d'architecture | rien de montré, proposition (question) ; délégation seulement sur « oui » | — |
| Galerie d'architecture **demandée** (« la galerie des schémas ») | galerie à choisir (rien d'ouvert d'office) | — |
| Mode présentation / sobre | inchangé F-237 (rien d'initiative) | — |

## Critères d'acceptation
- [x] Banc de 10 questions réelles : la pièce est ouverte quand un schéma existe (≥ 9/10) — **9/10 et 10/10** (Sonnet 5.5, 2 passages).
- [x] Jamais quand la question ne porte pas sur l'infra (0/10 faux positifs) — **0/10 et 0/10**.
- [x] Aucune consigne système modifiée au-delà de la version 22 (test : `VERSION` = 22, préfixe inchangé).
- [x] Une galerie d'architecture d'initiative s'ouvre sur la plus récente (test).

## Périmètre — hors scope
Mesure (SF-238-14) ; changement de consigne (aucun).

## Valeurs initiales
Sans objet.

## Contraintes de validation
| Champ | Règle |
|---|---|
| pièces d'initiative | ≤ 3 par réponse (inchangé) |

## Technique
Endpoints, tables : aucun. `jarvis-piece-show.ts` (sélection d'office), `pieces-bench.json` (+20 cas),
`JarvisPiecesBench` (catégories `ARCHI`, `ARCHI_NEUTRAL`, garde « architecture hors sujet »),
`JarvisPiecesBenchLiveTest` (rapport par catégorie).

## Plan de test
### Tests unitaires
`JarvisPiecesBenchTest` (jugement ARCHI / ARCHI_NEUTRAL, porte) ; `jarvis-piece-arch.spec.ts` (ouverture d'office).
### Tests d'intégration
Banc réel du cerveau rapide (`JarvisPiecesBenchLiveTest`, modèle de prod, 2 passages) ; banc à vraie voix F-256
(2 passages, boucle legacy).
### Isolation utilisateur
Non applicable (aucune lecture nouvelle).

## Préoccupations transversales
- Préfixe de cache / consigne : **non** (version 22 inchangée). Navigation, tenant, auth, plans : non.

## Dépendances
SF-238-10 (consigne v22, visionneuse, attente du graphe), SF-238-12 (narration) : livrées.

## Notes et décisions
- **D-13-1** Questions réelles relevées en lecture seule (`atelier_messages`, « architecture », « déployé », « infra »,
  « schéma », « VPC », « EKS ») puis ramenées au sujet du banc (Edenred-Portail) ; aucune donnée client dans le jeu.
- **D-13-2** Seul écart du passage 1 : I04 (« explique-moi comment fonctionne le réseau sur l'architecture du
  portail ») délégué à Opus sans montrer le schéma — défendable (explication longue), compté comme échec.

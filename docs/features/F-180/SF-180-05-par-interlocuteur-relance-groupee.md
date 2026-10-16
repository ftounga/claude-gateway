# Mini-spec — F-180 / SF-180-05 — Par interlocuteur et relance groupée

## Identifiant
`F-180 / SF-180-05` — feature parente `F-180` *Les accès et les interlocuteurs* (décisions **D5**, **D7**).
Dépend de SF-180-02 (nature), SF-180-03 (interlocuteurs) — mergées. Branche :
`feat/SF-180-05-par-interlocuteur-relance-groupee`. Statut : `in-progress`. Date : 2026-10-16.

## Objectif
Voir **qui** bloque **quoi** depuis **quand**, savoir **combien de temps chacun met à répondre**, et
**relancer une fois par personne** — par un brouillon groupé déposé dans la saisie, jamais envoyé seul.

## Comportement attendu

### Cas nominal
1. **Vue par interlocuteur** — `GET /api/workspaces/{id}/interlocuteurs/board` →
   ```json
   { "people": [ { "interlocuteurId": "…|null", "name": "Rémi Dupont", "role": "…", "openCount": 11,
                   "demandeCount": 7, "oldestOpenAt": "…", "subjects": ["data-platform", "connecteur"],
                   "avgResponseDays": 4.2, "responseCount": 6,
                   "avgByCategorie": { "ACCES": 5.1, "VALIDATION": 1.0 },
                   "open": [ TerminalActionResponse… ] } ],
     "byCategorie": [ { "categorie": "ACCES", "avgResponseDays": 4.3, "responseCount": 12, "openCount": 12 } ] }
   ```
   - périmètre : le poste du terminal possédé (ou ce terminal s'il est hébergé) ;
   - une personne = un interlocuteur (SF-180-03) ; sans interlocuteur (terminal hébergé, reprise pas encore
     passée), regroupement par **nom normalisé** de `requested_to`, à défaut `person` ;
   - seules les attentes **ouvertes** entrent dans `open` (les plus anciennes d'abord), triées par personne :
     le plus d'attentes ouvertes d'abord, puis la plus ancienne ;
   - « sujets bloqués » : noms des terminaux d'origine (et du sujet du Radar quand il est connu), distincts ;
   - les gestes `MOI` ne sont pas des attentes envers autrui : exclus de la vue par personne.
2. **Mesure (D7)** : délai **demande → réponse** = `closed_at − requested_at` (jours, décimal) sur les
   attentes **Fait** demandées ; moyenne par personne, par personne et nature, et par nature sur le poste.
   « Rémi répond en 4 j en moyenne » s'affiche dès une réponse mesurée.
3. **Relance groupée (D5)** : sur chaque personne, [Relancer Rémi (5)] dépose dans la saisie **un seul**
   brouillon listant ses attentes ouvertes (énoncé, depuis quand, ce que ça débloque) et demandant à
   l'agent de rédiger **un** message de relance ; [Par courriel à moi] dépose le même brouillon demandant
   à l'agent de **me l'envoyer par courriel** (F-110, `email_me`) pour que je le transfère. **Rien n'est
   envoyé** sans le geste d'envoi de l'utilisateur ; aucun état ne change.
4. **Panneau** : la vue « Personnes » devient la vue par interlocuteur : nombre, plus ancienne, sujets,
   délai moyen, attentes dépliables, boutons de relance ; en tête, le délai moyen par nature.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Terminal d'un autre compte | 404 |
| Personne sans attente ouverte | absente de la vue |
| Aucune réponse mesurée | `avgResponseDays` nul, « pas encore de réponse mesurée » |
| Fermeture antérieure à la demande (donnée incohérente) | ignorée dans la mesure |

## Contraintes de validation
| Élément | Règle |
|---|---|
| Brouillon | 30 attentes au plus par relance (au-delà : « et N autres ») |
| Mesure | sur les attentes Fait des 180 derniers jours |

## Critères d'acceptation
- [ ] La vue montre Rémi avec 11 attentes, triées par ancienneté.
- [ ] [Relancer] dépose un seul texte groupé dans la saisie, non envoyé.
- [ ] « Rémi répond en 4 j en moyenne » quand ses réponses sont mesurées ; moyenne par nature visible.
- [ ] Les gestes « à toi » n'apparaissent pas comme une personne à relancer.
- [ ] Bob ne voit rien d'Alice (404).

## Plan de test
- **Unitaires** : `InterlocuteurBoardServiceTest` (regroupement par interlocuteur et par nom, tri, MOI
  exclu, sujets, mesure par personne / nature, données incohérentes, fenêtre de 180 j).
- **Intégration** : `InterlocuteurBoardApiIntegrationTest` — 11 attentes de Rémi, ordre, mesure, isolation Bob.
- **Front** : `groupedFollowUpDraft` (contenu, borne 30, variante courriel), vue (compteurs, délai, ordre,
  relance → brouillon émis sans appel serveur), terminal (brouillon déposé, panneau fermé).

## Impacts
- **Endpoints** : `GET /api/workspaces/{id}/interlocuteurs/board`. **Aucune table.**
- **Composants** : `InterlocuteurBoardService` (nouveau), `InterlocuteurController` ; front modèles,
  service, `terminal-people-view`, panneau (relais du brouillon).

### Préoccupations transversales
- **Contexte tenant** : oui — `InterlocuteurBoardService` (poste du terminal **possédé**, `user_id` sur
  chaque lecture : attentes, interlocuteurs, noms de terminaux et de sujets). Navigation : non (le brouillon
  emprunte le relais existant `followUp` → `draftChange` de F-175).

## Hors périmètre
Envoi automatique de relances ; escalade hiérarchique ; détection de la réponse dans Teams / la boîte.

## Arbitrages (réversibles)
- Délai mesuré depuis `requested_at` (« demande → réponse »), pas depuis la création.
- Fenêtre de mesure 180 jours.
- La variante « courriel à moi » passe par l'agent (`email_me`, F-110) : aucun envoi depuis le panneau.

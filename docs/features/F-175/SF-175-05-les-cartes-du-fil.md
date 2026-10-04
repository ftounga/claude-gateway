# Mini-spec — F-175 / SF-175-05 — Les cartes du fil

## Identifiant
`F-175 / SF-175-05` — feature parente `F-175` *Le fil des attentes* — dépend de SF-175-01→04 (mergées).
Branche : `feat/SF-175-05-cartes-du-fil`. Statut : `in-progress`. Date : 2026-10-05.

## Objectif
Que chaque geste de l'agent sur les attentes se **voie dans le fil**, sous forme de carte, et que la
fermeture proposée se règle d'un clic [Confirmer] [Pas encore].

## Comportement attendu

### Cas nominal (D7)
1. **Carte** `AttenteBlock` (instantané : id, terminal, genre, manière de reconnaissance, description,
   état, à qui / quand / par où, proposition) produite par les outils :
   - `record_blocker` neuve → **ADDED** « Ajouté à tes attentes » ;
   - `record_blocker` qui retombe sur une attente ouverte → **ALREADY** « Déjà demandé » / « Déjà dans
     tes attentes » / « Une attente dit déjà la même chose » (+ « née dans un autre terminal du poste ») ;
   - `update_blocker` vers Demandé → **REQUESTED** « Marqué demandé » ;
   - `close_blocker` → **PROPOSED** « Je pense que c'est réglé » (ou « sans objet »), la parole citée,
     **[Confirmer] [Pas encore]**.
   Annulée ou déjà faite : pas de carte (le texte de l'outil suffit).
2. **Au fil de l'eau** : événement SSE `attente` ; **au rechargement** : la carte est rangée dans le bloc
   de transcription de l'appel (champ `attente`), conservée par le bornage.
3. **Dans tout terminal** (comme la page publiée, F-109) : ce n'est pas une sortie de commande.
4. **État vivant** : les boutons ne s'offrent que si le tableau des attentes dit la proposition encore
   en attente ; après un geste la carte dit « Confirmé : l'attente est fermée. » / « Laissée ouverte. »
   et le terminal relit le tableau (la bande suit). Sans réponse, l'attente reste ouverte. Pas de
   bouton en lecture seule.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Geste en échec | snackbar, boutons rendus |
| Attente disparue du tableau (fermée depuis longtemps) | carte sans boutons |
| Événement `attente` sans attente | ignoré (pas de carte creuse) |
| Sérialisation de la transcription | dates en texte ISO (l'`ObjectMapper` de la transcription n'a pas le module des dates — un `OffsetDateTime` aurait fait perdre la transcription entière) |

## Critères d'acceptation
- [ ] Les quatre cartes sont produites par les bons appels, et aucune pour annulée / faite.
- [ ] La carte part au fil de l'eau (SSE) ET survit au rechargement (transcription, bornage compris).
- [ ] [Confirmer] / [Pas encore] appellent les routes de SF-175-02 sur le terminal de l'attente.
- [ ] Boutons absents si la proposition n'attend plus, ou en lecture seule.

## Plan de test
- **Backend** : `TerminalActionToolTest` (cartes par issue), `AtelierChatServiceAttenteTest` (relais
  `onAttente` + transcription JSON + bornage ; liste jointe au message et pas au système).
- **Front** : `attente-card.component.spec.ts` (textes, boutons selon l'état vivant, confirmer,
  pas encore, échec, lecture seule, événement SSE).
- Suites complètes back + front.

## Impacts
Backend : `AttenteBlock` (nouveau), `TerminalActionToolExecutor.Outcome.card`,
`AtelierTurnReport.Block.attente` (+ forme d'avant conservée), `AtelierProgressListener.onAttente`,
`AtelierChatController` (événement `attente`), `AtelierChatService` (relais + transcription).
Front : modèles, `atelier.service` (événement), `atelier.component` (rangement comme une carte),
`attente-card.component` (nouveau), `atelier-terminal` (rendu aux deux emplacements du fil).
Aucune table.

### Préoccupations transversales
Aucune (pas d'auth, de tenant, de plan ni de route nouvelle ; les gestes passent par les routes
isolées de SF-175-02).

## Hors périmètre
Relance pré-remplie, compteurs rail / mosaïque (SF-175-06) ; reprise de l'existant (SF-175-07).

## Arbitrages (réversibles)
- Carte admise dans **tout** terminal, y compris de projet (comme la page publiée) : c'est un élément
  de la passerelle, pas une sortie de commande.
- État vivant lu sur le tableau déjà chargé (aucun appel par carte).

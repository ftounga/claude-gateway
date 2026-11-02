# Mini-spec — F-241 / SF-241-05 Le portrait vivant : retenir, oublier, relancer

## Identifiant
`F-241 / SF-241-05`

## Feature parente
`F-241` — Jarvis vous connaît : le portrait (cadrage §4 D7, D8, D9, D15, D16 ; §6 SF-241-05)

## Statut
`in-progress`

## Date de création
2026-11-02

## Branche Git
`feat/SF-241-05-portrait-vivant`

---

## Objectif
Le portrait s'enrichit en conversation avec le consentement du PO, et se rafraîchit de lui-même, sans insistance.

---

## Comportement attendu

### Cas nominal
- **Lignes-marques 📌** (consigne figée du cerveau rapide, après le bloc portrait — préfixe caché) : `📌 retenir: <fait>` (proposition « Voulez-vous que je retienne… ? »), `📌 noter: <fait>` (demande explicite « retiens que… », « C'est noté »), `📌 oublier: S<n>` (« oublie que… », « C'est oublié »), `📌 entretien` (« mettons à jour mon portrait »). Retirées du flux par la porte des phrases, **jamais dites**, relayées à l'écran dans `done.guards.pins`.
- **Écran (il enregistre, D7)** : `retenir` ⇒ une seule question en attente (l'invitation ↪ est annulée) ; « oui » dans les 30 s ⇒ `POST /souvenirs` (origine `VOIX`) puis « C'est noté. » ; « non » ⇒ « Entendu, je ne retiens rien. » ; autre chose ⇒ la proposition tombe. `noter` ⇒ enregistré aussitôt ; « non, oublie ça » / « annule » dans les 30 s ⇒ effacé. `oublier S<n>` ⇒ le souvenir n° n (relu à l'instant) est effacé. `entretien` ⇒ entretien de mise à jour (SF-241-02). Mémoire pleine (409) ⇒ « Ma mémoire est pleine, faites le tri dans les réglages. ».
- **Garde serveur « jamais de délégation » (D7)** : « retiens / souviens-toi / garde en mémoire que X » et « oublie que X » ne délèguent jamais : si le modèle a délégué ou omis la marque, la passerelle pose `📌 noter: X` (« C'est noté, Monsieur. ») ou `📌 oublier: S<n>` du souvenir le plus proche (« C'est oublié. », sinon « Je n'ai rien retenu de tel, Monsieur. »).
- **Bornes (D9)** : une seule proposition relayée par réponse ; aucune dans une réponse qui porte « Vous confirmez ? » ou ↪ ; **aucune marque en présentation** — et « retiens / oublie » y reçoit « Nous en parlerons en privé, Monsieur. ».
- **Relance (D15)** : la passerelle joint « [Jarvis — relance du portrait possible …] » au **message** seulement si : portrait revu (relance, entretien ou section) il y a ≥ 90 jours, parole calme (politesse ou phrase courte sans question ni chiffre), pas la première salutation du jour, aucune délégation en cours, aucune alerte de veille depuis 24 h. Jarvis choisit de la poser ou non ; posée, `last_checkin_at` est daté.
- **Souvenirs numérotés** `S<n>` dans le bloc (SF-241-03) : l'oubli vise juste.
- **Banc CONVERSATION** : `expectMark` par tour (noter, retenir, oublier, aucune, sans_relance) contrôlé **sans modèle** ; P11-P17 (55 scénarios au total).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Pas de « oui » dans les 30 s | rien n'est enregistré |
| 31ᵉ souvenir | « Ma mémoire est pleine… », rien enregistré (409) |
| « Oublie que X » sans souvenir proche | « Je n'ai rien retenu de tel, Monsieur. », rien effacé |
| Présentation | aucune marque ; « Nous en parlerons en privé, Monsieur. » |
| Tâche (« rappelle-moi de… », « n'oublie pas de relancer… ») | jamais un souvenir (consigne ; « n'oublie pas que » n'est pas dans la liste de la garde) |
| Écriture en échec | « Je n'ai pas pu le retenir. » |

## Critères d'acceptation
- [ ] CA1 — une proposition n'enregistre rien sans « oui » ; un « oui » enregistre et le souvenir apparaît dans la liste et dans le bloc du tour suivant (version incrémentée, SF-241-01/03).
- [ ] CA2 — « retiens que… » enregistre et le dit ; « non, oublie ça » annule.
- [ ] CA3 — « oublie que… » efface le bon souvenir.
- [ ] CA4 — aucune délégation sur retenir ou oublier.
- [ ] CA5 — au plus une proposition par réponse, jamais en présentation.
- [ ] CA6 — la relance n'est jamais proposée plus d'une fois en 90 jours, ni pendant une tâche.
- [ ] CA7 — banc CONVERSATION ≥ 90 % et zéro défaut grave avec P11-P17 (porte de déploiement).
- [ ] CA8 — un souvenir n'est jamais enregistré sous un autre `user_id` (l'écran écrit par `POST /souvenirs`, filtré par le principal).

## Périmètre — hors scope
Un apprentissage automatique sans consentement ; Opus qui écrit dans le portrait.

## Technique
- Aucune table nouvelle (colonne `last_checkin_at` de SF-241-01). Aucun endpoint nouveau : `POST/DELETE /api/jarvis/portrait/souvenirs`, `GET /api/jarvis/portrait` existants ; `done.guards.pins` sur `POST /api/jarvis/converse`.
- Composants : `JarvisPortraitNote` (Pin, `rememberedFact`, `forgetTarget`, `souvenirFor`, `checkinDue`, `isCheckin`, consigne 📌), `JarvisFastBrainService` (`rememberWithoutDelegation`, `checkinEligible`, `relayedPins`), `JarvisSobriety.alertWithin`, `JarvisPortraitService.markCheckin`, écran (`applyPortraitPins`, `answerPortrait`, `isUndoNote`).

### Préoccupations transversales
- [x] Navigation / routing de la parole — `handle()` : après l'entretien, `answerPortrait` intercepte seulement un « oui/non » dans les 30 s d'une proposition et « non, oublie ça » dans les 30 s d'une note (hors présentation, hors confirmation en attente). Vérifiés : confirmation d'outil, commentaire vidéo, preuve, correction, « dis-m'en plus » (l'invitation ↪ est annulée quand une proposition arrive : un seul « oui » possible), arrêt, changement de sujet, cerveau rapide.
- [x] Contexte tenant — `portraitFor(request.userId())` ; l'écran n'écrit que par les routes filtrées du principal.

## Plan de test
- Unitaires : `JarvisFastBrainPinTest` (noter sans délégation, marque du modèle relayée jamais dite, une proposition et jamais avec « Vous confirmez ? », oubli du bon souvenir / rien à oublier, présentation, relance au bon moment et datée, pas à la première salutation ni pendant une tâche, contrôles du banc).
- Écran : `jarvis.component.spec.ts` (+3 : proposition puis « oui », noter puis « non, oublie ça », oublier S2, mémoire pleine) ; `jarvis-portrait.spec.ts` (`isUndoNote`).
- Isolation : les écritures passent par `/api/jarvis/portrait/souvenirs` (isolation testée en SF-241-01).

## Notes et décisions (arbitrages réversibles)
- **A13** : les 📌 voyagent dans `done.guards.pins` (pas de nouvel événement SSE) : un seul point de lecture côté écran, après l'échange.
- **A14** : la garde serveur répond « C'est noté, Monsieur. » (sans reformuler le fait à la 2ᵉ personne : une conjugaison automatique serait fausse) ; le fait est enregistré tel que dit.
- **A15** : « parole calme » pour la relance = politesse reconnue ou phrase courte sans question ni chiffre ; le moment exact reste choisi par Jarvis (P16 ne l'exige pas).
- **A16** : la relance compte le portrait comme « revu » à sa dernière écriture de section aussi (un portrait tout neuf n'est pas relancé le lendemain).

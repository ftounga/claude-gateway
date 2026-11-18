# Mini-spec — [F-272 / SF-272-05] Les réponses arrivées pendant l'absence, dites à la reprise

## Identifiant
`F-272 / SF-272-05`

## Feature parente
`F-272` — Jarvis : corrections de la démo du 17/11

## Statut
`in-progress`

## Date de création
2026-11-18

## Branche Git
`feat/SF-272-05-reponses-a-la-reprise`

---

## Objectif
La réponse d'une délégation qui arrive pendant la pause ou après la fermeture de l'écran Jarvis est dite, en une
phrase courte, à la reprise — jamais perdue, jamais dite deux fois.

## Cause racine (vérifiée)
Démo : délégation « tickets LDIC » lancée 00:05:09 ; pause 00:05:41 ; `pagehide` 00:05:49 ; réponse d'Opus rangée
00:05:51. La fin de délégation n'existe que dans la file d'annonces **en mémoire de la page** (`announcements`,
alimentée par le flux du tour) : page fermée, flux coupé, la réponse n'est annoncée nulle part. Hypothèse « file
d'annonces perdue à la fermeture » confirmée. Page ouverte et en pause, la file n'attend pas la reprise (elle se dit
au premier moment « libre », pause comprise).

## Comportement attendu

### Cas nominal
1. Fin d'un tour Opus délégué par Jarvis (réponse rangée) : la passerelle pose une **annonce** (F-249)
   `DELEGATION_DONE`, clé `delegation:<id>`, affichée « Réponse prête · <libellé> · <sujet> » (mots-clés, jamais une
   phrase), dite « Réponse sur <libellé> : <ligne 🎙> » (bornée), échéance 12 h.
2. L'écran qui a lancé la délégation et la dit lui-même (file d'annonces) la marque **dite** à la passerelle
   (`POST /api/jarvis/notices/delegations/{delegationId}/spoken`) ; arrivée avant l'annonce, la marque crée une
   annonce close que la fin du tour ne rouvre pas.
3. À l'ouverture de `/jarvis` (sondage des annonces au chargement) puis toutes les 60 s, au repos : une annonce de
   délégation pas encore dite est dite (comme les autres annonces) — y compris après fermeture et réouverture.
4. En **pause** : ni la file d'annonces ni les annonces ne parlent ; à la reprise, elles sont dites.
5. Une annonce de délégation encore suivie par l'écran (en vol dans `delegations`) n'est pas dite par le sondage.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Tour en échec ou interrompu | annonce « Recherche sans réponse · <libellé> », dite « La recherche sur <libellé> n'a pas abouti. » |
| Délégation d'un autre compte | `POST …/spoken` sans effet visible (404 masqué : rien n'est créé chez autrui — l'annonce porte le `user_id` de l'appelant) |
| Coupe-circuit des annonces ouvert | rien n'est posé (comportement F-249) |
| Mode présentation | rien n'est dit (règle F-249) |

## Critères d'acceptation
- [ ] Fin d'un tour délégué ⇒ annonce `DELEGATION_DONE` du compte, clé `delegation:<id>`, ouverte et non dite (test).
- [ ] Marquée dite avant la fin du tour ⇒ la fin ne la rouvre pas (test).
- [ ] Bob ne voit ni ne marque l'annonce d'Alice (test d'intégration).
- [ ] Écran : en pause, la file d'annonces et le sondage se taisent ; à la reprise ils parlent (test du composant).
- [ ] Écran : une annonce de délégation en vol n'est pas dite par le sondage, elle est marquée dite à l'annonce (test).

## Plan de test
### Tests unitaires
`JarvisDelegationNoticesTest` (textes, ordre marque/fin), `jarvis.component.spec.ts` (pause, en vol, marque dite).
### Tests d'intégration
`JarvisNoticeApiIntegrationTest` étendu (`…/delegations/{id}/spoken`, isolation A/B).
### Isolation utilisateur
Clé et lecture sous le `user_id` de l'appelant.

## Tables / endpoints / composants impactés
Table `jarvis_notices` (genre `DELEGATION_DONE`, sans migration : `kind` varchar(24)). Endpoint **nouveau**
`POST /api/jarvis/notices/delegations/{delegationId}/spoken`. `JarvisNoticeService`, `NoticeTexts`,
`JarvisDelegationNotices` (nouveau), `AtelierChatService` (fin du tour délégué), front `jarvis-notices.ts`,
`jarvis.component.ts`.

## Préoccupations transversales
Aucune (endpoint authentifié comme les autres annonces, `user_id` du principal).

## Hors périmètre
Notification push (F-185) de la réponse : l'annonce suffit à la reprise ; le push reste le canal des alertes.

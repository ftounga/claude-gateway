# Mini-spec — F-249 / SF-249-07 L'écran « Mails de vos clients »

## Identifiant
`F-249 / SF-249-07`

## Feature parente
`F-249` — Les mails et l'agenda Outlook de vos clients (cadrage D16, D20)

## Statut
`done` (2026-11-07)

## Date de création
2026-11-07

## Branche Git
`feat/SF-249-07-ecran-mails-clients`

## Objectif
Dans les réglages de `/jarvis`, un volet montre, client par client, où glisser ses mails Outlook et ce qu'il en advient,
et permet de régler le rappel du soir, les alertes avant réunion et les mails répétitifs.

## Comportement attendu

### Cas nominal
1. Un bouton « Mails clients » (icône `forward_to_inbox`) à côté de « Mes boîtes » / « Mes agendas », dans la barre et
   dans l'écran de veille, **masqué en présentation** ; il ouvre le volet `app-jarvis-client-mail` (et ferme les autres).
2. Le volet (`GET /api/jarvis/client-mail`) liste chaque poste : nom, pastille « runner à jour » ou « runner à mettre à
   jour » (capacité `mail_deposit`), **dossier** `<racine>/jarvis/mails/` en police mono, nombre de mails, dernier
   dépôt, « déposé aujourd'hui », interrupteur « Rappel du soir ».
3. Sous chaque poste : les **mails répétitifs proposés** (« Expéditeur répétitif (4) : alertes@… » — « Ignorer » /
   « Garder ») et les **règles actives** (« Ignoré — Objet : … » — « Retirer »).
4. Formulaire « Rappel et alertes » (`mat-form-field` outline) : heure, rappels (1-3), minutes entre deux (15-180),
   alerte avant réunion (0-60), « Jours ouvrés seulement » ; « Enregistrer » ⇒ `PUT /api/jarvis/client-mail/settings` ;
   un refus (400) affiche le message de la passerelle.
5. Charte : verre navy §23 du volet Jarvis (patron `jarvis-mailboxes`), aucune couleur nouvelle ; responsive (pleine
   largeur sous 768 px).

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Lecture impossible | message « Impossible de lire l'état des mails des clients. » |
| Réglage refusé (400) | message de la passerelle sous le formulaire |
| Interrupteur refusé | snackbar « Réglage non enregistré. » |
| Proposition déjà traitée (409) | relecture du volet |
| Aucun poste | « Aucun poste : appairez d'abord le runner du poste client. » |

## Contraintes de validation
Bornes rappelées dans les champs (`min`/`max`), contrôlées par la passerelle (SF-249-05).

## Critères d'acceptation
- [ ] Le volet liste les postes, leur dossier et l'état du runner.
- [ ] L'interrupteur, le formulaire, « Ignorer », « Garder », « Retirer » appellent les bonnes routes et mettent la vue
      à jour.
- [ ] Le bouton et le volet n'existent pas en présentation.
- [ ] Charte et responsive conformes.

## Plan de test
- **Specs** `jarvis-client-mail.component.spec.ts` (HttpTestingController) : rendu, runner à mettre à jour,
  interrupteur, enregistrement + 400, ignorer/garder/retirer, aucun poste ; `jarvis-client-mail.spec.ts` (client) ;
  `jarvis.component.spec.ts` : bouton absent en présentation.
- Isolation : assurée par la passerelle (SF-249-05/06) ; l'écran n'envoie que des identifiants reçus.
- Suite complète frontend à la review.

## Impact
- **Frontend** : `jarvis/jarvis-client-mail.ts`, `jarvis/jarvis-client-mail.component.ts`, `jarvis.component.{ts,html}`
  (bouton + volet). Aucune route.

## Préoccupations transversales
- Navigation : aucune route ajoutée ; aucun garde modifié.

## Hors périmètre
L'annonceur (SF-249-08).

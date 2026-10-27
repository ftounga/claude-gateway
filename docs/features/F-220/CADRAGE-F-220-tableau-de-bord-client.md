# CADRAGE F-220 — Le tableau de bord du client

**Statut :** brouillon du 2026-10-27, à compléter par l'agent de livraison (maquette, découpage)
**Demande PO :** 2026-10-27
**Dépend de :** F-219 (API de préflight et de lancement en autonome)

## Le besoin, dans les mots du PO
« J'utilise l'application de manière intense, j'ai beaucoup de chantiers en cours. Chez Edenred, j'oublie même qu'il y a des choses à faire, et il faut que mon responsable me rappelle que je n'ai pas fini ce sujet. Je veux un dashboard par client, une page vraiment élaborée : j'entre dans un client et je navigue entre les différents chantiers. Pas juste un affichage : je veux pouvoir lancer un chantier directement, ou en sélectionner deux ou trois qui manquent de choses à faire et, en un clic, que ça bascule sur le terminal et lance l'action en une fois. Le but : arriver dans l'application et retrouver rapidement ce qui me reste à faire. »

## Mesure
13 chantiers ouverts pour 3 clôturés sur 7 jours (9 CAGIP, 4 EDENRED).

## Décisions PO (2026-10-27)
- **Où :** dans la **Forge**, sur la **page du client** (le poste). Le tableau devient l'onglet d'ouverture du client.
- **Ordre :** livré après F-218 et F-219.

## Proposition UX (à challenger et maquetter par l'agent)
- Tous les chantiers ouverts de **tous les sujets** du client, regroupés par ce qu'ils attendent :
  - **« À toi de jouer »** : une question, une validation de plan, une étape EXTERNE ;
  - **« Prêt à partir en autonome »** : préflight F-219 au vert ;
  - **« Bloqué par quelqu'un »** : attentes F-175 ;
  - **« En cours »** : un tour est vivant, ou le chantier est en autonome.
- **Ancienneté** visible sur chaque chantier (« dormant depuis 6 j »), mise en avant. C'est le remède à l'oubli.
- **Carte de chantier** :
  - sujet, titre, phase, anneau d'avancement (étapes faites / total) ;
  - prochaine étape ;
  - ce qui manque ;
  - dernier geste ;
  - coût engagé si disponible (F-133 / F-143).
- **Gestes :**
  - ouvrir le chantier dans son terminal, au focus ;
  - lancer en autonome ;
  - **multi-sélection**, puis « Lancer en autonome » sur plusieurs chantiers à la fois : chacun dans son sujet, dans la limite des 4 terminaux vivants (refus explicite au-delà), avec bascule vers la mosaïque (F-83) pour les voir travailler ;
  - clore, ou reporter.
- **Rappel des chantiers dormants :** à proposer (notification F-185 ou récapitulatif), sans spam.
- Mise à jour en direct si c'est simple (SSE `journey` existant, F-208).
- Responsive (téléphone) et `DESIGN_SYSTEM.md` obligatoires.

## Existant à réutiliser (vérifier avant de développer)
- Tableau des chantiers d'un sujet, en overlay (F-208) : modèle de plan, états, `depends_on`.
- Plusieurs chantiers par sujet et focus (F-206).
- Attentes (F-175) ; `sujets_etat` (F-178).
- Fil (F-191) ; Radar (F-99 à F-104) dans la Vigie : ne pas le dupliquer.
- Mosaïque (F-83) ; limite de 4 terminaux (F-70).
- Page client de la Forge refondue (F-98, F-68).

## Règles
- Isolation `user_id` sur toute agrégation.
- Pas de dashboard dupliquant le Radar : le Radar suit l'organisation (ce que les autres attendent) ; ce tableau suit **mes chantiers**.

# CADRAGE F-225 — Le point de situation d'un terminal

**Statut :** cadrage du 2026-10-31 (issu des audits du 31/10), à compléter par l'agent de livraison (découpage en SF)

**Demande PO (2026-10-31)** : « En revenant dans un terminal, je ne sais plus où on en était (avais-je envoyé le mail qu'il m'avait demandé ?). Je voudrais, en entrant ou via un bouton : le statut, le contexte, ce que j'ai fourni, ce qu'il a répondu, ce qui reste. »

## Existant (audit)
Tout est déjà calculé, mais éclaté sur 6 surfaces dont aucune ne s'ouvre à l'entrée :
- la bande des attentes (F-175) ;
- le tableau des chantiers (F-208) ;
- l'essentiel (F-126) ;
- les suites (F-192) ;
- « Marquer envoyé » (F-211, `message_block_sends`) ;
- les pièces jointes dans la bulle (F-169) ;
- la passation (F-117, seulement après compaction) ;
- `/sujet` ;
- `SubjectsStateService` (`sujets_etat`, F-178), offert à l'agent seul.

Le même schéma que « bilan calculé mais invisible ».

**Mesure** :
- environ 8 demandes explicites du PO depuis le 25/09 (« on en était où », « qu'est-ce qui reste ») ;
- 3,9 par jour la semaine du 28/10 ;
- 30 encarts mail sur 90 marqués envoyés seulement.

## Périmètre (aucun appel modèle)
1. `GET /api/workspaces/{id}/situation`, une composition déterministe de lectures existantes :
   - phase et étape courante de chaque chantier ouvert ;
   - attentes ouvertes (qui, à qui, depuis quand) ;
   - encarts mail ou message des 7 derniers jours, envoyés ou non, croisés avec l'attente liée ;
   - dernière demande du PO et essentiel de la dernière réponse ;
   - pièces fournies ;
   - suites proposées.

   Isolation `user_id`.
2. Écran : une carte « Point de situation », ouverte automatiquement à l'entrée si le terminal est inactif depuis plus de N heures (réglable ; défaut 4 h), et accessible par un bouton dans l'en-tête et par `/situation`. Un encart non marqué devient « Mail à X : pas marqué envoyé » avec [Marquer envoyé] et [Voir]. Respect de `DESIGN_SYSTEM.md` (fond sombre du terminal : contrastes AA), et sur téléphone.
3. **Hors périmètre** : un récit rédigé par le modèle (plus tard, à la demande, si la mesure le justifie).

# F-131 — Rejouer la dernière requête

> Cadrage du 2026-09-19, à la demande du PO, après des cas répétés où une réponse **vue à l'écran** n'a
> pas abouti (spinner bloqué / flux détaché / réponse écrasée). **Cadrage seul : livraison sur go** (donné).

## 1. Le besoin
> « Si l'utilisateur envoie un message et que, pour une raison ou une autre, la réponse n'est pas rendue,
> peut-on avoir un moyen de **rejouer la dernière requête** ? »

Aujourd'hui, quand le **flux SSE se détache** (l'utilisateur voit un spinner qui tourne indéfiniment alors
que le serveur a fini — cf. mémoire *le tour vit dans le flux*), ou quand une réponse **n'apparaît pas**,
l'utilisateur est coincé : il doit ré-écrire son message. On veut un **filet côté client** : rejouer la
dernière requête **d'un clic**.

## 2. Rapport aux correctifs serveur (complémentaire, pas redondant)
- **SF-125-07/08** (serveur) garantissent que **la bonne réponse est produite, conservée et persistée**.
- **F-131** (client) couvre le cas où **le transport a échoué** : le serveur a peut-être fini, mais l'écran
  n'a rien reçu (SSE tombé, onglet quitté, réseau). Le serveur ne peut pas « repousser » dans un flux
  mort ; l'utilisateur, lui, peut **rejouer**. Les deux se complètent.

## 3. Comportement attendu
1. **Détection « pas de réponse rendue »** : le flux se ferme **sans** événement final, **ou** un tour reste
   « en cours » au-delà d'un délai raisonnable sans rendu, **ou** la dernière réponse affichée est
   vide/incohérente → l'écran propose **« Rejouer la dernière requête »**.
2. **Toujours disponible manuellement** : un bouton **« Rejouer »** sur le dernier message utilisateur, même
   sans détection automatique (l'utilisateur juge). Simple, non intrusif.
3. **Le clic re-soumet le dernier message utilisateur** comme **nouveau tour** (via l'endpoint de chat
   existant). C'est un **choix explicite** : il consomme un tour (jetons) — assumé.
4. **Anti-doublon** : si un tour est réellement encore actif côté serveur (activité récente détectable),
   avertir avant de rejouer, pour ne pas lancer deux tours en parallèle.

## 4. Décisions (défauts proposés)
- **Rejouer = re-soumettre la dernière requête utilisateur** (nouveau tour), **pas** une reprise serveur du
  tour interrompu. Plus simple, fiable, et cohérent avec « le tour vit dans le flux » (un tour détaché est
  perdu ; on en relance un propre). *(Reprise serveur exacte = évolution éventuelle, hors périmètre.)*
- **Portée surtout frontend** : détection de non-rendu + bouton + re-POST du dernier message via l'endpoint
  existant. **Pas de nouveau backend a priori** (à confirmer en mini-spec).
- **Spinner honnête** : au passage, le spinner ne doit plus « tourner à l'infini » — s'il n'y a plus de flux
  ni de réponse, il bascule sur l'état « réponse non reçue — Rejouer ? » (corrige le vécu du PO).

## 5. Cas d'erreur / limites
- **Tour réellement encore en cours** → on ne rejoue pas en double : on l'indique et on laisse finir.
- **Message vide / pas de dernier message** → bouton inactif.
- Rejouer **ne restaure pas** la réponse perdue à l'identique (le tour détaché est parti) — il en **produit
  une nouvelle**. Dit clairement.

## 6. Portée & implémentation (esquisse)
- **Frontend (Atelier/terminal)** : détecter la fermeture du flux sans final / le tour trop long sans rendu ;
  afficher l'état « réponse non reçue » + bouton **Rejouer** ; le clic re-envoie le dernier message
  utilisateur au flux de chat existant. Réutilise la logique d'envoi existante.
- **Backend** : a priori inchangé (réutilise l'endpoint de stream). Éventuel petit contrôle « tour actif ? »
  pour l'anti-doublon (si un signal existe déjà).
- Aucune table, aucune migration.

## 7. Critères d'acceptation
- Un flux fermé sans réponse rendue → l'écran propose **Rejouer**, et le clic relance le dernier message
  (nouveau tour rendu normalement).
- Le spinner ne tourne plus indéfiniment : il bascule sur « réponse non reçue — Rejouer ? ».
- Rejouer pendant un tour réellement actif est **empêché/averti** (pas de double tour).
- Non-régression : l'envoi normal, le streaming, `<<essentiel>>` (F-126), SF-125-07/08 intacts.

## 8. Hors périmètre
- La **reprise serveur exacte** d'un tour interrompu (recomposer la réponse perdue) : évolution éventuelle.
- La cause racine du détachement du flux (réseau/onglet quitté) : on la **gère** (filet), on ne la supprime
  pas.

## 9. Préoccupations transversales
- **Navigation** : bouton intra-terminal, aucune route ajoutée.
- **Plans / limites** : un rejeu consomme un tour (quota) — c'est un geste explicite.
- **Composants** : composant terminal (détection non-rendu + bouton + re-envoi), logique d'envoi existante.

# Mini-spec — F-136 / SF-136-01 — Le magasin de carte

## Identifiant
`F-136 / SF-136-01` — feature parente `F-136`

## Objectif
Tenir, côté gateway, une **copie de travail** de la carte de chaque poste, rafraîchie **après** le
tour — pour qu'elle puisse servir au tour suivant sans coûter une seule seconde d'attente.

## Pourquoi un magasin, et pas une lecture à la demande
La carte vit sur la machine du client. La lire au **début** d'un tour coûterait six allers-retours
runner avant le premier mot du modèle — de l'ordre de deux secondes ajoutées à **chaque** demande,
pour un savoir qui ne change qu'en fin de tour. On la lit donc **après** la réponse, hors du chemin
critique : le tour en cours ne paie rien, et le suivant trouve tout prêt.

C'est aussi ce qui rend F-137 possible sans lecture supplémentaire.

## Comportement attendu
1. Après chaque tour sur un poste **en mémoire**, la carte est relue et rangée : un enregistrement
   par fichier, avec son titre, ses sections, son nombre de faits, son contenu et l'instant de
   lecture.
2. Le rafraîchissement est **asynchrone** : la réponse est déjà partie quand il démarre.
3. Il est **étranglé** : au plus un rafraîchissement par poste et par fenêtre courte, pour qu'une
   rafale de tours ne déclenche pas une rafale de lectures.
4. Une lecture qui échoue **ne détruit rien** : l'ancienne copie reste, et sert.

| Cas d'erreur | Comportement |
|---|---|
| Machine muette | le magasin garde l'état précédent ; aucune trace d'erreur à l'écran |
| Fichier illisible | les autres fichiers sont rangés quand même |
| Poste sans mémoire | rien n'est lu : il n'y a pas de carte |
| Poste « Hébergé » | rien n'est lu : pas de racine |

## Critères d'acceptation
- [ ] Après un tour, la carte du poste est en base : un enregistrement par fichier, avec titre, sections, faits, contenu.
- [ ] Le rafraîchissement **ne retarde pas** la réponse (asynchrone, vérifié par test).
- [ ] Deux tours rapprochés ne déclenchent **qu'un** rafraîchissement.
- [ ] Une machine muette **laisse la copie précédente intacte**.
- [ ] Un poste sans mémoire ne déclenche **aucun** appel machine.
- [ ] **Isolation** : la copie est rangée par `user_id` **et** `host_id` ; une lecture pour un autre poste ne rend rien.
- [ ] Un tour qui échoue ne laisse pas la copie à moitié écrite.

## Ce qui est stocké, et pourquoi c'est acceptable
Le **contenu** des fichiers de carte est stocké. La règle du paquet l'interdit déjà aux secrets
(*« jamais un mot de passe, une clé ou un jeton ; on note où le secret vit et qui l'accorde »*), et
le produit conserve déjà les conversations, bien plus bavardes. En échange, F-137 et F-139 pourront
travailler **sans jamais relire la machine**.

## Hors scope
L'injection dans le prompt (**SF-136-02**) · l'index par entité (**F-137**) · la péremption (**F-139**).

## Technique
| Élément | Changement |
|---|---|
| **migration `122-host-map-files.xml`** | table `host_map_files`, unique `(user_id, host_id, path)` |
| **`HostMapFile`** *(nouveau)* | l'entité |
| **`HostMapStore`** *(nouveau)* | rafraîchir, lire ; l'étranglement vit ici |
| **`HostMapRefreshExecutor`** *(nouveau)* | un pool dédié, petit, file bornée |
| `AtelierChatService` | déclenche le rafraîchissement **en fin de tour** |

## Plan de test
- [ ] Rafraîchissement : la carte lue est rangée, fichier par fichier.
- [ ] Étranglement : deux appels rapprochés ⇒ une seule lecture.
- [ ] Machine muette ⇒ copie précédente intacte.
- [ ] Poste sans mémoire / « Hébergé » ⇒ aucun appel.
- [ ] Isolation : deux comptes, deux postes homonymes.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| **Contexte tenant** | **oui** | Nouveau stockage porteur de données client : **toutes** les lectures et écritures filtrent `user_id` + `host_id`, et l'unicité en base porte sur ce couple. Le poste vient du tour (`workspace.getHostId()`), jamais d'un identifiant fourni par l'appelant. |
| Plans / limites | non | aucun quota, aucun gate touché |
| Navigation / routing | non | aucune route |

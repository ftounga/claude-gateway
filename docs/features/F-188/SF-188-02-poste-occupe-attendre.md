# Mini-spec — [F-188 / SF-188-02] Poste occupé : attendre au lieu de refuser

## Identifiant

`F-188 / SF-188-02`

## Feature parente

`F-188` — Les gros tours (cadrage `CADRAGE-F-188-gros-tours.md`, levier L2)

## Statut

`in-progress`

## Date de création

2026-10-11

## Branche Git

`feat/SF-188-02-poste-occupe-attendre`

---

## Objectif

Quand le poste exécute encore une commande précédente, la gateway attend qu'elle se termine (dans une borne), puis lance la commande demandée, au lieu de renvoyer à l'agent un refus qu'il relance en boucle.

---

## Constat (prod, vérifié le 2026-10-11)

Le 2026-10-04 (poste CAGIP), un `bash` long reçoit `runner_protocol_error` : la gateway a perdu la réponse, **mais la commande continue de tourner sur le poste**. Le runner n'exécute qu'une commande de premier plan à la fois. Il refuse donc la suivante avec `denied` / « Une commande est déjà en cours sur ce runner. » (`BashTool`). L'agent relance alors toutes les 3 s (20 refus, puis des oscillations). Sur 10 jours, 40 refus de ce type sont enregistrés dans `runner_audit`.

---

## Comportement attendu

### Cas nominal

1. Un résultat `bash` est **« poste occupé »** si :
   - `errorCode = denied`,
   - **et** son message est celui du runner (`BashTool.BUSY_MESSAGE`, « Une commande est déjà en cours sur ce runner. »).

   La détection marche avec les runners déjà installés, sans mise à jour. Un test de contrat garantit que le texte reste le même des deux côtés.
2. Sur « poste occupé », `callRunnerWithRetry` **attend puis réessaie la même commande** :
   - pas de 3 s (`app.atelier.runner-busy-poll-ms`) ;
   - borne totale de 60 s (`app.atelier.runner-busy-wait-ms`, `0` désactive l'attente) ;
   - toujours **dans le budget du tour**, et l'attente cède à une interruption du tour.
3. Pendant l'attente, l'utilisateur voit dans la sortie de l'étape une ligne : « (le poste termine une commande précédente — j'attends qu'il se libère) ».
4. Dès que le poste est libre, la commande s'exécute normalement, et l'agent reçoit **son vrai résultat**.
5. Toujours occupé à la borne : l'agent reçoit un message qui explique, à la place du refus brut :
   « Une commande précédente tourne encore sur le poste (sa réponse a pu être perdue). J'ai attendu 60 s sans qu'il se libère. Ne relance pas la même commande à l'identique : attends, vérifie son effet autrement (fichier produit, processus), ou lance les commandes longues avec run_in_background. »
6. **Pas de nouvelle demande d'autorisation** : l'autorisation déjà donnée pour cet appel vaut pour l'exécution différée (même appel, même commande).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Autre `denied` (commandes désactivées, `--no-bash`, autre message) | Inchangé : rendu tel quel, sans attente |
| Budget de tour insuffisant pour un pas | Pas d'attente ; message explicatif rendu |
| Tour interrompu pendant l'attente | L'attente cesse ; le résultat occupé est rendu |
| Outil autre que `bash` | Inchangé |

---

## Critères d'acceptation

- [ ] Un `bash` « occupé » puis libre au deuxième essai rend le résultat réel de la commande, sans refus vu par l'agent.
- [ ] Toujours occupé : un seul résultat rendu à l'agent, avec le message explicatif, après au plus la borne configurée.
- [ ] Un `denied` d'une autre nature n'attend pas.
- [ ] L'attente respecte le budget du tour et l'interruption.
- [ ] `0` désactive l'attente (coupe-circuit).
- [ ] Le texte « occupé » du runner et celui attendu par la gateway sont identiques (test de contrat).

---

## Plan de test

- Unitaires, `AtelierChatServiceRunnerBusyTest` :
  - occupé puis libre ;
  - toujours occupé, avec le message ;
  - autre `denied` ;
  - coupe-circuit à 0 ;
  - budget insuffisant.
- Contrat : `RunnerBusyContractTest`, avec `BashTool.BUSY_MESSAGE` égal au texte attendu par la gateway.
- Isolation : sans objet (même appel, même poste, même utilisateur).

---

## Composants impactés

- Backend :
  - `AtelierChatService` (`callRunnerWithRetry`, détection « occupé », message) ;
  - propriétés `app.atelier.runner-busy-wait-ms` et `app.atelier.runner-busy-poll-ms`.
- Runner : `BashTool.BUSY_MESSAGE` (constante publique, texte **inchangé**).
- Contrat : un test.

## Préoccupations transversales

Aucune. Le contrat runner reste inchangé (même code, même texte).

---

## Périmètre

### Hors scope (explicite)

- Retrouver la réponse perdue de la commande précédente (ce serait une reprise de l'appel côté runner).
- Code d'erreur dédié `busy` (changement de contrat, inutile ici).

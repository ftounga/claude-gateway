# Mini-spec — F-39 / SF-39-13 — La liste de tâches, visible pendant le travail

## Identifiant

`F-39 / SF-39-13`

## Feature parente

`F-39` — L'Atelier comme harnais

## Statut

`ready`

## Date de création

2026-09-06

## Branche Git

`feat/SF-39-13-liste-de-taches`

---

## Objectif

> Donner à l'agent un moyen de **poser son plan** et de le tenir à jour pendant un tour long, et à
> l'utilisateur le moyen de **voir où il en est** — sans lire la sortie des commandes pour le
> deviner.

---

## Déclencheur

Ouverture du **lot 7** du cadrage F-39 (décision D2, « au-delà de la parité »). L'usage réel mesuré
donne une médiane de 6 outils par demande mais une moyenne de **13,8**, avec des tours allant jusqu'à
**125 outils** : sur ces tours-là, ni l'agent ni l'utilisateur ne savent dire ce qui reste à faire.

Deux manques distincts, que le même outil comble :

- **Pour l'agent** : rien ne l'oblige à formuler un plan, donc rien ne l'empêche de dériver. Un plan
  écrit dans le contexte est un engagement qu'il relit à chaque itération.
- **Pour l'utilisateur** : la ligne vivante (SF-30-13) dit ce qui se passe **à l'instant**, jamais ce
  qui reste. Sur un tour de trente étapes, c'est la différence entre patienter et se demander si
  quelque chose est bloqué.

---

## Comportement attendu

### Cas nominal

1. L'agent dispose d'un outil `set_plan`, qui prend la **liste complète** des étapes avec leur état.
2. Il l'appelle quand il commence un travail à plusieurs étapes, puis **à chaque changement d'état**
   — une étape terminée, la suivante entamée.
3. Chaque appel **remplace** le plan précédent : il n'y a qu'un plan par tour, et c'est le dernier
   qui vaut. Aucune fusion, aucune réconciliation — deux vues d'un même plan ne peuvent pas diverger.
4. L'écran affiche le plan au-dessus de la sortie, et le met à jour au fil de l'eau
   (événement SSE `plan`).
5. Le plan est **persisté avec le tour** : au rechargement, un tour terminé montre encore ce qui
   avait été prévu et ce qui a été fait.

### États d'une étape

| État | Sens |
|---|---|
| `pending` | pas encore commencée |
| `active` | en cours — **une seule à la fois** |
| `done` | terminée |

Une liste comportant plusieurs `active` est **normalisée** : la première est conservée, les autres
repassent en `pending`. On ne refuse pas l'appel pour autant — un plan mal formé reste plus utile
qu'un refus qui ferait perdre le tour.

### Bornes

| Borne | Valeur | Pourquoi |
|---|---|---|
| Étapes par plan | 20 | Au-delà, ce n'est plus un plan mais une transcription ; et le plan est renvoyé à chaque itération |
| Longueur d'un titre | 200 caractères | Une ligne d'écran, tronquée au besoin |
| Plans par tour | illimité | C'est le geste de mise à jour ; seul le dernier est conservé |

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `steps` absent ou vide | Le plan est **effacé** ; résultat d'outil neutre, pas une erreur | 200 |
| Plus de 20 étapes | Les 20 premières sont conservées, l'agent en est informé dans le résultat d'outil | 200 |
| État inconnu (`todo`, `wip`…) | Ramené à `pending` ; l'appel aboutit | 200 |
| Plusieurs étapes `active` | Normalisé (la première l'emporte) ; l'appel aboutit | 200 |
| Titre trop long | Tronqué à 200 caractères | 200 |

**Aucun appel à `set_plan` ne fait échouer un tour.** Un outil d'organisation qui casse le travail
qu'il organise serait pire que son absence.

---

## Critères d'acceptation

- [ ] L'outil `set_plan` est exposé au modèle, sur les deux cibles d'exécution.
- [ ] Un appel remplace intégralement le plan du tour.
- [ ] Une liste vide efface le plan sans erreur.
- [ ] Les bornes (20 étapes, 200 caractères) sont appliquées, et le dépassement est **dit à l'agent**
      dans le résultat d'outil.
- [ ] Un état inconnu devient `pending` ; plusieurs `active` sont normalisés à une seule.
- [ ] Chaque mise à jour est relayée à l'écran par un événement SSE `plan`.
- [ ] Le plan est persisté avec le tour et réapparaît au rechargement.
- [ ] Aucun appel `set_plan` ne peut faire échouer le tour.
- [ ] Isolation `user_id` inchangée.
- [ ] Zéro régression : un tour sans `set_plan` se comporte exactement comme avant.

---

## Périmètre

### Hors scope

- **SF-39-14** — sous-agents (le reste du lot 7).
- L'édition du plan par l'utilisateur : c'est le plan de l'**agent**, pas une liste de courses
  partagée. Le modifier de l'extérieur poserait la question de qui tranche en cas de conflit.
- Toute persistance du plan **entre** deux tours : un plan est l'organisation d'un travail, pas un
  état du projet.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Valeurs | Normalisation |
|-------|-------------|-------------|---------|---------------|
| `steps` | Non | 20 entrées | tableau d'objets | au-delà : tronqué, agent informé |
| `steps[].title` | Oui | 200 | texte | `trim()`, tronqué ; entrée ignorée si vide |
| `steps[].status` | Non | — | `pending` \| `active` \| `done` | inconnu ⇒ `pending` ; un seul `active` |

---

## Technique

### Endpoint(s)

Aucun endpoint ajouté. Un événement SSE `plan` s'ajoute au flux existant `/chat/stream`.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `atelier_messages` | UPDATE du document `terminal_json` | **Colonne existante** (F-30 SF-30-09) : le plan y rejoint la transcription et la consommation. Aucune migration. |

### Migration Liquibase

- [x] **Non applicable** — le document de tour existe déjà et accueille un champ de plus.

### Classes impactées

| Classe | Changement |
|--------|-----------|
| `atelier/AtelierPlan` | **Nouveau** — le plan, ses états, sa normalisation et ses bornes |
| `atelier/AtelierChatService` | Outil `set_plan`, exécution, relais, persistance dans le tour |
| `atelier/AtelierProgressListener` | Événement `onPlan` |
| `atelier/AtelierChatController` | Émission SSE `plan` |

### Composants Angular

- `atelier-terminal` — affichage du plan au-dessus de la sortie, mise à jour au fil de l'eau.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | Aucun nouveau chemin d'accès ; le plan vit dans le tour, déjà filtré par `user_id` |
| **Plans / limites** | **Oui** | Un plan est renvoyé au modèle à chaque itération : il compte dans les tokens d'entrée, donc dans le quota et le plafond par message (SF-39-15). C'est ce que bornent les 20 étapes × 200 caractères — au plus ~4 000 caractères, négligeable devant les 200 000 tokens du seuil d'écartement de contexte (SF-39-12). Aucun appel aux services de limites n'est ajouté, retiré ni déplacé. |
| Navigation / routing | Non | — |

---

## Plan de test

### Tests unitaires

- [ ] `AtelierPlanTest` — normalisation : état inconnu ⇒ `pending` ; deux `active` ⇒ un seul ;
      titre tronqué ; entrée au titre vide ignorée ; plus de 20 étapes ⇒ 20 conservées.
- [ ] `AtelierChatServiceTest` — `set_plan` relaie un événement de plan au listener.
- [ ] `AtelierChatServiceTest` — deux appels : le second **remplace** le premier.
- [ ] `AtelierChatServiceTest` — liste vide ⇒ plan effacé, tour poursuivi.
- [ ] `AtelierChatServiceTest` — entrée malformée ⇒ le tour aboutit quand même.
- [ ] `AtelierChatServiceTest` — le plan est persisté avec le tour.
- [ ] `AtelierChatServiceTest` — non-régression : un tour sans `set_plan` est inchangé.

### Tests d'intégration

- [ ] `AtelierChatApiIntegrationTest` — le flux SSE porte un événement `plan` avant le `done`.

### Isolation workspace

- [x] Applicable — couverte par les tests existants ; aucun nouveau chemin d'accès aux données.

---

## Dépendances

- `SF-39-03` (mémoire de la trajectoire) — done · `SF-39-15` (plafond par message) — done

---

## Notes et décisions

**D1 — Remplacer, jamais fusionner.** L'outil prend la liste **complète**. Une API de mise à jour
partielle (« marque l'étape 3 terminée ») supposerait que les deux côtés s'accordent sur la
numérotation, et ferait diverger le plan de l'agent de celui de l'écran dès le premier décalage.
Remplacer coûte quelques tokens de plus et supprime toute une classe de bugs.

**D2 — Un plan mal formé ne fait jamais échouer le tour.** Normaliser plutôt que refuser : l'outil
sert à organiser un travail, et le casser parce que son plan est mal écrit serait exactement le
mauvais compromis. Le dépassement de borne est **dit à l'agent** dans le résultat d'outil, ce qui lui
permet de se corriger sans qu'on ait à l'interrompre.

**D3 — Le plan ne survit pas au tour.** Il organise un travail, il ne décrit pas un état du projet.
Le faire survivre poserait la question de sa péremption — un plan d'hier, à moitié fait, sur un
projet qui a changé — pour un bénéfice que la mémoire de la trajectoire (SF-39-03) couvre déjà.

**D4 — L'utilisateur ne modifie pas le plan.** C'est le plan de l'agent ; l'éditer de l'extérieur
rouvrirait la question de qui tranche en cas de conflit, et personne n'a demandé cette capacité.

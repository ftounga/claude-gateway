# F-123 — Lecture Teams auto-apprenante (zéro relevé manuel, sans API Microsoft ni accord client)

> Cadrage du 2026-09-16, à la demande du PO, en réponse à sa frustration : *« Je vais devoir faire des
> relevés Teams tout le temps ? Pour chaque action ? Comment tu n'es pas autonome dessus. »* et sa
> consigne : *« pas d'option nécessitant l'API Microsoft, un accord client. »*
> **Cadrage seul. Décision PO actée : 100 % observation de la session existante — ni Microsoft Graph,
> ni consentement admin, ni accord DSI.** Dépend de F-122 (Chrome managé par le runner).

## 0. Le problème qu'on supprime

Aujourd'hui, apprendre un endpoint Teams = un **relevé manuel** du PO (ouvrir Chrome en debug, dérouler
les étapes, envoyer le fichier) **puis** un recalage code par endpoint. C'est pénible et ça se répète à
chaque surface nouvelle (`schedulingService/meetings`, `events/{id}`, `readcollabobject`, puis
`calendarView`, puis conversations/messages…). Cause de fond : les endpoints internes de Teams sont
**non documentés** et le seul moyen d'en connaître la forme est de **l'observer** — décision produit
assumée (pas d'accord DSI). **F-123 garde ce modèle, mais retire l'humain de la boucle d'apprentissage.**

## 1. La cible : le runner apprend seul, sans Microsoft, sans DSI

- **Auto-capture** : le runner **pilote lui-même** le Chrome managé (F-122) pour exercer les surfaces
  clés (liste du calendrier, une réunion, un fil, fichiers) et **capte les squelettes** (noms + types,
  **jamais une valeur** — garde SF-89-12) — l'auto-relevé remplace le relevé manuel.
- **Adaptateur piloté par les données** : au lieu d'un mapping figé dans le code par endpoint, un
  **mapping résilient** (chemin d'URL → genre ; champs → sens par **table d'alias** — `subject`,
  `startTime/endTime`, `id/iCalUid/objectId`… — et heuristiques de forme) appliqué **à l'exécution**.
  Un endpoint nouveau mais **de même famille** (ex. `calendarView` vs `events/{id}`) est résolu **sans
  recompilation**.
- **La sonde de santé garde tout** : un mapping appris n'est utilisé que s'il **valide** (champs
  attendus présents) ; sinon **échec visible** (SF-89-11), jamais d'invention.
- **Déclenchement automatique** : à l'onboarding (F-122) **et** sur **dérive détectée** par la sonde
  (« Microsoft a changé la forme ») — plus jamais un relevé demandé au PO pour l'usage courant.

## 2. Ce que ça retire au PO / au dev

| Avant | Après F-123 |
|---|---|
| Le PO lance un relevé manuel par surface | Le runner capte seul à l'onboarding |
| Un recalage code + déploiement par endpoint | Les endpoints d'une **famille connue** sont absorbés sans code |
| On découvre les changements Microsoft par l'échec | La sonde les détecte et **relance l'apprentissage** seule |

## 3. Honnêteté sur les limites (et comment on les tient)

- Un adaptateur **générique** peut mal lire. **Garde-fous** : tables d'alias par genre (on ne devine pas
  à l'aveugle), validation par la sonde **avant** usage, et **échec visible** (SF-89-11) si un mapping
  appris ne rend rien — jamais une valeur inventée.
- Une **famille vraiment nouvelle** (genre jamais vu, ex. un futur objet Teams sans équivalent) peut
  encore demander un ajout de code — mais c'est rare, et la capture reste **automatique** (le squelette
  arrive tout seul, le dev n'a plus qu'à nommer le genre). **Plus jamais** de relevé manuel côté PO.
- Vie privée : l'**apprentissage** ne retient que la **structure** (noms/types) ; la lecture normale lit
  les valeurs **pour servir l'agent**, comme aujourd'hui, sans les stocker.

## 4. Découpage

| SF | Titre | Contenu |
|---|---|---|
| **SF-123-01** | **Auto-capture des formes par le runner** | Le runner, via le Chrome managé (F-122), exerce les surfaces clés et capte les squelettes (noms+types, zéro valeur, bornes de SF-89-14) — sans commande utilisateur. Réutilise le moteur du relevé « forme ». |
| **SF-123-02** | **Adaptateur Teams piloté par les données** | Remplace les mappings figés par un mapping résilient (alias + heuristiques) appliqué à l'exécution, validé par la sonde ; absorbe les endpoints d'une famille connue (à commencer par `calendarView` = famille CALENDAR_EVENT) sans recompilation. |
| **SF-123-03** | **Réapprentissage automatique sur dérive** | La sonde de santé qui passe sous un seuil déclenche une nouvelle auto-capture + revalidation, sans geste ; journalisé, avec échec visible si l'apprentissage échoue. |
| **SF-123-04** | **Repli & garde-fous** | Un genre vraiment inconnu → squelette remonté automatiquement + échec visible (SF-89-11) ; jamais d'invention ; le dev n'a plus qu'à nommer le genre le cas échéant. |

**Ordre** : SF-123-01 (capture) → SF-123-02 (adaptateur data-driven) → SF-123-03 (réapprentissage) →
SF-123-04 (repli).

## 5. Rapport avec le reste
- **Dépend de F-122** (Chrome managé piloté par le runner) — sans lui, pas d'auto-capture.
- **Remplace à terme** le besoin de SF-89-14/15 côté PO : ces deux-là restent la voie **manuelle**
  transitoire ; F-123 en fait la version **automatique**. On peut livrer SF-89-14/15 d'abord (débloque
  `calendarView` vite), puis F-123 pour supprimer le manuel.
- **Aucune dépendance Microsoft Graph, aucun consentement admin, aucun accord DSI.**

## 6. Hors périmètre
- Toute voie par API documentée Microsoft (Graph) ou consentement admin : **exclue par le PO**.
- Écrire dans Teams par apprentissage (F-108 couvre les écritures, confirmées) : hors sujet.

## 7. Préoccupations transversales
- **Auth / tenant** : capture et mapping **par utilisateur/poste** ; isolation stricte, aucune forme
  partagée entre clients (une forme apprise sur un tenant ne s'applique pas d'office à un autre — la
  sonde revalide).
- **Composants** : runner (`NetworkSurvey`/`--forme`, `TeamsAdapterV1` → adaptateur data-driven,
  `NetworkObserver`, pilotage Chrome F-122), sonde de santé, catalogue Teams.

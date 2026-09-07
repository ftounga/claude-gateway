# Mini-spec — F-38 / SF-38-24 — Une panne réseau qui se décrit

## Identifiant

`F-38 / SF-38-24`

## Feature parente

`F-38` — Exécution sur machine connectée (runner local)

## Statut

`ready`

## Date de création

2026-09-07

## Branche Git

`feat/SF-38-24-panne-reseau-qui-se-decrit`

---

## Objectif

> Qu'aucune panne réseau du runner ne se raconte par `null`.

---

## Déclencheur

Troisième obstacle du même client, après le prérequis Java et le chemin avalé par le shell :

```
[19:32:52] ERREUR  Appel d'appairage impossible (https://portal.ng-itconsulting.com/api/runner/pair) : null
```

`e.getMessage()` valait `null` — certaines `IOException` n'en portent pas — et le runner n'affichait
ni le **type** de l'exception, ni sa **cause**. Le message ne permet donc pas de distinguer :

- un proxy d'entreprise obligatoire,
- une interception TLS (certificat non reconnu par la JVM),
- une résolution DNS en échec,
- un simple délai dépassé.

Quatre situations, quatre gestes différents — et un unique `null` pour les départager.

Le même motif (`e.getMessage()` seul) était présent en **six** points du chemin réseau : appairage,
connexion WebSocket, erreur de connexion, long-polling, émission de trame.

---

## Comportement attendu

### Cas nominal

Toute panne réseau se décrit par une chaîne **jamais vide** : le type de l'exception, son message
s'il en a un, et la chaîne des causes.

```
Appel d'appairage impossible (https://…/runner/pair) : IOException, causé par :
ConnectException: Connection timed out
```

Quand le type reconnu appelle un geste précis, une piste courte suit :

| Type rencontré | Piste ajoutée |
|---|---|
| Échec de poignée de main TLS | « certificat non reconnu par Java — proxy interceptant ? `-Djavax.net.ssl.trustStore` » |
| Hôte introuvable | « nom non résolu — DNS ou proxy d'entreprise ? » |
| Connexion refusée / expirée | « sortie réseau bloquée ? Vérifiez `HTTPS_PROXY` / `HTTP_PROXY` » |

### Ce qui ne change pas

Le runner **échoue toujours**, avec les mêmes codes de sortie. Seul le texte change.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Exception sans message et sans cause | Le **type seul** est affiché — jamais `null`, jamais une chaîne vide |
| Chaîne de causes profonde | Bornée à 3 niveaux : au-delà, on répète du bruit |
| Cause cyclique | La description se termine — pas de boucle infinie |

---

## Critères d'acceptation

- [ ] Une exception **sans message** produit une description non vide contenant son type.
- [ ] Une exception avec cause affiche la cause.
- [ ] La chaîne est bornée à 3 niveaux.
- [ ] Une cause cyclique ne fait pas boucler la description.
- [ ] Les six points du chemin réseau utilisent la description.
- [ ] Les pistes TLS / DNS / connexion s'affichent sur le type correspondant, et **sur lui seul**.
- [ ] Les codes de sortie et le fait d'échouer sont **inchangés**.

---

## Périmètre

### Hors scope

- Rendre le runner tolérant à ces pannes (proxy automatique, truststore système) : c'est un autre
  sujet, et il demande des décisions de sécurité.
- Les messages qui ne concernent pas le réseau.

---

## Technique

| Classe | Changement |
|--------|-----------|
| `runner/Failures` | **Nouvelle** — description non vide d'une exception, et piste éventuelle |
| `PairingClient`, `RunnerConnection`, `PollingConnection`, `FrameSender` | Utilisation |

### Migration Liquibase

- [x] **Non applicable.**

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | — |
| Navigation / routing | Non | — |
| **Confidentialité des journaux** | **Oui** | La description est écrite dans la **console de l'utilisateur**, sur sa machine. Elle ne contient que des types d'exception et des messages de la JVM — jamais le jeton d'appairage, jamais le contenu d'un fichier. À vérifier : `PairingException` continue de ne pas porter le code d'appairage. |

---

## Plan de test

### Tests unitaires

- [ ] Exception sans message → description contenant le type.
- [ ] Exception avec message → les deux.
- [ ] Cause présente → cause affichée.
- [ ] Profondeur bornée à 3.
- [ ] Cycle → terminaison.
- [ ] Piste TLS sur un échec de poignée de main, absente sur une autre exception.
- [ ] Piste DNS sur un hôte introuvable.

### Isolation workspace

- [x] Non applicable.

---

## Notes et décisions

**D1 — Décrire, pas diagnostiquer.** Le runner ne sait pas si le proxy est obligatoire ; il sait
quelle exception il a reçue. La piste est formulée comme une **question**, jamais comme une
conclusion : affirmer « votre proxy bloque » alors que le câble est débranché ferait perdre plus de
temps que `null`.

**D2 — Le type d'abord.** Un `ConnectException` sans message dit déjà l'essentiel à qui connaît
Java, et donne à chercher à qui ne le connaît pas. C'est précisément ce que `getMessage()` seul
supprimait.

**D3 — Borner la chaîne des causes.** Trois niveaux suffisent à atteindre la cause racine dans les
piles réseau de la JVM ; au-delà, on ajoute du bruit à un message que quelqu'un doit lire dans une
console.

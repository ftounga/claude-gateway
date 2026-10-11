# Mini-spec — [F-190 / SF-190-06] Les téléchargements du runner derrière Zscaler

## Identifiant

`F-190 / SF-190-06` (correctif)

## Feature parente

`F-190` — Regarder une vidéo

## Statut

`in-progress`

## Date de création

2026-10-11

## Branche Git

`fix/SF-190-06-telechargements-derriere-zscaler`

---

## Objectif

Le rapatriement de `ffmpeg` (et de whisper et de son modèle) passe par le même client réseau que la connexion à la gateway. Il hérite ainsi du proxy d'entreprise et de la racine Zscaler, et fonctionne sur un poste d'entreprise.

---

## Constat (prod, poste EDENRED, 2026-10-11 12:16)

- Runner à jour (capacité `video` annoncée) ; `video_images` est appelé, puis le travail échoue : « Le rapatriement de « ffmpeg » n'a pas abouti ((certificate_unknown) PKIX path building failed…) ».
- Le poste est derrière **Zscaler**, mais la racine d'inspection est dans le magasin système, puisque `curl` vers internet fonctionne.
- Le runner lit ce magasin depuis F-80 (`TrustStores`) et l'applique au client construit par `RunnerMain`, que son commentaire présente comme « un seul client … pour TOUT le trafic du runner ».
- C'était faux : `LocalToolchain.httpDownloader` et **cinq remontées** de `ToolStack` (images de moments, audio de réunion, images de réunion, audio de capture, Radar) créaient chacun un `HttpClient` nu, **sans proxy ni racine d'entreprise**.

---

## Comportement attendu

### Cas nominal

1. `RunnerHttp` détient le client du runner (proxy et truststore), posé par `RunnerMain` dès sa construction.
2. `LocalToolchain.httpDownloader` et les cinq remontées l'utilisent.
3. Avant qu'il soit posé (tests, usages hors `RunnerMain`), un client simple est rendu : c'est le comportement d'avant.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `--no-system-trust` | Le client du runner est celui de la JDK seule, comme pour la gateway (choix explicite de l'utilisateur) |
| Téléchargement toujours refusé | Message et remède inchangés (installer `ffmpeg` soi-même) |

---

## Critères d'acceptation

- [ ] Le rapatriement passe par le client du runner (prouvé par un proxy de test qui reçoit le `CONNECT`).
- [ ] Plus aucun `HttpClient` nu dans le code du runner, hors boucle locale (Chrome piloté en 127.0.0.1) et construction du client unique : une garde de test l'impose.
- [ ] Sur le poste EDENRED, après mise à jour du runner, `video_images` rapatrie `ffmpeg` et extrait les images (vérification du PO).

---

## Plan de test

- `RunnerHttpTest` : repli, puis client posé ; passage par le proxy ; garde « aucun client nu ».
- Suite runner complète.
- Isolation : sans objet.

---

## Composants impactés

- Runner :
  - `RunnerHttp` (nouveau) ;
  - `RunnerMain` (pose du client) ;
  - `ToolStack` (cinq remontées) ;
  - `teams/LocalToolchain` (rapatriement).

## Préoccupations transversales

Aucune côté application. Réseau du runner : **comportement aligné** sur la connexion gateway (F-44 et F-80), rien de plus permissif.

---

## Périmètre

### Hors scope (explicite)

- Toute confiance supplémentaire (keystore utilisateur, désactivation de la vérification) : non, on réutilise exactement la confiance déjà accordée à la gateway.

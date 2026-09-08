# Mini-spec — F-45 / SF-45-03 — Fiche « Pour votre DSI »

## Identifiant

`F-45 / SF-45-03`

## Feature parente

`F-45` — Mise en service guidée du runner sur poste d'entreprise

## Statut

`done` — PR #293, mergée le 2026-09-08

## Date de création

2026-09-08

## Branche Git

`feat/SF-45-03-fiche-pour-votre-dsi`

---

## Objectif

> Que l'écran **génère la demande** que le client doit transmettre à sa DSI — domaine, port, flux,
> sens, et les deux points sur lesquels un proxy d'entreprise fait échouer le runner.

---

## Déclencheur

Chez le client du 2026-09-07, la moitié des trois heures s'est passée à **reconstituer la demande** :
quel domaine, quel port, dans quel sens, et pourquoi « HTTPS est déjà ouvert » ne suffisait pas. Ces
informations sont **connues du produit** — l'écran connaît son propre domaine, et le comportement du
runner face à un proxy est documenté depuis SF-38-24/25. Les faire retrouver à un utilisateur, c'est
lui faire deviner ce qu'on sait.

Le quatrième obstacle, le `407`, est précisément celui qu'aucun utilisateur ne peut résoudre seul :
il **doit** passer par sa DSI. Sans une demande écrite, cet aller-retour se fait à l'oral, incomplet.

---

## Comportement attendu

### Cas nominal

L'étape « Vérifier l'accès réseau » porte un bloc **« Pour votre DSI »** avec deux actions :
**copier** la fiche, et la **télécharger** (`runner-acces-reseau-dsi.txt`).

La fiche est du **texte brut**, générée depuis l'origine de la page, et contient :

| Rubrique | Contenu |
|---|---|
| Sens | **Sortant uniquement** — aucun port entrant, aucune règle de NAT, aucune exposition du poste |
| Domaine | l'hôte de la page consultée |
| Port | le port de la page (`443` par défaut en HTTPS) |
| Protocoles | **HTTPS et WSS** — le WebSocket emprunte le **même** hôte et le **même** port, par bascule `Upgrade` |
| Repli | un proxy qui laisse passer HTTPS mais refuse l'`Upgrade` fait basculer le runner sur un repli en long-polling HTTPS, plus lent mais fonctionnel (SF-38-09) |
| Proxy | le runner lit `HTTPS_PROXY` / `HTTP_PROXY` / `NO_PROXY` |
| Limite JVM | l'authentification proxy **intégrée** (NTLM, Kerberos) **n'est pas portée** : aucun support SSPI, `Basic` désactivé sur les tunnels `CONNECT` depuis Java 8u111 |
| Deux issues | exclure ce domaine de l'authentification proxy, **ou** laisser l'utilisateur passer par un relais local (`px`, `cntlm`) |
| Interception TLS | si le proxy déchiffre le TLS, l'autorité interne doit être connue de la JVM (`-Djavax.net.ssl.trustStore`) |
| Ce qui n'est pas demandé | aucun droit administrateur, aucun service installé, exécution sous le compte de l'utilisateur, arrêt au `Ctrl-C` |
| Traçabilité | date de génération et origine d'où la fiche a été produite |

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Page servie en `http://` (développement local) | La fiche annonce `HTTP`/`WS` et le port réel, plutôt que de mentir avec `443` |
| Origine avec port explicite (`:8080`) | Le port de la fiche est celui de l'origine |
| Origine illisible | La fiche reste générée avec une mention explicite plutôt que de disparaître : une fiche imparfaite vaut mieux qu'un bouton mort |
| Presse-papiers indisponible | Message « sélectionnez le texte manuellement » (comportement existant) |
| Téléchargement bloqué par le navigateur | La copie reste disponible — les deux actions sont indépendantes |

---

## Critères d'acceptation

- [ ] Le bloc « Pour votre DSI » est présent dans l'étape « Vérifier l'accès réseau ».
- [ ] La fiche nomme le **domaine** de la page consultée.
- [ ] La fiche nomme le **port** de la page consultée (`443` quand il est implicite en HTTPS).
- [ ] La fiche nomme **HTTPS et WSS**, et dit qu'ils partagent le même hôte et le même port.
- [ ] La fiche dit **sortant uniquement** et **aucun port entrant**.
- [ ] La fiche dit que l'authentification proxy **NTLM/Kerberos n'est pas portée par la JVM**.
- [ ] La fiche propose les **deux** issues : exclusion du domaine, ou relais local.
- [ ] La fiche mentionne le **repli long-polling** si l'`Upgrade` WebSocket est refusé.
- [ ] La fiche mentionne l'**interception TLS** et le truststore.
- [ ] La fiche dit qu'**aucun droit administrateur** n'est requis.
- [ ] Une origine en `http://` produit `HTTP`/`WS`, jamais `HTTPS`/`WSS`.
- [ ] La fiche est **copiable** et **téléchargeable** sous `runner-acces-reseau-dsi.txt`.
- [ ] La fiche ne contient **aucun** code d'appairage, aucun jeton, aucun identifiant utilisateur.

---

## Périmètre

### Hors scope (explicite)

- **Envoyer la fiche** (courriel, ticket) : l'écran produit le texte, l'utilisateur choisit son canal.
- **Un endpoint backend qui génère la fiche** : tout son contenu est déjà connu du navigateur
  (l'origine) ou constant (le comportement du runner). Un aller-retour n'apporterait rien et créerait
  une route à maintenir.
- **Un PDF ou un document mis en forme** : une DSI copie-colle dans un ticket ; le texte brut est le
  format qui survit à tous les outils.
- **Adapter la fiche à la marque du proxy** (Zscaler, Netskope, BlueCoat…) : on décrit ce que le
  runner fait, pas ce que chaque produit tiers exige.

---

## Valeurs initiales

Sans objet — aucune entité, aucune persistance. La fiche est recalculée à chaque affichage.

---

## Contraintes de validation

Sans objet — la fiche n'accepte aucune saisie. Son unique entrée est l'origine de la page, lue et
non fournie par l'utilisateur.

---

## Technique

### Endpoint(s)

**Aucun** — ni créé, ni consommé.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] **Non applicable.**

### Composants Angular

- `RunnerPairingDialogComponent` — `itDepartmentSheet()` (fonction pure exportée, testable seule),
  `downloadItSheet()`.
- `runner-pairing-dialog.component.html` — le bloc et ses deux actions.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | Aucun appel |
| Contexte tenant | Non | Aucun accès données ; la fiche ne contient **ni** identifiant de projet, **ni** identifiant d'utilisateur |
| Plans / limites | Non | — |
| Navigation / routing | Non | — |
| **Divulgation** | **Oui, vérifiée** | La fiche est destinée à sortir de l'écran (copiée dans un ticket). Elle ne doit donc contenir que ce qui est **déjà public** : le domaine de la passerelle et le comportement du runner. Composants vérifiés : `pairingCode` (jamais lu par la fiche), `data.workspaceId` / `workspaceName` (jamais lus), `workspacePath` (jamais lu — c'est l'arborescence du poste). Test dédié. |

---

## Plan de test

### Tests unitaires

- [ ] La fiche contient l'hôte de l'origine passée.
- [ ] Origine `https://exemple.fr` → port `443`.
- [ ] Origine `https://exemple.fr:8443` → port `8443`.
- [ ] Origine `http://localhost:4200` → `HTTP`/`WS` et port `4200`, jamais `HTTPS`/`WSS`.
- [ ] La fiche contient « sortant » et « aucun port entrant ».
- [ ] La fiche contient `NTLM`, `Kerberos`, `SSPI` et `8u111`.
- [ ] La fiche contient les deux issues (exclusion, relais local).
- [ ] La fiche mentionne le repli long-polling et l'interception TLS.
- [ ] La fiche dit qu'aucun droit administrateur n'est requis.
- [ ] Une origine illisible produit tout de même une fiche non vide.
- [ ] La fiche ne contient **ni** le code d'appairage, **ni** le nom du projet, **ni** le chemin saisi.
- [ ] `downloadItSheet()` enregistre un fichier nommé `runner-acces-reseau-dsi.txt`.
- [ ] Le bloc « Pour votre DSI » est rendu dans le gabarit.

### Tests d'intégration

Sans objet — aucune route.

### Isolation workspace

- [x] **Non applicable** — aucun accès aux données. Le test de non-divulgation ci-dessus couvre le
      risque réel de cette subfeature.

---

## Dépendances

### Subfeatures bloquantes

- `SF-45-01` — **done** (le bloc s'insère dans l'étape qu'elle a créée)
- `SF-38-09` (repli long-polling) — **done**, cité par la fiche

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

**D1 — La fiche est générée côté écran, pas côté gateway.** Tout ce qu'elle contient est déjà connu
du navigateur (l'origine) ou constant (le comportement du runner). Un endpoint n'ajouterait qu'une
route à maintenir et un aller-retour à échouer. *Alternative écartée* : `GET /runner/network-facts`.

**D2 — Du texte brut, pas un PDF.** Une DSI colle ces lignes dans un ticket. Le texte brut passe
partout ; un PDF demande une pièce jointe et se relit mal dans un fil.

**D3 — Le port vient de l'origine, jamais d'une constante.** Écrire `443` en dur serait faux en
développement et faux le jour où la passerelle est servie ailleurs. La fiche dit ce que **cette**
page voit.

**D4 — HTTPS *et* WSS sont nommés séparément, même s'ils partagent le port.** C'est le point que le
client a dû expliquer deux fois : un pare-feu applicatif peut autoriser HTTPS et refuser l'`Upgrade`
WebSocket. Le repli long-polling est mentionné pour que la DSI sache que le refus n'est pas fatal —
seulement coûteux.

**D5 — La limite NTLM/Kerberos est écrite comme une contrainte du produit, pas comme un bogue.** Elle
vient de la JVM, elle ne sera pas corrigée par une version du runner, et la DSI est la seule à pouvoir
la lever. Deux issues sont proposées plutôt qu'une seule exigence : une exclusion de domaine se
refuse, un relais local se tolère.

**D6 — Aucune donnée du projet dans la fiche.** Ni code, ni nom de projet, ni chemin : la fiche est
faite pour sortir de l'écran. Un test l'affirme explicitement, parce que c'est le genre de fuite qui
s'ajoute par inadvertance à la première évolution.

# Mini-spec — F-38 / SF-38-25 — Contrôle de vol réseau au démarrage

## Identifiant

`F-38 / SF-38-25`

## Feature parente

`F-38` — Exécution sur machine connectée (runner local)

## Statut

`ready`

## Date de création

2026-09-07

## Branche Git

`feat/SF-38-25-controle-de-vol-reseau`

---

## Objectif

> Que le runner vérifie qu'il **atteint la gateway** avant de tenter quoi que ce soit, et dise
> comment configurer un proxy **sur le système où il tourne**.

---

## Déclencheur

Trois obstacles en une heure chez le même client, tous avant la première connexion. Le dernier :

```
[19:32:52] INFO  Appairage auprès de https://portal.…/runner/pair…
[19:32:52] ERREUR  Appel d'appairage impossible (…) : null
```

SF-38-24 a rendu ce message lisible. Il reste que l'échec survient **au milieu d'une opération
métier** — l'appairage — alors que la cause n'a rien de métier : le poste ne sort pas sur Internet.
Diagnostic réel : DNS qui ne résout pas les noms publics, aucun proxy dans l'environnement, alors
que le **navigateur du même poste atteint l'application**.

Demande du product owner, 2026-09-07 : *« qu'il dise même comment configurer en fonction du système
d'exploitation »*.

---

## Comportement attendu

### Cas nominal

Au démarrage, **avant l'appairage et avant toute connexion**, le runner joint la gateway. Si elle
répond — **quel que soit le code HTTP** — le contrôle est passé et rien ne s'affiche de plus qu'une
ligne :

```
[19:32:51] INFO  Réseau    : gateway joignable
```

### Gateway injoignable

Le runner s'arrête **avant** l'appairage, avec un message en trois parties : ce qui a échoué, la
piste, et les gestes **du système courant**.

```
La gateway n'est pas joignable : UnknownHostException: portal.ng-itconsulting.com
Nom d'hôte non résolu — DNS, ou proxy d'entreprise obligatoire ?

Votre navigateur atteint peut-être cette adresse alors que ce terminal ne le peut pas :
c'est le cas quand un proxy d'entreprise est configuré côté Windows mais absent de ce shell.

Sur Windows — retrouver le proxy :
  netsh winhttp show proxy
  reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyServer
  reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v AutoConfigURL
Puis, dans ce terminal :
  set HTTPS_PROXY=http://hote:port        (invite de commandes)
  $env:HTTPS_PROXY="http://hote:port"     (PowerShell)
  export HTTPS_PROXY=http://hote:port     (Git Bash)

Si AutoConfigURL renvoie un fichier .pac, ouvrez-le : l'adresse du proxy y est écrite en clair.
```

Sur macOS, ce sont `scutil --proxy` et `export` ; sur Linux, `env | grep -i proxy` et `export`.

### Bornes

| Borne | Valeur | Pourquoi |
|---|---|---|
| Délai du contrôle | 10 s | Au-delà, on fait attendre quelqu'un devant un terminal muet |
| Ce qui vaut « joignable » | **toute** réponse HTTP | On teste la **joignabilité**, pas la santé : un 404 prouve qu'on a parlé au serveur |

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Gateway répond 404 / 401 / 500 | **Contrôle passé** — le réseau fonctionne, le reste est un autre sujet |
| Délai dépassé | Échec, avec les gestes du système |
| Nom non résolu, connexion refusée, TLS refusé | Échec, avec la piste correspondante (SF-38-24) |
| Système d'exploitation non reconnu | Les gestes **génériques** (variables d'environnement) sont affichés — jamais rien |

---

## Critères d'acceptation

- [ ] Le contrôle a lieu **avant** l'appairage et avant toute connexion.
- [ ] Toute réponse HTTP vaut « joignable », y compris 404 et 500.
- [ ] Un échec arrête le runner **avant** l'appairage, avec un code de sortie non nul.
- [ ] Le message nomme la cause (réutilise `Failures`) puis les gestes du **système courant**.
- [ ] Windows, macOS et Linux ont chacun leurs commandes ; un système inconnu reçoit les génériques.
- [ ] Le message dit que le **navigateur peut réussir là où le terminal échoue** — c'est le fait qui
      débloque la compréhension.
- [ ] Un runner qui atteint la gateway ne subit **aucun retard perceptible** au-delà du contrôle.
- [ ] Le contrôle n'est **pas** un test de santé : il ne juge pas la réponse.

---

## Périmètre

### Hors scope

- Découvrir le proxy automatiquement (lecture du registre, PAC) : ce serait exécuter la
  configuration réseau du poste, avec les décisions de sécurité que cela suppose. Ici, on **dit où
  regarder**.
- Configurer quoi que ce soit à la place de l'utilisateur.
- Le truststore d'entreprise (déjà nommé par la piste TLS de SF-38-24).

---

## Technique

| Classe | Changement |
|--------|-----------|
| `runner/NetworkPreflight` | **Nouvelle** — le contrôle et son message |
| `runner/OperatingSystem` | **Nouvelle** — détection et gestes par système |
| `runner/RunnerMain` | Appel du contrôle avant l'appairage |

### Migration Liquibase

- [x] **Non applicable.**

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | Le contrôle n'envoie aucun jeton — il n'en a pas encore |
| Contexte tenant | Non | — |
| Plans / limites | Non | — |
| Navigation / routing | Non | — |
| **Chemin de démarrage** | **Oui** | Un contrôle ajouté au démarrage est un point de blocage de plus. Il est **borné à 10 s**, ne juge pas la réponse, et ne s'interpose pas sur un runner qui reconnecte : composants à vérifier — démarrage avec appairage, démarrage avec jeton déjà stocké, repli long-polling. |

---

## Plan de test

### Tests unitaires

- [ ] Une réponse 200 vaut joignable.
- [ ] Une réponse 404 vaut joignable — le contrôle ne juge pas la santé.
- [ ] Une exception de transport vaut injoignable.
- [ ] Le message contient la description de la cause.
- [ ] Windows, macOS et Linux produisent chacun leurs commandes.
- [ ] Un système inconnu produit les gestes génériques, jamais un message vide.
- [ ] Le message mentionne le navigateur qui réussit là où le terminal échoue.

### Isolation workspace

- [x] Non applicable.

---

## Notes et décisions

**D1 — Toute réponse vaut joignable.** Le contrôle répond à une seule question : « ce terminal
sort-il jusqu'à cette adresse ? ». Juger le code HTTP en ferait un test de santé, qui bloquerait un
runner parfaitement fonctionnel le jour où un endpoint change.

**D2 — Dire où regarder, ne pas aller chercher.** Lire le registre Windows ou interpréter un fichier
PAC reviendrait à exécuter la configuration réseau du poste. Le runner dit les commandes ;
l'utilisateur décide.

**D3 — Nommer le paradoxe du navigateur.** « Mon navigateur y arrive » est la première objection, et
c'est justement l'indice : le proxy est configuré côté système, pas dans le shell. Le dire
explicitement fait gagner l'aller-retour que ce diagnostic a coûté.

**D4 — Échouer tôt plutôt que tard.** Le même échec survenait au milieu de l'appairage, mêlant une
cause réseau à une opération métier. Le contrôle le sort de là : ce qui n'a rien à voir avec
l'appairage ne doit pas échouer pendant l'appairage.

# Mini-spec — F-82 / SF-82-03 — Le repli de transport se nomme

## Identifiant

`F-82 / SF-82-03`

## Feature parente

`F-82` — Arrêter proprement, et savoir quand on n'y arrive pas

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-82-03-repli-transport-se-nomme`

---

## 0. Instruction préalable — CONCLUSION AVANT TOUT CORRECTIF

> Le cadrage l'exige : « le repli a-t-il été tenté et a-t-il échoué en silence, ou n'a-t-il jamais
> été tenté ? Tant qu'on ne sait pas lequel des deux, aucun correctif ne peut être écrit. »
> Cette section a été écrite **avant** la moindre ligne de correctif. Elle est la mini-spec.

### 0.1 — Ce qui a été lu

`TransportFallbackPolicy`, `RunnerConnection.run()`, `PollingConnection.run()/loop()`,
`RunnerMain.runSession()`, `HttpPollingClient`, et côté gateway `RunnerPollController` +
`RunnerSecurityConfig`.

### 0.2 — Verdict : **le repli n'est pas cassé. Le silence, lui, l'est.**

Le mécanisme est **présent, câblé et atteignable**, de bout en bout :

1. `TransportFallbackPolicy` compte les échecs (`recordTransportFailure`) **et** les sessions trop
   courtes (`recordSessionEnded` sous `SHORT_SESSION` = 5 s — la signature d'un proxy qui accepte
   l'`Upgrade` puis coupe). À `FAILURES_BEFORE_FALLBACK` = 2, `shouldFallBack()` passe à vrai.
2. `RunnerConnection.run()` lit ce verdict à **chaque** tour de boucle, pose `fellBackToPolling` et
   sort.
3. `RunnerMain.runSession()` lit `connection.fellBackToPolling()` et monte la `PollingConnection`.
4. Les trois endpoints du repli existent côté gateway (`POST /runner/poll|send|disconnect`,
   `RunnerSecurityConfig:75-77`) et sont authentifiés par l'en-tête `X-Runner-Token`.

Le repli est donc **tenté** dès deux échecs consécutifs de transport — soit, avec le backoff, environ
une seconde après le premier échec. Aucun défaut n'a pu être établi dans cette chaîne. **On ne
fabrique donc pas de correctif pour un défaut qu'on n'a pas établi.**

### 0.3 — Ce qui est établi, en revanche : trois silences

**(a) Rien ne récapitule jamais ce qui a été tenté.** Les messages existent, mais dispersés dans le
défilement et jamais réunis : « Connexion échouée : … » (par échec), « WebSocket coupé de façon
répétée sur ce réseau — bascule sur le repli long-polling HTTP. », « Repli long-polling actif :
… ». Au moment où le runner s'arrête, l'utilisateur n'a **nulle part** la phrase qui compte : quel
transport a été essayé, **pourquoi** il a échoué, et lequel a été retenu. C'est exactement le
« s'est arrêté sans rien dire d'exploitable » du constat.

**(b) Un échec total de transport sort en code `0`.** Trois chemins ramènent la main sans que le
moindre transport ait porté la session, et `execute()` rend **`0` — « arrêt normal »** :

| Chemin | Code |
|---|---|
| `RunnerConnection.run()` quitté par `catch (InterruptedException) { break; }` — aucun repli, **aucune ligne** | `0` |
| `RunnerConnection.run()` quitté par `if (!sleep(delay)) break;` (backoff interrompu) — idem | `0` |
| `PollingConnection.loop()` quitté sur `ChannelClosedException` (409) ou drapeau `running` | `0` |

Un code `0` après un échec total de transport dit « tout s'est bien passé » à qui lit le code de
sortie — script d'exploitation, supervision, ou simplement l'utilisateur qui n'a rien vu passer.

**(c) Un transport imposé ne dit pas qu'il interdit le repli.** Avec `--transport websocket`,
`shouldFallBack()` rend `false` **par construction** (choix délibéré de SF-38-09 : un opérateur qui
a demandé la socket doit voir l'échec, pas un contournement silencieux). Mais le runner ne dit
jamais que c'est **ce drapeau** qui empêche le repli : on voit des reconnexions sans fin, sans
savoir qu'un repli existe et qu'on l'a soi-même interdit.

### 0.4 — Ce que cette subfeature corrige donc

**Le silence, et lui seul.** La logique de bascule n'est pas touchée : `TransportFallbackPolicy`
garde ses seuils, `RunnerConnection` garde sa boucle, `PollingConnection` garde la sienne.

### 0.5 — Ce qui n'est PAS corrigé, et pourquoi (risque résiduel assumé)

`TransportFallbackPolicy.recordSessionEnded()` **remet le compteur à zéro** dès qu'une session a
vécu plus de 5 s. Un proxy qui tue la socket toutes les dix secondes fait donc boucler le runner
**sans jamais** basculer. C'est un comportement **documenté et voulu** (« une session qui a réellement
vécu remet le compteur à zéro ») et non un défaut observé le 2026-09-12. Le modifier serait changer
la politique de repli de SF-38-09 sur la foi d'une hypothèse — exactement ce que le cadrage
interdit. Consigné en risque résiduel ; le journal de transport le rendra **visible** s'il se
produit, ce qui est la condition pour pouvoir le trancher un jour sur des faits.

---

## Objectif

Le runner **nomme** le transport essayé, le **motif** de son échec, et le transport **finalement
retenu** — et un échec total de transport cesse de sortir en code « arrêt normal ».

---

## Comportement attendu

### Cas nominal — le WebSocket tient

Aucune ligne ajoutée. Un transport qui s'établit du premier coup et ne tombe jamais n'a rien à
récapituler : le récapitulatif est **muet** quand il n'y a rien à dire.

### Cas du repli — le WebSocket est refusé, le long-polling prend le relais

À la fin de la session, un bloc de trois lignes :

```
Transports : WebSocket (wss://…/runner/ws) — 2 échec(s), dernier motif : Connexion refusée.
Transports : long-polling HTTP (https://…/runner/poll) — établi.
Transport retenu : long-polling HTTP.
```

**Les deux** transports sont nommés, avec leur cible expurgée du jeton, et le motif du dernier échec.

### Cas de l'échec total — aucun transport n'a tenu

```
Transports : WebSocket (wss://…/runner/ws) — 2 échec(s), dernier motif : Connexion refusée.
Transports : long-polling HTTP (https://…/runner/poll) — 1 échec(s), dernier motif : Liaison fermée par la gateway (409).
Aucun transport n'a tenu : ni WebSocket, ni long-polling HTTP.
```

et le processus sort en **`6`**, pas en `0`.

### Cas du transport imposé

`--transport websocket`, après un échec : la ligne qui manquait —

```
Aucun repli tenté : --transport websocket impose la socket (retirez le drapeau pour autoriser le repli).
```

### Cas d'erreur

| Situation | Comportement attendu | Code |
|---|---|---|
| Arrêt demandé (`Ctrl-C`) avant qu'un transport ne s'établisse | Le récapitulatif est dit, **mais le code reste `0`** : l'arrêt a été demandé, ce n'est pas un échec | `0` |
| Jeton refusé (401) | Chemin inchangé : le jeton est effacé, réappairage ou code `4` | `3`/`4` |
| Gateway injoignable au contrôle de vol | Chemin inchangé, avant tout transport | `5` |
| Erreur inattendue | Chemin inchangé | `1` |
| Un transport s'est établi puis la session s'est terminée | Récapitulatif si des échecs ont eu lieu, code `0` | `0` |

---

## Critères d'acceptation

- [ ] La **conclusion de l'instruction** (§0) est écrite dans cette mini-spec, et le correctif s'y
      tient : **aucune** modification de `TransportFallbackPolicy`, ni des seuils, ni de la boucle
      de bascule.
- [ ] Le runner **nomme le transport essayé**, avec sa cible — jeton **expurgé**.
- [ ] Le runner **nomme le motif** de l'échec de chaque transport.
- [ ] Le runner **nomme le transport retenu** quand un transport s'est établi.
- [ ] Quand **aucun** transport n'a tenu, le runner le dit et nomme **les deux** transports tentés.
- [ ] Une session dont le transport s'établit du premier coup n'ajoute **aucune** ligne.
- [ ] Un échec total de transport sort en **`6`**, jamais en `0`.
- [ ] Un `Ctrl-C` avant établissement sort en **`0`** : un arrêt demandé n'est pas un échec.
- [ ] `--transport websocket` dit **pourquoi** aucun repli n'est tenté, et **comment** l'autoriser.
- [ ] Les codes de sortie existants (`0`, `1`, `2`, `3`, `4`, `5`) sont **inchangés** sur leurs
      chemins respectifs.
- [ ] Le journal ne consigne **aucun jeton**, nulle part.

---

## Périmètre

### Hors scope (explicite)

- **Changer la politique de bascule** : seuils, comptage, remise à zéro sur session longue —
  inchangés (§0.5).
- Revenir au WebSocket à chaud après un repli (le repli reste unidirectionnel, SF-38-09).
- Rendre le `poll` HTTP en vol annulable (SF-82-01, hors scope également).
- Un troisième transport.
- Arrêter le processus du runner depuis l'application, ou un service système.

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs | Normalisation |
|---|---|---|---|
| Cible d'un transport | Oui | URL absolue | jeton remplacé par `***` (même règle que `safeUri`) |
| Motif d'échec | Oui | message de `Failures.describe` / de l'exception | tronqué à 200 caractères |
| Code de sortie « aucun transport » | — | `6` | — |

---

## Technique

### Endpoint(s)

Aucun. Aucune modification côté gateway.

### Tables impactées

Aucune.

### Composants impactés

| Fichier | Nature |
|---|---|
| `runner/src/main/java/fr/claudegateway/runner/TransportJournal.java` | **nouveau** — ce qui a été tenté, pourquoi ça a échoué, ce qui a été retenu |
| `runner/src/main/java/fr/claudegateway/runner/RunnerConnection.java` | consigne ses tentatives (constructeurs existants conservés) |
| `runner/src/main/java/fr/claudegateway/runner/PollingConnection.java` | consigne les siennes (constructeur existant conservé) |
| `runner/src/main/java/fr/claudegateway/runner/RunnerMain.java` | dit le récapitulatif, et rend `6` sur échec total |
| `runner/src/test/java/fr/claudegateway/runner/TransportJournalTest.java` | **nouveau** |

`TransportFallbackPolicy` n'est **pas** dans cette liste, et c'est le point.

---

## Plan de test

### Tests unitaires (`runner`)

| # | Test | Vérifie |
|---|---|---|
| 1 | `un_transport_etabli_du_premier_coup_ne_dit_rien` | Le récapitulatif est muet quand il n'y a rien à dire |
| 2 | `le_repli_nomme_les_deux_transports_et_le_motif_de_l_echec` | **Le cas du cadrage** : WS refusé → long-polling, et **les deux** sont nommés |
| 3 | `le_transport_retenu_est_nomme` | La troisième chose exigée |
| 4 | `un_echec_total_nomme_les_deux_transports_et_le_dit` | Ni WS ni polling : c'est dit |
| 5 | `un_echec_total_n_est_pas_un_arret_normal` | `anyEstablished()` faux → code `6` |
| 6 | `un_arret_demande_avant_tout_transport_reste_un_arret_normal` | `Ctrl-C` → `0` |
| 7 | `le_journal_n_ecrit_jamais_le_jeton` | La cible est expurgée |
| 8 | `un_motif_tres_long_est_tronque` | Borne défensive |
| 9 | `un_transport_impose_dit_pourquoi_aucun_repli_n_est_tente` | `--transport websocket` |
| 10 | `le_code_de_sortie_reserve_est_six` | La constante, et qu'elle ne collisionne pas |

### Tests de non-régression

La suite `TransportFallbackPolicyTest` existante doit passer **sans modification** : c'est la preuve
que la politique de bascule n'a pas été touchée.

### Tests d'intégration

Aucun : le journal est de la logique pure, et le transport réel reste un smoke manuel (note de
classe de `RunnerConnection`).

### Isolation utilisateur

Sans objet — code local au runner, aucun accès aux données.

---

## Préoccupations transversales

| Préoccupation | Touchée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | — |
| Navigation / routing | Non | — |

---

## Risques résiduels

1. **La remise à zéro du compteur sur session longue** (§0.5) : un proxy qui coupe toutes les dix
   secondes ne déclenchera jamais le repli. Non corrigé délibérément — défaut non établi. Le
   récapitulatif le rendra visible.
2. **Le code `6`** est nouveau. Aucun script connu ne lit le code de sortie du runner, et aucun test
   n'exerce `execute()` jusqu'à une session ; la surface est donc nulle aujourd'hui. Arbitrage tracé.

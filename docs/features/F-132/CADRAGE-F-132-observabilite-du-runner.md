# F-132 — Observabilité du runner (journal de diagnostic consultable)

> Cadrage du 2026-09-19, à la demande du PO. **Cadrage seul : livraison sur go.** Inclut un **chiffrage**
> (le PO veut savoir si c'est financièrement tenable avant tout changement d'archi).

## 1. Le besoin (constat vécu cette nuit)
Le gros du travail Vigie/Teams se fait **dans le runner**, mais côté serveur c'est une **boîte noire**.
Ce qu'on n'a **pas** pu voir depuis la gateway pendant le debug CAGIP (il a fallu lire le terminal local) :
- la **raison** de « Teams connecté » rouge (readiness en mémoire, non tracée) ;
- l'état réel du **Chrome managé** (lancé ? port ? échec ?) ;
- le **cycle de vie de la capture** (join / start / stop) — absent de `runner_audit` ;
- la cause de l'**échec de la 2ᵉ capture**.

**Conséquence** : diagnostic lent, dépendant de captures d'écran et du terminal local. Objectif : rendre le
runner **observable à distance**, vite, y compris **par l'assistant**.

## 2. Principe (ce qu'on fait — et ce qu'on ne fait PAS)
> **Des événements de diagnostic structurés, expurgés et bornés — PAS un firehose de logs bruts.**

- **NON** : streamer tous les logs bruts du runner en continu. Refusé pour trois raisons : **confidentialité**
  (le runner est sur un poste **client/banque** ; les logs bruts peuvent contenir chemins, commandes,
  contenus Teams), **coût** (bande passante uplink + stockage permanent), **bruit** (le verbeux noie le
  signal).
- **OUI** : des **événements structurés à niveaux** (`DEBUG`/`INFO`/`WARN`/`ERROR`), portant des **formes et
  des états**, **jamais le contenu** (esprit « relevé forme » SF-89-12). Transport = le **WebSocket qui
  existe déjà** (aucune nouvelle archi de transport). Stockage **borné** (anneau par poste, TTL court).
  Consultation par **endpoint + panneau UI + l'assistant à la demande**. Détail lourd = **snapshot à la
  demande** (on étend « Vérifier ce que voit le runner » de F-122), pas en continu.

## 3. Ce qu'on capture (exemples, expurgés)
- **Chrome managé** : tentative de lancement, exécutable retenu (nom, pas le chemin complet si sensible),
  port, état `REACHABLE`/`LAUNCHED`/`UNREACHABLE`/`NO_BROWSER`, échec + code.
- **Sonde Teams** : résultat (connecté / reconnexion requise / onglet non ouvert), verdict de l'adaptateur
  (champs reconnus / attendus — **des nombres, pas les valeurs**), URL **classifiée** (type, pas l'URL brute).
- **Capture** : join (ok/échec + raison), start/stop, tailles (octets audio, nb images) — **pas** le média.
- **Boucle Vigie / heartbeat** : ticks, readiness assemblée, échecs de remontée.
- **Erreurs** : type + message court **expurgé** (jamais de secret, jamais de contenu métier).

## 4. Confidentialité & cloisonnement (le point dur, banque)
- **Expurgation à la source** (dans le runner, avant émission) : aucun secret, aucun contenu Teams, aucune
  donnée métier ; chemins réduits/masqués ; URLs remplacées par leur **classe**. Règle : on émet **ce qui
  aide à diagnostiquer la plomberie**, jamais ce que le poste **contient**.
- **Isolation** : événements rangés par `user_id`+`host_id` ; un utilisateur ne voit que ses postes.
- **Opt-in / niveau réglable par poste** (à trancher) : niveau `INFO` par défaut ; `DEBUG` activable
  ponctuellement pour un debug, puis retombe. **Rétention courte** (défaut proposé 7 jours, purge auto).
- **Lecture par l'assistant** = lecture de ces **événements expurgés**, jamais des logs bruts du poste.

## 5. Transport & stockage
- **Transport** : trame `runner_diag` sur le **WebSocket existant** (comme `runner_audit`/le heartbeat).
  Aucune nouvelle connexion, aucun port. Émission **par lots** (batch, ~toutes les quelques secondes) pour
  limiter le trafic.
- **Stockage** : table `runner_diag_events` (anneau **borné par poste**, ex. N derniers + TTL 7 j, purge
  planifiée) sur la RDS partagée existante — **pas** de nouvelle infra. *(Variante : S3 si volume ; a priori
  inutile vu le chiffrage.)*
- **Consultation** : `GET /runner-hosts/{hostId}/diag` (paginé, filtrable par niveau/temps), isolation
  `user_id`+`host_id` ; panneau **« Journal du runner »** dans la Vigie ; lisible par l'assistant via la
  base (comme `runner_audit` aujourd'hui). **Snapshot à la demande** : endpoint qui demande au runner un
  état complet ponctuel (extension du diagnostic F-122).

## 6. Chiffrage (ordre de grandeur — la question du PO)
Hypothèses : événement structuré ~**300–500 octets**, poste actif émettant ~**5–15 événements/min** en
usage normal (plus en `DEBUG` ponctuel).
- **Bande passante** : ~10 evts/min × 400 o ≈ **4 Ko/min ≈ 0,25 Mo/h ≈ ~2 Mo par journée de 8 h et par
  poste**. **Négligeable** — à comparer aux **520 Mo** d'une image de déploiement ou **1,6 Mo** d'un seul
  audio de réunion. Sur le WebSocket existant → **coût transport marginal**.
- **Stockage** : anneau ex. **2000 événements/poste** = ~**1 Mo/poste** ; TTL 7 j → quelques Mo pour une
  poignée de postes. **Négligeable** sur la RDS partagée.
- **Coût jetons** : **nul** tant que l'assistant ne lit pas les événements (lecture à la demande, comme
  `runner_audit`).
**Verdict : financièrement tenable, et de très loin — à condition de rester sur des événements structurés
bornés (le firehose brut, lui, serait coûteux ET risqué ; on l'écarte).**

## 7. Découpage
| SF | Titre | Contenu |
|---|---|---|
| **SF-132-01** | **Émettre des événements de diagnostic (runner)** | Points d'instrumentation clés (Chrome managé, sonde Teams, capture, boucle Vigie, erreurs) → trame `runner_diag` expurgée, à niveaux, batchée, sur le WebSocket existant. Expurgation à la source. |
| **SF-132-02** | **Stocker + exposer (gateway)** | Table `runner_diag_events` (anneau borné + TTL + purge), `GET …/diag` paginé/filtré, isolation `user_id`+`host_id`. |
| **SF-132-03** | **Panneau « Journal du runner » (Vigie)** | UI de consultation (niveau, temps, recherche), par poste. |
| **SF-132-04** *(option)* | **Snapshot à la demande** | Extension du « Vérifier ce que voit le runner » (F-122) : état complet ponctuel tiré à la demande. |
| **SF-132-05** *(option)* | **Niveau réglable par poste** | Passer un poste en `DEBUG` le temps d'un diagnostic, retour auto à `INFO`. |

**Ordre** : 132-01 → 132-02 → 132-03 (→ 04/05 options).

## 8. À trancher (défauts proposés)
1. **Rétention** : 7 jours + purge auto. ✔️ ?
2. **Niveau par défaut** : `INFO` (DEBUG ponctuel via SF-132-05). ✔️ ?
3. **Stockage** : RDS partagée (anneau borné). ✔️ ? *(S3 seulement si le volume l'exigeait — improbable.)*
4. **Lecture par l'assistant** : via la base (comme `runner_audit`). ✔️ ?

## 9. Hors périmètre
- **Logs bruts** / firehose / streaming de contenu (écarté §2).
- Rétention longue / analytics.
- Observabilité de la gateway elle-même (déjà via CloudWatch — C3).

## 10. Préoccupations transversales
- **Auth / tenant** : lecture isolée `user_id`+`host_id` ; le runner est déjà identifié (`X-Runner-Token`).
- **Confidentialité** : expurgation à la source (§4) — invariant non négociable (poste client/banque).
- **Composants** : runner (instrumentation + expurgation + trame `runner_diag`), transport WS existant,
  gateway (table + endpoint + purge), frontend (panneau Vigie), extension du diagnostic F-122.

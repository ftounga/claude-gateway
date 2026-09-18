# SF-122-06 — Brancher la boucle Vigie (Chrome managé + readiness) dans le runtime du runner

> Cadrage du 2026-09-19 (PO), après un test réel CAGIP : la mise en service reste bloquée « en attente du
> runner » et « Rejoindre & capturer » échoue (« Chrome managé injoignable »). **Cadrage seul : livraison
> sur go** (donné). Corrige un **défaut de livraison de F-122**.

## 1. Le constat (vérifié dans le code + en prod)
Les briques runner de F-122 existent et passent leurs tests unitaires, **mais ne sont jamais appelées au
runtime** :
- `ManagedChrome.real(...)` → **aucun appelant** (hors tests).
- `VigieReadinessUploader` (POST `/runner/vigie/readiness`) → **aucun appelant** (cité en commentaire seul).
- `VigieBackground.assemble(...)`, `VigieServiceDiagnostic` → **aucun appelant**.
- `RunnerMain` / `RunnerConnection` ne mentionnent **ni Vigie, ni Chrome managé, ni readiness**.

Conséquence en prod (CAGIP, 2026-09-19, runner 1.0.0 à jour) : le runner **ne lance jamais** le Chrome
managé et **ne remonte jamais** de readiness → les 4 vérifications de la check-list (SF-122-02) restent
« en attente du runner », « Démarrer la Vigie » reste grisé, « Teams : navigateur non détecté », et
`teams_meeting_join` (F-128) échoue faute de Chrome managé. **Le code de capture F-128 est prêt ; c'est ce
câblage amont qui manque.**

## 2. Objectif (une phrase)
Brancher, dans le cycle de vie du runner, une **tâche d'arrière-plan Vigie** qui lance et maintient le
Chrome managé et **remonte périodiquement l'état de mise en service**, pour que la check-list SF-122-02
passe au vert et que la capture F-128 fonctionne.

## 3. Comportement attendu
1. **Démarrage** : à la connexion du runner (là où vit déjà le heartbeat planifié de `RunnerConnection`),
   démarrer une boucle Vigie planifiée (période raisonnable, ~15–30 s).
2. **Chrome managé** : résoudre les réglages (`ManagedChromeSettings.resolve` — port `BrowserPort` 9222,
   profil dédié, OS), instancier `ManagedChrome.real(settings)`, **s'assurer qu'il est lancé** (idempotent :
   ne pas relancer s'il est déjà joignable ; relancer s'il est tombé). Réutilise SF-122-05 (flags
   auto-accept + amorçage du profil).
3. **Sonder + assembler + remonter** : sonder l'état (`isReachable`), déterminer « Teams connecté » et le
   « test de lecture Teams » (les 4 vérifs), assembler via `VigieBackground.assemble(...)` un
   `VigieReadinessReport`, et le **remonter** via `VigieReadinessUploader` (POST `/runner/vigie/readiness`).
4. **Relogin Teams** : quand « Teams connecté » est faux, déclencher le chemin de **connexion/relogin
   Teams** (SF-122-03) — le Chrome managé **surgit** pour le login interactif (SSO/MFA), puis se remasque.
   S'assurer que ce chemin est atteignable une fois la boucle branchée.
5. **Best-effort, jamais fatal** : si le Chrome est absent (`NO_BROWSER`) ou la sonde échoue, remonter
   l'état **actionnable** (SF-122-04) sans casser le runner ni ses autres fonctions.
6. **Arrêt propre** : à la déconnexion/arrêt du runner, **arrêter le Chrome managé** (pas de Chrome
   orphelin) et la boucle.

## 4. Cas d'erreur / limites
- **Chrome/Edge introuvable** → `NO_BROWSER` remonté (message actionnable) ; le reste du runner continue.
- **Port de débogage injoignable après lancement** → `UNREACHABLE` remonté ; nouvel essai au tick suivant.
- **Teams non connecté** → check-list « Teams connecté » ❌ + chemin relogin proposé ; pas de blocage du
  runner.
- La boucle ne doit pas **spammer** (relancer Chrome en boucle) : idempotence stricte sur « déjà joignable ».

## 5. Portée & implémentation (esquisse)
- **`RunnerConnection`** (réutiliser `heartbeatExecutor`/un `ScheduledExecutorService`) ou un petit
  `VigieLoop` démarré par `RunnerConnection`/`RunnerMain` : orchestrer §3 (ensure Chrome → probe → assemble
  → upload) au tick, et le cycle de vie §1/§6.
- Réutiliser tel quel : `ManagedChrome`, `ManagedChromeSettings`, `VigieBackground`, `VigieReadinessReport`,
  `VigieReadinessUploader`, `VigieServiceDiagnostic`, `ChromePaths`, `ChromeProfileSeed` (SF-122-05).
- Aucune nouvelle table, aucune migration, aucun endpoint (le POST readiness existe déjà). Backend gateway :
  inchangé (il consomme déjà le readiness pour la check-list SF-122-02).

## 6. Critères d'acceptation
- Runner démarré → **au bout de quelques ticks, le backend reçoit un readiness** et la check-list SF-122-02
  reflète l'état réel (Chrome managé joignable / Teams / test de lecture), au lieu de rester « en attente ».
- Chrome managé **lancé automatiquement** (sans manip debug manuelle), idempotent, arrêté avec le runner.
- `NO_BROWSER` / `UNREACHABLE` remontés proprement (message actionnable), sans casser le runner.
- Une fois la check-list au vert, **« Rejoindre & capturer » (F-128) fonctionne** (join dans le Chrome
  managé).
- Tests runner : la boucle assemble+remonte un rapport quand joignable ; NO_BROWSER si absent ; idempotence
  (pas de double lancement) ; arrêt propre ; échec Vigie non fatal pour le runner. Non-régression du
  heartbeat et des autres outils.

## 7. Hors périmètre
- Refonte de l'observation réseau Teams / `TeamsAdapterV1` (inchangés).
- Le **test de lecture Teams réel** (dépend d'une session Teams connectée) : la boucle le **remonte**, elle
  ne le simule pas.
- L'auto-accept (SF-122-05) et la capture (F-128) : déjà livrés, ici on les **rend atteignables**.

## 8. Préoccupations transversales
- **Composants** : `RunnerConnection`/`RunnerMain` (câblage + cycle de vie), `ManagedChrome` &co (réutilisés).
  Auth/tenant : inchangés (le runner est déjà identifié). Réseau : le Chrome managé ouvre son port de
  débogage **local** (boucle locale), comme aujourd'hui en manuel.

# SF-100-09 — Collecte Radar sur Teams v2 : brancher le repli « lecture-écran »

> **⛔ CLOSE — DÉJÀ LIVRÉ (2026-09-19).** Vérification à la livraison : le repli lecture-écran est **déjà
> branché et mergé** par **SF-89-06 (PR #560, 2026-09-13)** — `RadarTools.verify`, `TeamsRadarCollector.run`
> via `TeamsScreenFallback`, 38 tests verts. La prémisse de ce cadrage (« pas branché ») venait d'une note
> de risque F-100 **périmée** (corrigée). **Aucun code écrit.** Seul reliquat réel (non-code) : **activer la
> synchro du soir** (SF-100-07, geste) et **caler/valider les sélecteurs DOM Teams v2 sur poste réel**
> (`TeamsScreen.VERSION` « à confirmer »). Le cadrage ci-dessous est conservé pour trace.

> Cadrage du 2026-09-19 (PO). **Livraison demandée.** Referme le **risque déjà suivi dans F-100** : sur le
> nouveau Teams (v2), la collecte du Radar capte les réunions mais **pas les conversations ni les
> transcriptions**.

## 1. Le constat (déjà documenté F-100, reproduit cette nuit)
La synchro du soir (F-100) et la lecture des échanges (F-101) captent bien les **réunions/calendrier**
(REST `graph.microsoft.com`), mais **0 conversation** sur Teams **v2** : le client v2 **sert les fils
depuis son cache** → l'observation **réseau** ne voit rien passer. Le risque était noté dans F-100 :
> « Teams sert les fils depuis son cache et aucun échange ne porte la transcription — le **repli “lire
> l'écran” décidé en F-87 §9 bis n'est pas encore branché** sur `teams_radar_verify` / `teams_radar_collect`
> : les cases *conversations* et *transcriptions* peuvent rester vides sur le nouveau Teams. »

Conséquence directe : le **Radar reste à moitié aveugle** → le **résumé du matin (F-102)** sous-alimenté
(pas de « à faire par moi / j'attends des autres » issus des conversations).

## 2. Ce qui existe déjà (on branche, on ne réécrit pas)
- La **décision** est prise (F-87 §9 bis : lire l'écran en repli quand le réseau ne porte rien).
- Le **code existe** : `TeamsScreen` / `TeamsScreenReader` lisent le **DOM** (innerText/textContent, bouton
  de transcription) — sans toucher cookies/stockage/caches.
- La **collecte** existe : `teams_radar_collect` / `teams_radar_verify` (`RadarCollector`,
  `TeamsRadarCollector`, `RadarSyncAgent`).
**Le trou : le repli lecture-écran n'est PAS branché dans la collecte/vérif.**

## 3. Objectif (une phrase)
Quand la voie **réseau** ne rapporte pas les conversations/transcriptions (cas Teams v2 servi du cache),
la collecte et la vérification du Radar **basculent sur la lecture de l'écran** (DOM de l'onglet Teams
managé) pour en tirer les fils/messages visibles — de sorte que le Radar se **remplisse** et que la
**vérification** ne reste plus vide sur les cases *conversations* / *transcriptions*.

## 4. Comportement attendu
1. **Vérification (`teams_radar_verify`)** : si la case *conversations* (ou *transcriptions*) est vide côté
   réseau, tenter la **lecture-écran** ; la case passe à « vu » si l'écran fournit le contenu attendu, sinon
   reste explicitement « non vu » avec la **raison** (onglet non ouvert / rien à l'écran / politique).
2. **Collecte (`teams_radar_collect`)** : en repli, lire depuis le DOM les **fils visibles** et leurs
   messages (dans les bornes raisonnables de ce qui est chargé à l'écran), et les verser dans le **même
   contrat d'entrée** que la voie réseau (SF-101-01) — pour que F-101 les analyse sans distinction de
   source. **Traçabilité de la source** (réseau vs écran) conservée.
3. **Expurgation / cloisonnement** : lecture du **contenu visible** uniquement (comme F-87 : ni cookies, ni
   stockage) ; rien n'est écrit ; texte brut supprimé après analyse (contrat F-101 inchangé).
4. **Priorité réseau** : le réseau reste la voie **primaire** (plus riche/fiable) ; l'écran est un **repli**,
   pas un remplacement — pour ne pas dégrader les clients où le réseau porte les fils (autre client).

## 5. Ce qui reste hors de cette SF (et pourquoi)
- **Traversée profonde de tous les fils** (scroller/ouvrir chaque conversation programmatiquement) : cette
  SF lit **ce qui est chargé/visible** ; l'exploration exhaustive de l'historique par pilotage de l'UI est
  une **évolution** si besoin (plus lourde).
- **Recalibration des FORMES réseau v2** : secondaire — puisque v2 sert du cache, le réseau ne porte de
  toute façon pas les fils ; le repli écran est la bonne réponse. Si un relevé forme réseau v2 s'avère
  utile plus tard, il fera l'objet d'un lot dédié.
- **Assouplir une porte de readiness** : non (décision PO — cf. mémoire *porte-validation-vs-policy-client*).

## 6. Validation
Le comportement navigateur réel n'est **pas** vérifiable en CI (comme la capture F-128) → **« À VALIDER SUR
USAGE RÉEL »** sur le poste du PO. Tests automatisés : les parties **pures** (assemblage, mapping vers le
contrat F-101, décision réseau→repli, expurgation) + la logique de bascule ; le rendu DOM réel est validé
sur le poste. **Un relevé/observation léger du DOM v2** par le PO peut être nécessaire pour **caler les
sélecteurs** — marche à donner au moment du test (aucune valeur sensible, juste la forme visible).

## 7. Critères d'acceptation
- Sur une vérification où la case *conversations* est vide côté réseau, la **lecture-écran** est tentée et
  peut faire passer la case à « vu » (test de la logique de bascule + mapping).
- La collecte verse les fils lus à l'écran dans le **contrat F-101** (même forme), source **tracée**.
- Voie réseau **prioritaire**, repli écran seulement si vide (aucune régression chez un client où le réseau
  porte les fils).
- Expurgation respectée (aucun cookie/stockage/valeur sensible), contrat F-101 (texte brut supprimé) intact.

## 8. Préoccupations transversales
- **Confidentialité** : lecture du contenu visible only, expurgée ; isolation `user_id`+`host_id` inchangée.
- **Composants** : runner — `teams_radar_collect`/`teams_radar_verify` (`RadarCollector`/
  `TeamsRadarCollector`/`RadarSyncAgent`), branchement de `TeamsScreen`/`TeamsScreenReader` (F-87 §9 bis) ;
  contrat d'entrée F-101 inchangé ; gateway/analyse F-101 inchangées (même contrat). **C'est du code
  runner → mise à jour du runner nécessaire.**

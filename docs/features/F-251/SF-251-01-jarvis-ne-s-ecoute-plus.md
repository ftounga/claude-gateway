# Mini-spec — F-251 / SF-251-01 — Jarvis ne s'écoute plus parler

## Statut
`in-review` — 2026-11-08 — branche `feat/SF-251-01-jarvis-ne-s-ecoute-plus`

## Objectif
> Le micro ne s'ouvre plus jamais pendant que Jarvis parle : ni par la fin d'un tour qui ignore une phrase en cours
> (course A), ni par une ouverture d'écoute qui aboutit pendant une phrase (course B).

## Constat (cadrage §1, cause 1 — essai du 07/11)
- Course A (seq 190-200, 230-239) : `say_start` juste avant `brain_done` ; la fin du tour remet « repos » sans voir la
  phrase ⇒ réécoute armée 250 ms plus tard, micro ouvert sous la voix. Variante (seq 41-49) : la phrase dite finit, la
  file de voix du cerveau rapide joue encore ⇒ « repos » et réécoute sous la voix.
- Course B (seq 123-128) : `say()` démarre pendant l'ouverture ; `openListening` passe à « écoute » sans condition.

## Comportement nominal
1. **Compteur de phrases** (`saying`) : `say()` l'incrémente, le décrémente à sa fin. Une phrase finie ne remet
   « repos » que si aucune autre phrase n'est dite **et** que la file de voix est vide (`JarvisVoiceService.busy`) ;
   sinon « parole » est gardé et « repos » vient quand la file se vide.
2. **Fin du tour du cerveau rapide** : « parole » gardé si une phrase est dite ou que la file joue.
3. **`say()` coupe l'écoute** avant de parler : réécoute armée annulée, ouverture en cours abandonnée (numéro
   d'ouverture), écoute ouverte close sans envoi — chaque cas journalisé `say_during_listen` (`arm` / `opening` /
   `ecoute`).
4. **`openListening` abandonne** si Jarvis parle à l'aboutissement (phrase dite, ou « parole » avec file active) :
   ce qui a été ouvert est rendu, `listen_open_drop` journalisé.
5. **Réécoute** : le minuteur ne rouvre pas si une phrase est dite, si la file joue ou si le cerveau rapide calcule
   encore (`relisten_skip voix|cerveau`) ; à la fin du cerveau rapide, la réécoute est réarmée si l'écran est au repos.

## Cas d'erreur
- La file de voix ne se vide jamais (lecture figée) : la borne de lecture SF-233-16 la close ⇒ `idle()` résolu ⇒ repos.
- Un appui pendant que Jarvis parle (`talk()`) : `voice.stop()` puis écoute — inchangé (la phrase interrompue se
  termine avant l'ouverture).
- Une coupure par la voix (capture de l'interruption en cours) : `say()` ne ferme pas cette capture.

## Critères d'acceptation
- [ ] Course A : une phrase dite qui démarre avant la fin du tour ⇒ l'état reste « parole » jusqu'à sa fin, aucune
  écoute ouverte avant.
- [ ] Variante A : phrase finie, file de voix active ⇒ « parole » jusqu'à `idle()`, puis « repos » et réécoute.
- [ ] Course B : `say()` pendant l'ouverture ⇒ l'écoute n'aboutit pas (session rendue), `say_during_listen opening`.
- [ ] `say()` réécoute armée ⇒ minuteur annulé (`say_during_listen arm`) ; écoute ouverte ⇒ close (`… ecoute`).
- [ ] Aucune régression des tests Jarvis existants.

## Contraintes de validation
Aucun champ saisi. Événements de journal : `say_during_listen` (détail `arm|opening|ecoute`), `listen_open_drop`,
`relisten_skip` (détail `voix|cerveau` en plus de l'existant).

## Plan de test
- Unitaires (`jarvis.component.spec.ts`, bloc F-251) : course A, variante A (file active), course B, réécoute annulée,
  écoute close par `say()`, réécoute réarmée après le cerveau rapide ; `jarvis-voice.service.spec.ts` : `busy`.
- Intégration : aucun endpoint touché.
- Isolation utilisateur : inchangée (aucune donnée lue ni écrite).

## Impacts
- Front : `jarvis.component.ts`, `core/services/jarvis-voice.service.ts` (`busy`).
- Back : aucun. Aucune table, aucune migration.

### Préoccupations transversales
- [ ] Auth / Principal — non. [ ] Contexte tenant — non. [ ] Plans / limites — non. [ ] Navigation — non.

## Hors périmètre
- Micro persistant et tampon (SF-251-02), porte de parole (SF-251-03), délai de grâce (SF-251-04), détection de voix
  (SF-251-05). Aucune consigne ni modèle touché ⇒ bancs Jarvis non requis (V8).

## Vérification après déploiement
```sql
-- Écoutes ouvertes alors qu'une phrase de Jarvis est en cours (doit être 0) : un say_start sans say_end avant stream_open.
SELECT t.page_id, t.seq FROM jarvis_listen_traces t
WHERE t.event = 'stream_open' AND t.created_at > now() - interval '2 days'
  AND EXISTS (SELECT 1 FROM jarvis_listen_traces s WHERE s.page_id = t.page_id AND s.event = 'say_start' AND s.seq < t.seq
    AND NOT EXISTS (SELECT 1 FROM jarvis_listen_traces e WHERE e.page_id = t.page_id AND e.event = 'say_end'
      AND e.seq > s.seq AND e.seq < t.seq));
-- Les protections en action.
SELECT event, detail, count(*) FROM jarvis_listen_traces
WHERE event IN ('say_during_listen', 'listen_open_drop', 'relisten_skip') AND created_at > now() - interval '2 days'
GROUP BY 1, 2 ORDER BY 3 DESC;
```

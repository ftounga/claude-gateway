# Mini-spec — F-251 / SF-251-02 — Le micro reste ouvert

## Statut
`in-review` — 2026-11-09 — branche `feat/SF-251-02-le-micro-reste-ouvert`

## Objectif
> Ne plus perdre le début des phrases : le micro et le module audio restent ouverts pendant toute la conversation, les
> 1,5 s précédant chaque écoute partent en tête de la session, et le ticket d'écoute est demandé pendant que Jarvis parle.

## Constat (cadrage §1, cause 2 — essai du 07/11)
21 réouvertures : 0,25 s d'attente + ≈ 0,78 s (ticket HTTP, `getUserMedia`, WebSocket, module audio) ≈ **1,05 s de
surdité** à chaque fois ; rien n'est gardé avant `session_started`.

## Comportement nominal
1. **Micro de la conversation** (`ConversationMic`, tenu par `JarvisStreamOpener`) : ouvert à la première écoute en flux
   d'une conversation active, **gardé** entre les écoutes, rendu à la pause, à la veille, à la sortie de l'écran et avant
   toute dictée de repli. Journal : `mic_keep open ms=|reuse|fail|close`. Un micro devenu inutilisable (piste terminée,
   contexte fermé) est rouvert à l'écoute suivante.
2. **Tampon circulaire de 1,5 s** : hors écoute, le son est gardé ; à l'acceptation de la session il part en tête
   (`sendPreRoll`, morceaux de 100 ms), puis le son vivant enchaîne sans trou. Journal : `preroll ms=<durée envoyée>`.
   La phrase du mot d'éveil (F-239), si elle existe, prime sur le tampon.
3. **Anti-écho** : tant que Jarvis parle (état « parole », phrase dite ou voix qui joue) **et 150 ms après**, le tampon
   est vidé — jamais la voix de Jarvis dans une session.
4. **Une phrase par écoute** : à l'attachement le tampon est consommé ; à la fin d'une session il repart vide (la fin de
   la phrase précédente n'est jamais renvoyée).
5. **Ticket d'avance** : à l'entrée en « parole » d'une conversation active, le ticket suivant est demandé ; à
   l'ouverture il est utilisé (ou attendu s'il est en vol). Journal : `ticket_ready ms=`. Expiré ou inutilisé (pause,
   veille, sortie) : rendu au serveur (`closeSttSession(id, 0)`).
6. **Capture du banc et retranscription** : le tampon envoyé est gardé (`keep`), le clip inclut donc le début de la
   phrase. Le débit « implausible » compte la durée du tampon.

## Cas d'erreur
- Micro refusé / absent à l'ouverture : `mic_keep fail`, repli dictée comme aujourd'hui (conversation en pause si la
  dictée échoue aussi).
- Hors conversation (appui simple, mot d'éveil isolé) : micro à la session, rendu à la fin (comportement inchangé).
- WebSocket refusée : le micro gardé est rendu avant la dictée (jamais deux captures en même temps).
- Ticket d'avance échoué : on en redemande un à l'ouverture (chemin d'erreur existant).

## Critères d'acceptation
- [ ] Deux écoutes de suite en conversation : `getUserMedia` appelé une seule fois (`ConversationMic` réutilisé).
- [ ] Le son capté entre deux écoutes part en tête de la session, dans l'ordre, puis le son vivant ; l'audio gardé pour
  la retranscription commence par le tampon.
- [ ] Pendant que Jarvis parle et 150 ms après : tampon vide.
- [ ] À la pause, la veille, la sortie : micro rendu (pistes arrêtées), ticket d'avance rendu.
- [ ] Le ticket d'avance évite une demande à l'ouverture ; expiré, il est rendu et un neuf est demandé.
- [ ] Aucune régression des tests Jarvis.

## Contraintes de validation
Aucun champ saisi. Constantes : tampon 1 500 ms, anti-écho 150 ms, marge de validité du ticket 5 s.

## Plan de test
- Unitaires `jarvis-stream-listener.spec.ts` : anneau (capacité, ordre, vidage), `ConversationMic` (attache consomme,
  anti-écho, détachement vide, usure), `StreamingListener` sur micro gardé (tampon en tête, `audio()` commence par lui,
  `close()` ne coupe pas les pistes), `JarvisStreamOpener` (réutilisation, réouverture si usé, libération).
- Unitaires `jarvis.component.spec.ts` : `open` reçoit le micro gardé en conversation, pas hors conversation ; libération
  à la pause et avant dictée ; ticket d'avance consommé / rendu expiré / rendu à la pause ; `preroll` tracé.
- Intégration : aucun endpoint touché. Isolation : inchangée.

## Impacts
- Front : `jarvis-stream-listener.ts`, `jarvis.component.ts`. Back : aucun. Aucune table, aucune migration.

### Préoccupations transversales
- [ ] Auth / Principal — non. [ ] Contexte tenant — non. [ ] Plans / limites — non (ticket : même décompte, session
  rendue à 0 s). [ ] Navigation — non.

## Hors périmètre
- Détection de voix sur le micro partagé (SF-251-05), porte de parole (SF-251-03), délai de grâce (SF-251-04).

## Vérification après déploiement
```sql
SELECT event, split_part(detail, ' ', 1) AS d, count(*) FROM jarvis_listen_traces
WHERE event IN ('mic_keep', 'ticket_ready') AND created_at > now() - interval '2 days' GROUP BY 1, 2;
-- Tampon réellement envoyé (ms) : médiane et nombre.
SELECT count(*), percentile_cont(0.5) WITHIN GROUP (ORDER BY substring(detail from 'ms=(\d+)')::int) AS ms_med
FROM jarvis_listen_traces WHERE event = 'preroll' AND created_at > now() - interval '2 days';
```

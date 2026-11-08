# Mini-spec — F-251 / SF-251-03 — « Pardon, Monsieur ? »

## Statut
`in-review` — 2026-11-09 — branche `feat/SF-251-03-pardon-monsieur`

## Objectif
> Une vraie phrase retranscrite n'est plus jetée pour « pas de voix au micro », et quand Jarvis rejette une écoute où il
> a entendu quelqu'un, il le dit au lieu de se taire.

## Constat (cadrage §1, cause 3 — essai du 07/11, seq 66-69)
Une phrase réelle de 20 caractères rejetée `no_voice` (96 ms de voix mesurée, seuil 100) ; aucun signe pour le PO.

## Comportement nominal
1. **Porte de parole** : un texte rendu par la retranscription complète (énoncé complet ou dictée), de **2 mots ou
   plus**, n'est plus rejeté `no_voice`. `empty`, `noise`, `hallucination`, `implausible` inchangés ; le texte du seul
   flux reste soumis à `no_voice`.
2. **« Pardon, Monsieur ? »** : une écoute rejetée alors que le micro a entendu **au moins 400 ms** de voix, hors tour
   en cours : Jarvis le dit. Au plus une fois par 30 s, jamais deux fois de suite (une parole acceptée réarme).
3. Journal : `speech_rejected <motif> chars=<n> voiced=<ms ou -1>`, `pardon`.

## Cas d'erreur
- Niveau non mesuré (`voicedMs` nul) : pas de « Pardon », on ne sait pas si quelqu'un a parlé.
- Conversation expirée au moment du rejet : la veille passe avant, pas de « Pardon ».
- Un tour tourne : pas de « Pardon » (l'état de réflexion n'est pas écrasé).
- Confirmation en attente : chemin existant (`NO_ANSWER_PHRASE`) inchangé.

## Critères d'acceptation
- [ ] `speechGate('Vente chez le client.', { voicedMs: 96, transcribed: true })` rend `null` ; un seul mot reste `no_voice`.
- [ ] Une hallucination connue reste refusée même `transcribed`.
- [ ] Rejet avec 400 ms de voix ⇒ « Pardon, Monsieur ? » dit une fois, `pardon` tracé ; 399 ms ⇒ rien.
- [ ] Deux rejets de suite ⇒ un seul « Pardon » ; une parole acceptée puis un rejet après 30 s ⇒ un second.
- [ ] Retranscription complète de 2 mots avec 50 ms de voix : la demande part.

## Contraintes de validation
Aucun champ saisi. Constantes : 2 mots, 400 ms, 30 s.

## Plan de test
- Unitaires `jarvis-speech-gate.spec.ts` (règle `transcribed`), `jarvis.component.spec.ts` (bloc F-251 : seuil, espacement,
  réarmement, tour en cours, demande acceptée).
- Intégration : aucun endpoint. Isolation : inchangée.

## Impacts
Front : `jarvis-speech-gate.ts`, `jarvis-conversation.ts` (phrase), `jarvis.component.ts`. Back : aucun, aucune migration.

### Préoccupations transversales
- [ ] Auth — non. [ ] Tenant — non. [ ] Plans / limites — non. [ ] Navigation — non.

## Hors périmètre
Délai de grâce (SF-251-04), détection de voix (SF-251-05). Phrase fixe, aucune consigne ni modèle (bancs non requis, V8).

## Vérification après déploiement
```sql
SELECT split_part(detail, ' ', 1) AS motif, count(*) FROM jarvis_listen_traces
WHERE event = 'speech_rejected' AND created_at > now() - interval '2 days' GROUP BY 1 ORDER BY 2 DESC;
SELECT count(*) FROM jarvis_listen_traces WHERE event = 'pardon' AND created_at > now() - interval '2 days';
```

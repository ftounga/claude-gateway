# Mini-spec — F-251 / SF-251-05 — Se laisser couper

## Statut
`in-review` — 2026-11-09 — branche `feat/SF-251-05-se-laisser-couper`

## Objectif
> Le PO peut de nouveau couper Jarvis à la voix : la détection de voix démarre pour de bon en production, et tout échec
> dit désormais où et pourquoi.

## Constat (cadrage §1, cause 5 — essai du 07/11, seq 16)
`vad_fail TypeError` à la première parole, puis plus aucun essai sur la page.
**Cause prouvée** : le paquet `@ricky0123/vad-web` est en CommonJS ; dans le bundle de production (esbuild),
`import()` rend `{ default: { MicVAD… } }` et `MicVAD` déstructuré vaut `undefined` ⇒
`TypeError: Cannot read properties of undefined (reading 'new')`. Reproduit à l'identique avec esbuild et Chrome
(micro factice). Karma (webpack) rend un espace de noms complet : aucun test ne pouvait le voir.

## Comportement nominal
1. `MicVAD` lu à la racine **ou** sous `default` (`resolveMicVad`) ; absent ⇒ `TypeError` classé « chargement ».
2. **Micro de la conversation** : la détection reçoit un **clone** du flux gardé (SF-251-02), plus de second
   `getUserMedia` ; sa fin n'arrête que le clone. Sans micro gardé : `getUserMedia` comme avant.
3. **Échec non définitif** : 3 essais par page. Journal `vad_fail <étape> <nom> <message court> at <première ligne>`
   (`chargement|modele|micro|demarrage`), `vad_ok shared=0|1`.
4. Anti-écho du tampon : tenu aussi pendant la capture d'une coupure (la fin de la phrase qui a coupé n'est pas renvoyée).

## Cas d'erreur
- Le modèle ne se charge pas (réseau) : `vad_fail modele`, nouvel essai à la parole suivante, 3 fois au plus.
- Micro gardé fermé entre-temps : clone d'un flux mort impossible ⇒ `getUserMedia` (flux absent) ou échec tracé.

## Critères d'acceptation
- [ ] `resolveMicVad` lit les deux formes ; rien ⇒ `TypeError`.
- [ ] Un flux gardé est passé à la détection, `vad_ok shared=1` tracé.
- [ ] Échec : étape, nom, message et ligne au journal ; 3 essais au plus ; un succès remet le compteur à zéro.
- [ ] Bundle esbuild de production : `MicVAD` résolu, modèle chargé, détection démarrée (vérification manuelle faite).

## Contraintes de validation
Aucun champ saisi. Détail du journal : liste blanche, 120 caractères (`traceDetail`), jamais de parole (la détection ne
manipule que du son, D3).

## Plan de test
Unitaires `jarvis-barge-in.spec.ts` (`resolveMicVad`, `vadFailureDetail`), `jarvis-stream-listener.spec.ts` (`keptStream`),
`jarvis.component.spec.ts` (flux passé, essais bornés, compteur remis à zéro, anti-écho en capture).
Intégration : aucun endpoint. Isolation : inchangée.

## Impacts
Front : `jarvis-barge-in.ts`, `jarvis-stream-listener.ts`, `jarvis.component.ts`. Back : aucun, aucune migration.

### Préoccupations transversales
- [ ] Auth — non. [ ] Tenant — non. [ ] Plans / limites — non. [ ] Navigation — non.

## Hors périmètre
Réglage des seuils de la détection (inchangés). Coupures intempestives par la voix de Jarvis : à observer après
déploiement (`barge_cut`), la détection n'ayant jamais tourné en production.

## Vérification après déploiement
```sql
SELECT event, split_part(detail, ' ', 1) AS d, count(*) FROM jarvis_listen_traces
WHERE event IN ('vad_ok', 'vad_fail', 'barge_cut') AND created_at > now() - interval '2 days' GROUP BY 1, 2;
```

# Mini-spec — F-251 / SF-251-04 — Laisser finir

## Statut
`in-review` — 2026-11-09 — branche `feat/SF-251-04-laisser-finir`

## Objectif
> Le PO qui reprend sa phrase juste après une pause n'est plus coupé : après la fin de tour du fournisseur, Jarvis
> attend 0,7 s et continue la même demande si la parole reprend.

## Constat (cadrage §1, cause 4 — essai du 07/11, seq 173-188)
Le premier `committed_transcript` (1,2 s de silence) clôt la demande, sauf phrase « en suspens » : écoutes coupées en
deux. Le banc SF-239-10 interdit de baisser ce seuil ; il reste à 1,2 s.

## Comportement nominal
1. Un segment validé non vide, phrase non en suspens : **délai de grâce de 0,7 s** (`grace_start`) avant
   `finishListening`, sans rien changer à la fin de tour du fournisseur.
2. **La parole reprend** (texte provisoire non vide) pendant la grâce : grâce annulée (`grace_resume`), même demande ; le
   prochain segment validé rouvre une grâce.
3. **Silence** jusqu'au bout : `grace_end` puis `finishListening` (la « fin de tour confirmée » de F-252).
4. La phrase en suspens (`holdIfSuspended`, 1,5 s) et l'entretien gardent leur règle, sans grâce en plus.

## Cas d'erreur
- Écoute close pendant la grâce (pause, coupure, erreur de flux) : le minuteur est annulé, aucune double clôture.
- Fin par le filet du niveau micro ou l'écoute maximale : inchangée, annule la grâce à la fermeture du flux.

## Critères d'acceptation
- [ ] Segment validé : pas de clôture avant 700 ms ; clôture à 700 ms, `grace_start` et `grace_end` tracés.
- [ ] Parole reprise à 300 ms : pas de clôture à 700 ms, le texte final contient les deux segments, `grace_resume` tracé.
- [ ] Écoute coupée pendant la grâce : aucune clôture.
- [ ] Phrase en suspens : comportement SF-239-10 inchangé.
- [ ] La latence ajoutée ne dépasse jamais 700 ms (hors reprise de parole).

## Contraintes de validation
Aucun champ saisi. `JARVIS_GRACE_MS` : 700 ms (jeton d'injection, 0 en test unitaire hors bloc grâce).

## Plan de test
Unitaires `jarvis.component.spec.ts` (bloc F-251) : les 4 cas. Intégration : aucun endpoint. Isolation : inchangée.

## Impacts
Front : `jarvis-turn-end.ts` (jeton), `jarvis.component.ts`. Back : aucun, aucune migration.

### Préoccupations transversales
- [ ] Auth — non. [ ] Tenant — non. [ ] Plans / limites — non. [ ] Navigation — non.

## Hors périmètre
Seuil du fournisseur (1,2 s, inchangé), anticipation F-252, détection de voix (SF-251-05).

## Vérification après déploiement
```sql
SELECT event, count(*) FROM jarvis_listen_traces
WHERE event IN ('grace_start', 'grace_resume', 'grace_end') AND created_at > now() - interval '2 days' GROUP BY 1;
```

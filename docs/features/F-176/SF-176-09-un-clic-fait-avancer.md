# Mini-spec — F-176 / SF-176-09 — Un clic fait avancer

## Identifiant
`F-176 / SF-176-09` — rouverture du 2026-10-06 (D6), validée PO. Dépend de SF-176-07/08.
Branche : `feat/SF-176-09-un-clic-fait-avancer`. Statut : `in-progress`. Date : 2026-10-06.

## Objectif
Qu'un clic dans le parcours guidé enregistre la décision **et** relance l'agent, sans que le PO ait
à taper « go » (défaut G1 : 5 relances manuelles mesurées le 05/10).

## Comportement attendu

### Cas nominal
1. Le geste est enregistré par l'endpoint existant (inchangé) ; à son succès, **un tour démarre** par le
   chemin d'envoi normal du terminal, avec un message **visible dans le fil** :
   | Bouton | Message |
   |---|---|
   | [Passer en guidé et continuer] | « ✓ Mode guidé — investigation lancée. » |
   | [Planifier et continuer] | « ✓ Diagnostic confirmé — planification lancée. » |
   | [Continuer l'investigation] | « ↻ Investigation poursuivie. » |
   | [Valider le plan et lancer] | « ✓ Plan vN validé — exécution lancée. » |
   | [Valider l'amendement et reprendre] | « ✓ Amendement vN validé — exécution reprise. » |
   | [Clore le chantier] | « ✓ Chantier clos. » |
2. Variante secondaire **[Valider sans lancer]** : valide, ne démarre rien.
3. [Rester libre] et [Pas encore] ne relancent rien.
4. Un brouillon en cours **part avec** le message de reprise (rien n'est perdu).
5. Les boutons sont désactivés pendant un tour (`busy = geste en cours || tour en cours`).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| le geste échoue (400 version dépassée…) | rien ne démarre ; message d'échec existant |
| tour déjà en cours | rien ne démarre (boutons désactivés) |
| terminal en lecture seule (mosaïque) | rien ne démarre |
| plafond de terminaux vivants atteint | rien ne démarre (garde-fou F-70) |

## Contraintes de validation
Message de reprise : phrase fixe ≤ 60 caractères.

## Critères d'acceptation
- [ ] Cliquer [Valider le plan et lancer] démarre un tour sans rien taper ; le fil montre « ✓ Plan vN validé ».
- [ ] [Valider sans lancer] ne démarre pas de tour.
- [ ] Aucun démarrage pendant un tour, en lecture seule, au plafond, ou si le geste échoue.

## Plan de test
- **Front** : `terminal-journey-resume.spec.ts` (messages, reprise, brouillon conservé, sans lancer,
  pendant un tour / lecture seule, libellés).
- **Back** : aucun changement (endpoints existants, isolation déjà testée — Bob 404).
- Suites complètes front.

## Impacts
- Front : `journey.models.ts` (`journeyResumeMessage`), `terminal-journey-strip.component.ts`
  (libellés, [Valider sans lancer]), `atelier-terminal.component.ts/.html` (reprise, `busy`).
- Aucun endpoint, aucune table.

### Préoccupations transversales
- Auth / tenant / plans / navigation : **non**. Le plafond de terminaux vivants (F-70) est respecté.

## Hors périmètre
Indicateur « à qui la main » (SF-176-10) ; démarrage côté serveur sans écran ouvert.

## Arbitrages (réversibles)
- **La reprise passe par le chemin d'envoi du terminal** (le démarrage de tour existant) plutôt que par
  un nouvel endpoint qui démarrerait un tour côté serveur : même tour, mêmes garde-fous (plafond,
  mentions, précision F-84), aucun nouveau contrat ; le geste reste enregistré par la gateway d'abord.
- [Continuer l'investigation] et [Passer en guidé] relancent aussi (même règle « un clic = la suite »).

# Mini-spec — F-202 / SF-202-02 Plume écran : saisie multiligne conservée, rendu, jeu d'essai

## Identifiant
`F-202 / SF-202-02`

## Feature parente
`F-202` — Plume refondue (cadrage `CADRAGE-F-202-plume-refonte.md`)

## Statut
`in-progress` — 2026-10-16

## Branche Git
`feat/SF-202-02-plume-multiligne-front`

---

## Objectif
La demande enrichie (Markdown multiligne) ne perd jamais ses retours à la ligne : la saisie reste une zone multiligne tant que le texte en contient.

## Comportement attendu

### Cas nominal
- Après « ✨ Enrichir », la demande arrive dans la zone multiligne (inchangé).
- **Nouveau** : refermer le panneau Plume (×) ne remet plus un `<input>` d'une ligne si le brouillon contient un retour à la ligne : la zone multiligne reste (Entrée = nouvelle ligne, Ctrl/⌘ + Entrée = envoyer).
- Le brouillon redevient une ligne (envoi, « Revenir à mon texte » d'une ligne, saisie vidée) ⇒ retour au champ d'une ligne et à ses aides (@-mentions, slash-commandes).
- « Revenir à mon texte » inchangé.
- Le panneau d'analyse liste les sources réellement lues par SF-202-01 (passation, plan, fichiers touchés).
- Zone multiligne un peu plus haute (12 lignes) pour un prompt rédigé ; aucune couleur nouvelle (`DESIGN_SYSTEM.md`).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Échec serveur (502, dont « dépassait la longueur prévue ») | message du serveur affiché, brouillon intact (inchangé) |

## Critères d'acceptation
- [ ] `multilineDraft()` vrai si Plume enrichi **ou** brouillon contenant `\n`.
- [ ] Fermer le panneau avec un brouillon multiligne conserve la zone multiligne et le texte à l'identique.
- [ ] Brouillon d'une ligne ⇒ champ `<input>` d'origine.
- [ ] Aucune couleur / police hors charte.

## Plan de test
- Unitaires (`atelier-terminal` spec Plume) : `multilineDraft` (enrichi, `\n` sans Plume, une ligne) ; `closePlume` conserve le brouillon multiligne ; message 502 dédié relayé.
- Build `npm run build` + `npm test`.
- Jeu d'essai avant/après sur 5 phrases réelles : joint à la PR (`docs/features/F-202/JEU-ESSAI-F-202.md`).

## Impacts
- Composants : `AtelierTerminalComponent` (TS + HTML + SCSS Plume), `TerminalPlumeComponent` (`PLUME_STEPS`).
- Endpoints / tables : aucun changement.

## Préoccupations transversales
- Aucune (pas d'auth, tenant, limites ni routage touchés).

## Hors périmètre
- Rendu Markdown riche dans la saisie (le texte reste éditable brut) ; @-mentions dans la zone multiligne.

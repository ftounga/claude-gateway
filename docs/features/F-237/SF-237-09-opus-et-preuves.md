# Mini-spec — F-237 / SF-237-09 — Côté Opus et preuves : la ligne `🖥 montrer:` étendue, les preuves au fil de l'eau

## Identifiant
`F-237 / SF-237-09` · Feature parente `F-237` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-237-09-opus-et-preuves`
Cadrage : §6.3, §6.4, §8.4 (T-G7), §14 idée 6, D4. Dépend de SF-237-08 (`applyShow`, `ShowCommand`).

## Objectif
> Opus montre les pièces qu'il a dans son fil **sans outil ajouté** (ligne `🖥 montrer:` étendue, dans le message) ;
> les captures de preuve produites pendant un tour **arrivent dans la galerie au fil de l'eau** ; un chantier clos
> **sans preuve** déclenche la proposition de la générer, et un « oui » délègue `capturer_preuves` (outil existant).

## Comportement attendu
- `JarvisOralNote.TEXT` (message d'un tour né à la voix, jamais la consigne système) gagne une puce : `🖥 montrer:
  preuves chantier N`, `🖥 montrer: entrees chantier N`, `🖥 montrer: pieces <id>,<id>` (≤ 8 ids du fil) ; après
  `capturer_preuves`, terminer par `🖥 montrer: preuves chantier N`. Liste d'outils Opus **inchangée**.
- Écran : `parseShowLine` comprend ces formes (`isPieceShow`) ; `resolveShow` les ignore (pas l'univers) ; la ligne
  reste masquée (ni lue ni affichée) comme SF-234-13. Une demande d'Opus = `afficher {galerie|piece, raison demande}`
  appliqué par `JarvisPieceStore.applyShow` — une seule fois par ligne.
- **T-G7** : chaque événement SSE `proof` d'un tour lancé par Jarvis (délégation ou tour direct) devient une carte
  `proof:<hex>` (chemins passerelle : vignette `/jarvis/pieces/proof:<hex>/thumb`, contenu
  `/workspaces/{id}/proofs/{hex}`) ; elle **rejoint** la galerie ouverte du même sujet (nouvelle action `append` du
  réducteur), sinon ouvre « Preuves du chantier N ».
- **Chaîne §6.4** : la proposition et le « oui » passent par le cerveau rapide (consigne v7 de SF-237-08 : sans
  « oui », rien ; sur « oui », `deleguer`).
- **Preuve manquante à la clôture** : un chantier qui **sort** du tableau (univers relu) sur un sujet toujours présent
  est clos ; si l'index n'a **aucune** preuve pour lui, Jarvis dit « Le chantier N de X est clos sans preuve en
  image. Voulez-vous que je la génère, Monsieur ? » (file d'annonces, jamais pendant que le PO parle). Réponse dans
  les 2 min : « oui » net ⇒ délégation `capturer_preuves` (tour Opus normal, autorisation bash habituelle) ;
  « non » ⇒ « Entendu. » ; ambigu ⇒ la parole suit son chemin normal (aucune action).

### Cas d'erreur
- Poste hors ligne / runner sans `proof` : les messages existants du tour Opus sont dits tels quels.
- Index injoignable à la clôture : aucune proposition (jamais une affirmation non vérifiée).
- Mode présentation : aucune carte, aucune proposition.
- Identifiant de pièce inconnu dans la ligne : retiré (forme fermée) ; absent de l'index ⇒ « Je ne la trouve pas. ».

## Critères d'acceptation
- [ ] La consigne orale contient les formes pièces ; consigne système et outils Opus **inchangés** (test).
- [ ] `🖥 montrer: preuves chantier 3` ⇒ galerie des preuves du chantier 3 ; jamais résolue sur l'univers.
- [ ] Un événement `proof` ⇒ une carte de plus dans la galerie ouverte (sans doublon, plafonnée).
- [ ] Chantier clos sans preuve ⇒ proposition ; aucune délégation sans « oui ».

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Préfixe de cache | **Oui** | `JarvisOralNote` (message) ; `AtelierChatServiceVoiceTurnTest` vérifie consigne système et outils identiques au tour tapé |
| Contexte tenant | Non | aucune lecture nouvelle côté passerelle (index SF-237-01 déjà isolé) |

## Plan de test
- Back : `AtelierChatServiceVoiceTurnTest` (formes présentes, préfixe intact).
- Front pur : `jarvis-piece-live.spec.ts` (analyse de la ligne, ordre d'écran, carte de preuve, `append`, `addLive`,
  détection de clôture, phrase et demande fixes).

## Hors périmètre
Clignotement visuel d'une carte « preuve vide » (arbitrage : proposition orale seulement) ; diaporama (10).

# Mini-spec — F-39 / SF-39-18 — Regarder ses fichiers sans tuer le tour

## Identifiant

`F-39 / SF-39-18`

## Feature parente

`F-39` — L'Atelier comme harnais

## Statut

`done` — livrée le 2026-09-06 (PR #246, correctif de cycle d'import PR #250)

## Date de création

2026-09-06

## Branche Git

`feat/SF-39-18-explorateur-sans-quitter-le-terminal`

---

## Objectif

> Faire que consulter ses fichiers pendant qu'un tour travaille ne détruise plus le terminal — donc
> ne condamne plus le tour.

---

## Déclencheur

**Banc d'essai, cinquième défaut — et le plus grave, parce qu'il se déclenche par un geste normal.**

L'explorateur vit sur une route séparée (`/atelier/:id/fichiers`). Y naviguer **détruit**
`AtelierComponent`, et avec lui le flux SSE du tour en cours. La suite est mécanique :

1. Le tour continue côté serveur — c'est voulu, « un client parti n'arrête pas le travail ».
2. L'agent demande l'autorisation d'exécuter une commande.
3. La demande part dans un flux **qui n'existe plus** : personne ne la voit.
4. Au bout de **120 s**, la porte tranche seule (`Decision.TIMEOUT`) : **refus**.
5. Toutes les commandes suivantes subissent le même sort.

Et revenir au terminal ne rattache rien : il n'existe pas de reprise de flux. Le tour est perdu de
vue définitivement, alors qu'il a peut-être encore des minutes de travail devant lui.

L'utilisateur a formulé le symptôme avant qu'on en trouve la cause : *« si pendant que le terminal
travaille je bascule sur l'explorateur de fichiers, il s'arrête en fait ? »*

---

## L'option retenue, et les deux écartées

| Option | Pourquoi non / oui |
|---|---|
| Avertir avant de quitter | Honnête, mais on interdirait de regarder ses fichiers pendant que l'agent travaille — c'est refuser le geste au lieu de le rendre sûr |
| Reprendre le flux au retour (`Last-Event-ID`) | La solution générale, et un chantier à part entière ; elle traiterait aussi la coupure réseau, mais pas aujourd'hui |
| **Ne pas détruire le terminal** | **Retenue** : l'explorateur devient un panneau **dans** la vue. Le flux survit, la porte reste atteignable, le geste redevient anodin |

La troisième traite la cause plutôt que le symptôme, et prolonge ce que le lot 4 a commencé : un seul
écran, dont on ne sort pas.

---

## Comportement attendu

### Cas nominal

1. Depuis le terminal, ouvrir l'explorateur **ne change pas de route** : il s'affiche par-dessus, en
   plein écran, et le terminal reste monté derrière.
2. Le tour continue de défiler — les demandes d'autorisation **arrivent toujours**, et restent
   traitables sans fermer l'explorateur.
3. Le fermer rend la main au terminal, exactement là où il en était.
4. L'URL suit (`?vue=fichiers`), pour que la page reste partageable et le bouton « précédent »
   utile — mais un changement de **paramètre de requête** ne détruit aucun composant, à la
   différence d'un changement de route.

### Compatibilité

La route `/atelier/:id/fichiers` **reste** : elle sert les liens existants, les favoris et l'ouverture
directe du fichier d'instructions (F-34 / SF-34-02). Dans ce cas, aucun terminal n'est monté — il n'y
a donc rien à perdre, et le comportement est inchangé.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Un tour est en cours et une autorisation est demandée | Elle s'affiche **par-dessus** l'explorateur ; répondre ne le ferme pas |
| Aucun projet actif | Le bouton d'explorateur reste inopérant, comme aujourd'hui |
| Modification non publiée en attente dans l'explorateur | La garde de sortie existante (SF-31-09) s'applique à la **fermeture du panneau** |
| Ouverture par URL directe `/fichiers` | Comportement actuel, inchangé |

---

## Critères d'acceptation

- [ ] Ouvrir l'explorateur depuis le terminal **ne détruit pas** `AtelierComponent`.
- [ ] Le flux SSE d'un tour en cours **survit** à l'ouverture et à la fermeture de l'explorateur.
- [ ] Une demande d'autorisation reste visible et traitable pendant que l'explorateur est ouvert.
- [ ] L'URL porte `?vue=fichiers`, et le bouton « précédent » referme le panneau.
- [ ] La route `/atelier/:id/fichiers` continue de fonctionner à l'identique.
- [ ] La garde de sortie sur modifications non publiées s'applique à la fermeture du panneau.
- [ ] Aucune régression sur les acquis F-30 : le terminal reste immersif, sa transcription intacte.

---

## Périmètre

### Hors scope

- La reprise d'un flux SSE interrompu (`Last-Event-ID`) : elle reste souhaitable pour les coupures
  réseau, et ne relève pas de ce correctif.
- Toute refonte de l'explorateur lui-même : il est réutilisé **tel quel**, seul son mode d'affichage
  change.

---

## Technique

### Composants impactés

| Composant | Changement |
|---|---|
| `atelier-files.component` | Entrée `embedded` et sortie `closed` : en mode panneau, il n'appelle plus le routeur pour revenir, il émet |
| `atelier.component` | Signal d'ouverture, affichage en surcouche, synchronisation avec `?vue=fichiers` |
| `app.routes.ts` | Inchangé — la route dédiée reste |

### Migration Liquibase

- [x] **Non applicable** — aucun changement serveur.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | Aucun accès aux données modifié |
| Plans / limites | Non | — |
| **Navigation / routing** | **Oui** | Chemins revérifiés : terminal → explorateur (panneau désormais), explorateur → terminal (SF-30-10), ouverture directe par URL, ouverture sur le fichier d'instructions (SF-34-02), bouton « précédent », et rechargement avec `?vue=fichiers`. |

---

## Plan de test

### Tests frontend

- [ ] Ouvrir l'explorateur depuis le terminal ne déclenche **aucune** navigation de route.
- [ ] Le panneau s'ouvre et se ferme ; l'URL gagne puis perd `?vue=fichiers`.
- [ ] Un tour en cours (flux simulé) continue de recevoir ses événements pendant l'ouverture.
- [ ] Une demande d'autorisation reste affichée par-dessus le panneau.
- [ ] En mode `embedded`, l'explorateur émet `closed` au lieu de naviguer.
- [ ] Hors mode `embedded` (route dédiée), le comportement est inchangé.

### Isolation workspace

- [x] Non applicable — aucun accès aux données n'est touché.

---

## Notes et décisions

**D1 — Un paramètre de requête, pas un segment de route.** C'est le cœur du correctif : Angular
détruit un composant quand la **route** change, pas quand un **paramètre de requête** change. Porter
l'état dans l'URL reste souhaitable — page partageable, bouton « précédent » utile — mais pas au prix
d'un composant détruit.

**D2 — L'explorateur est réutilisé tel quel.** Il porte l'édition, la publication Git, la garde de
sortie : le réécrire pour l'imbriquer serait rouvrir tout ce qui a été réglé en F-31. Une entrée et
une sortie suffisent.

**D3 — La route dédiée survit.** Des liens existent — favoris, fichier d'instructions (SF-34-02),
retour depuis l'explorateur (SF-30-10). Les casser pour corriger un autre défaut serait un mauvais
échange, et le cas « ouverture directe » ne perd rien, puisqu'aucun terminal n'y est monté.

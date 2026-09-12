# Mini-spec — F-83 / SF-83-03 — Agrandir une tuile, sans rouvrir son flux

## Identifiant

`F-83 / SF-83-03`

## Feature parente

`F-83` — La mosaïque : quatre terminaux en même temps

## Statut

`in-progress`

## Date de création

2026-09-12

## Branche Git

`feat/SF-83-03-agrandir-une-tuile`

---

## Objectif

Un clic **agrandit** une tuile à toute la mosaïque et la **rend** à la mosaïque — sans rouvrir le
flux, sans perdre une ligne, sans toucher aux trois autres lectures.

---

## Comportement attendu

### Cas nominal

1. L'en-tête d'une tuile porte un bouton qui occupe toute sa largeur utile : un clic **agrandit**.
2. La tuile agrandie occupe **toute la grille** ; les autres sont masquées, et **elles seules** le
   sont : leurs lectures restent ouvertes, leurs terminaux restent montés.
3. Un second clic — ou la touche **Échap** — la **rend à la mosaïque**, à sa place, telle qu'on
   l'avait laissée.
4. **Le flux n'est pas rouvert au passage**, et c'est le point : agrandir est un changement de
   *mise en page*, jamais de branchement. Aucun `attach` supplémentaire n'est émis, et le terminal
   agrandi est **le même élément du document** qu'avant — donc le même défilement, le même contenu.
5. Un terminal qui **disparaît** du registre pendant qu'il est agrandi rend la mosaïque : on ne
   garde pas un agrandissement sur une tuile qui n'existe plus.
6. **Ce qui attend une décision reste visible même agrandi** : le compte écrit de l'en-tête de page
   ne bouge pas, et il compte **toutes** les tuiles — y compris celles que l'agrandissement masque.
   Sans quoi agrandir reviendrait à se rendre aveugle, ce que F-76 puis F-83 refusent.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Clic sur « Entrer » d'une tuile | On **entre dans le terminal** ; aucun agrandissement déclenché | — |
| Échap alors qu'aucune tuile n'est agrandie | Rien. Le geste n'a rien à annuler | — |
| La tuile agrandie quitte le registre | La mosaïque revient d'elle-même | — |
| Une seule tuile à l'écran | Le bouton reste actif et sans effet visible : la tuile occupe déjà tout | — |

---

## Critères d'acceptation

- [ ] Un clic sur l'en-tête d'une tuile l'agrandit ; un second la réduit.
- [ ] **Échap** réduit la tuile agrandie.
- [ ] Agrandir puis réduire **n'émet aucun `attach` supplémentaire** : le nombre d'appels réseau est
      **identique** avant et après. *(Le critère du cadrage : « le flux n'est pas rouvert ».)*
- [ ] L'élément `app-atelier-terminal` de la tuile agrandie est **le même nœud** avant, pendant et
      après l'agrandissement.
- [ ] Les autres tuiles sont **masquées**, jamais détruites : leurs terminaux restent dans le
      document.
- [ ] Le compte écrit des attentes en en-tête **inclut les tuiles masquées**.
- [ ] Le bouton porte un **libellé accessible** qui dit l'état (`aria-pressed`), jamais une icône
      seule.
- [ ] Une tuile agrandie qui quitte le registre ramène la mosaïque.
- [ ] Suite frontend verte, `ng build` vert.

---

## Périmètre

### Hors scope (explicite)

- Écrire depuis une tuile, agrandie ou non.
- Mémoriser l'agrandissement d'une session à l'autre : c'est un geste, pas un réglage.
- Le plein écran du navigateur (`requestFullscreen`) — l'agrandissement répond au besoin sans
  sortir de l'application, et le fil d'Ariane doit rester atteignable.
- Changer le plafond de quatre, retirer les aperçus de F-76.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|---|---|---|
| `zoomed` | `null` | On arrive sur la **mosaïque** : c'est l'écran demandé, l'agrandissement est un second geste |

---

## Contraintes de validation

| Champ | Obligatoire | Format | Normalisation |
|---|---|---|---|
| `zoomed` | Non | identifiant de projet **venu du registre**, ou `null` | Remis à `null` dès que ce projet quitte le registre |

Aucun champ saisi : cet écran n'a toujours aucune entrée.

---

## Technique

### Endpoint(s)

Aucun. **Aucun appel réseau ajouté** — c'est même ce que le test vérifie.

### Tables impactées

Aucune. Aucune migration.

### Composants Angular

- `MosaiqueComponent` — signal `zoomed`, `toggleZoom()`, `Échap`, et la règle CSS qui masque les
  autres tuiles **sans** les retirer du document.

---

## Plan de test

### Tests unitaires

- [ ] `mosaique.component.spec` — un clic agrandit, un second réduit.
- [ ] `mosaique.component.spec` — **Échap** réduit.
- [ ] `mosaique.component.spec` — **aucun appel réseau de plus** après agrandir + réduire.
- [ ] `mosaique.component.spec` — **le même nœud** `app-atelier-terminal` avant / pendant / après.
- [ ] `mosaique.component.spec` — les autres tuiles restent **dans le document**, masquées.
- [ ] `mosaique.component.spec` — le compte des attentes inclut une tuile masquée.
- [ ] `mosaique.component.spec` — « Entrer » n'agrandit pas.
- [ ] `mosaique.component.spec` — la tuile agrandie qui quitte le registre rend la mosaïque.
- [ ] `mosaique.component.spec` — le bouton dit l'état (`aria-pressed`) et porte un libellé.

### Tests d'intégration

Sans objet : aucun endpoint.

### Isolation utilisateur

- [x] Non applicable — aucun accès aux données ; l'agrandissement est une affaire de mise en page.

---

## Dépendances

### Subfeatures bloquantes

- `SF-83-02` — statut : `done`

### Questions ouvertes impactées

Aucune.

---

## Préoccupations transversales

| Préoccupation | Composants vérifiés un par un | Verdict |
|---|---|---|
| **Plans / limites (plafond F-70)** | `LiveTerminalService` (front) toujours non injecté ; `live_terminals` toujours intouchée ; 409 `terminal_limit_reached` inchangé ; `app-live-badge` toujours absent de la tuile ; en-tête « n / 4 » toujours affiché, **y compris agrandi**. Le test « aucun appel réseau de plus » couvre aussi ce point : agrandir ne prend rien | intact |
| **Navigation / routing** | Aucune route ajoutée ni modifiée. L'agrandissement **ne change pas l'URL** : c'est un état d'écran, et une adresse qui porterait un agrandissement rouvrirait la page — donc les flux — au retour arrière | traité |
| **Auth / Principal** | Aucun changement | traité |
| **Contexte tenant** | Aucun accès aux données | traité |

---

## Notes et décisions

- **Masquer, ne pas filtrer.** Réduire la liste à une tuile détruirait les trois autres composants
  de terminal ; leurs lectures survivraient (elles vivent dans la page, pas dans la tuile), mais on
  perdrait leur défilement et leur contenu affiché, et l'on rouvrirait tout au retour. Le CSS
  masque, le document garde. **Réversible** : une règle de style.
- **Le clic est pris sur l'en-tête, pas sur le flux.** Un clic dans le flux sert à **sélectionner du
  texte** — c'est un terminal. Détourner ce clic pour agrandir rendrait la sortie incopiable, ce qui
  est exactement ce qu'on vient regarder.
- **Échap plutôt qu'un second bouton flottant** : le geste standard pour « revenir », et il ne coûte
  aucun pixel — l'écran en compte chaque ligne.

# Mini-spec — [F-125 / SF-125-03] Un fichier de carte non déclaré ne bloque plus

## Identifiant

`F-125 / SF-125-03`

## Feature parente

`F-125` — La tenue de la carte du poste : silencieuse, robuste, jamais dans la réponse

## Statut

`ready`

## Date de création

2026-09-17

## Branche Git

`feat/SF-125-03-carte-non-declaree`

---

## Objectif

> En une phrase : un fichier de carte présent à la racine mais **non déclaré** (ni apporté par un
> paquet, ni référencé par l'index `README.md`) est **signalé et toléré** (avertissement, jamais une
> erreur) — il ne met plus l'agent « hors gouvernance » ni en boucle.

---

## Comportement attendu

### Cas nominal

1. **Détection** : l'inspection d'intégrité (`IntegriteInspection`) liste la racine du poste (via
   `GovernanceHostFiles.listRoot`, qui passe par le runner comme le reste des lectures de racine).
   Un fichier **à la racine**, en `.md`, qui n'est **ni** un fichier de carte attendu (apporté par un
   paquet actif) **ni** référencé par l'index `README.md`, est un fichier **non déclaré**.
2. **Réparateur, pas bloquant** : chaque fichier non déclaré donne un constat
   `CARTE_NON_DECLAREE` de **niveau AVERTISSEMENT** — il informe (« présent mais non déclaré : ajoute
   une ligne dans `README.md`, ou laisse-le, il est toléré ») et **ne bloque jamais** la fin du tour
   (`IntegriteRapport.bloque()` ne regarde que les erreurs). `IntegritePosteControl` reste inchangé :
   il ne bloque toujours que sur les erreurs, donc un fichier non déclaré ne renvoie plus l'agent au
   travail.
3. **Borné** : au plus quelques fichiers cités (constante), le reste **annoncé** — comme les autres
   constats.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| La racine ne peut pas être listée (machine muette, refus, listage vide) | Aucun constat non déclaré — on ne conclut rien de ce qu'on n'a pas lu |
| Listage **tronqué** (proche de la borne du runner) | Aucun constat — conclure « non déclaré » d'une liste incomplète fabriquerait une fausse alerte |
| Le fichier est référencé par `README.md` (déclaré « en douceur ») | Pas de constat : il est déclaré |
| Le fichier est apporté par un paquet actif (`attendus`) | Pas de constat : il est déclaré |
| Un `.md` sous un sous-dossier de projet (chemin avec `/`) | Ignoré : la règle porte sur la **racine** du poste |

---

## Critères d'acceptation

- [ ] Un `.md` présent à la racine, non attendu et non référencé par `README.md`, produit un
      constat `CARTE_NON_DECLAREE` de niveau **AVERTISSEMENT**.
- [ ] Ce constat **ne bloque pas** (`rapport.bloque()` reste `false` s'il n'y a que lui).
- [ ] Un `.md` référencé par `README.md` **ne** produit **pas** de constat.
- [ ] Un fichier de carte attendu (paquet actif) ne produit pas de constat.
- [ ] Une racine non listable ou un listage tronqué ne produit **aucun** constat non déclaré.
- [ ] `IntegritePosteControl` ne bloque toujours que sur les erreurs (non-régression).

## Plan de test minimal

- **Unitaires** :
  - `IntegriteInspectionTest` : racine avec un `enjeux.md` non déclaré → un AVERTISSEMENT
    `CARTE_NON_DECLAREE`, `bloque()` faux ; `enjeux.md` référencé par README → pas de constat ; racine
    non listable → pas de constat ; listage tronqué → pas de constat ; `.md` sous un projet → ignoré.
  - `GovernanceHostFilesTest` : `listRoot` rend les entrées de racine, vide quand la machine ne répond
    pas / listage tronqué / poste hébergé.
  - `IntegritePosteControlTest` : un rapport ne portant que des avertissements ne bloque jamais
    (déjà couvert ; on ajoute un cas avec `CARTE_NON_DECLAREE`).
- **Isolation utilisateur** : `listRoot` reçoit un `GovernanceHostRef` déjà vérifié possédé
  (`GovernanceHostScope`) ; le `userId` ne sert qu'à signer l'audit. Aucun nouvel accès transverse.

## Tables / endpoints / composants impactés

- **Backend** : `GovernanceHostFiles` (nouvelle méthode `listRoot`), `IntegriteInspection` (nouvelle
  passe `passeCarteNonDeclaree`), `IntegriteRegle` (`CARTE_NON_DECLAREE`, AVERTISSEMENT),
  `IntegriteConstat` (fabrique `carteNonDeclaree`). Aucune table, aucune migration, aucun endpoint,
  aucun frontend.

## Analyse transversale (préoccupations)

- **Auth / Principal** : non concernée.
- **Contexte tenant** : `listRoot` agit sur le poste déjà résolu et possédé (`GovernanceHostScope`) ;
  l'inspection lit toujours par `user_id` + `host_id`. Aucun nouveau résolveur de tenant.
- **Plans / limites** : non concernée.
- **Navigation / routing** : non concernée.

## Hors périmètre (explicite)

- **Écrire** automatiquement la déclaration dans `README.md` (auto-déclaration active) : l'inspection
  **ne modifie jamais la machine** (contrat F-95, règle async) ; elle **signale** le geste, elle ne
  l'exécute pas. Décision par défaut, tracée dans la PR.
- Le desserrage de la dette de promotion → **SF-125-04**.
- La consigne « zéro plomberie » (déjà livrée SF-125-01) et la robustesse du marqueur (SF-125-02).

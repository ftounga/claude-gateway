# Mini-spec — [F-172 / SF-172-06] Bascule et mesure

## Identifiant

`F-172 / SF-172-06`

## Feature parente

`F-172` — Opus 5.5 dans la Forge (D2, D3 validées par le PO le 2026-10-02)

## Statut

`blocked` — action d'exploitation en production, en attente du feu vert du PO (hors vague autonome : la vague ne déploie pas et ne touche pas la configuration de production).

## Date de création

2026-10-02

## Branche Git

Aucune : pas de code. La bascule est un réglage d'environnement (D2).

---

## Objectif

Passer la Forge sur Opus 5.5 par configuration (`APP_ATELIER_MODEL=claude-opus-5-5`), puis mesurer une semaine pour décider : garder, ou revenir à Opus 5 en une commande (D3).

---

## Comportement attendu

### Cas nominal (déroulé)

1. **Prérequis** : SF-172-01 → 05 mergées **et déployées** (le déploiement unique de fin de vague).
2. **Contrôle avant bascule** : aucun tour d'agent en cours (lecture de `runner_audit` récent), comme pour tout déploiement — la bascule redémarre les pods et tue les tours en cours.
3. **Bascule** : `APP_ATELIER_MODEL=claude-opus-5-5` dans la configuration du déploiement `backend` (ns `claude-gateway-staging`), redémarrage progressif.
4. **Mesure, une semaine**, comparée aux deux semaines Opus 5 précédentes (cadrage §6) :

| Mesure | Source | Attendu | Retour arrière si |
|---|---|---|---|
| Coût par tour | `usage_turns` par `model` | −20 à −30 % | Hausse |
| Tours coupés au plafond de sortie | journal « réponse coupée au plafond de sortie » | ≤ Opus 5 | Hausse nette |
| Refus et replis, par catégorie | journaux « Tour d'agent refusé », « Repli côté serveur » | Rares, rattrapés ou affichés | Refus non rattrapés récurrents sur le travail infra |
| Blocs de raisonnement perdus | journal `thinking_dropped=` (SF-172-05) | Faible | Perte systématique à chaque tour |
| Relances « creuse / vérifie / c'est faux » | `atelier_messages` | Baisse modérée | Hausse |

5. **Décision** consignée dans `PRODUCT_SPEC.md` (historique) : garder, ou retour arrière.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Tour d'agent en cours au moment prévu | Attendre ou prévenir le PO ; ne pas basculer |
| Critère de retour arrière atteint | `APP_ATELIER_MODEL=claude-opus-5` (même contrôle `runner_audit` avant) |

---

## Critères d'acceptation

- [ ] Bascule faite après contrôle `runner_audit`, sans tour tué.
- [ ] Une semaine de mesure, tableau rempli.
- [ ] Décision écrite dans l'historique de `PRODUCT_SPEC.md`.

## Périmètre

### Hors scope

Sélecteur de modèle (D2), exploration vers Sonnet 5.5 (D4), chat passerelle, historique « append-only » (seulement si la mesure D5 le justifie).

## Technique

Aucune table, aucun endpoint, aucun composant. Préoccupations transversales : aucune.

## Plan de test

Non applicable (exploitation) ; la mesure tient lieu de vérification.

## Dépendances

SF-172-01 → 05 déployées.

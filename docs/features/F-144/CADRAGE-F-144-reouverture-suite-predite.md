# Cadrage — F-144 rouverte : la suite prédite, comme Claude Code

> Cadrage PO le 2026-10-04 (« je t'avais demandé que le terminal propose, comme Claude Code, le
> message suivant ; je me rends compte que ça n'a jamais été implémenté » → « oui go »).
> Source de vérité produit : `docs/PROJECT.md`. Subordonné à `CLAUDE.md`.

## 1. Pourquoi rouvrir

F-144 / SF-144-01 (PR #778, 2026-09-22) a livré une version **sans appel au modèle** : jusqu'à
trois puces fixes sous la zone de saisie, dans quatre cas seulement (étape de plan ouverte, tour
interrompu, plafond atteint, fichiers modifiés). La feature notait elle-même que « l'appel modèle
reste une variante ouverte si le résultat se révèle trop pauvre ». **Il l'est** : sur un tour
ordinaire, rien n'apparaît, et quand une puce apparaît c'est une phrase toute faite. Le PO ne la
reconnaît pas comme la fonctionnalité demandée.

Claude Code, lui, **prédit le prochain message** à partir de la conversation et l'affiche en
**texte grisé dans la zone de saisie** ; Tab l'accepte, l'utilisateur corrige ou envoie.

## 2. Objectif

Après chaque tour terminé du terminal, proposer **une** suite contextuelle, prédite par un petit
modèle, affichée en texte fantôme dans la zone de saisie, acceptée d'une touche, jamais envoyée
sans geste.

## 3. Décisions (prises par défaut, réversibles, tracées)

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **Appel séparé** au modèle rapide existant (`ANTHROPIC_FAST_MODEL`, défaut `claude-haiku-4-5`), via l'interface `AIProvider`, **hors de la boucle de l'agent** : il ne touche ni le préfixe de cache, ni l'historique, ni le raisonnement du tour. | Justesse avant coût : le raisonnement de l'agent est strictement inchangé. Provider Independence. |
| D2 | Entrée bornée : la dernière demande de l'utilisateur (≤ 2 000 car.), le texte final de la réponse (≤ 4 000 car., la fin si plus long), le titre du terminal, l'état du relevé (plan ouvert, interruption, plafond, fichiers modifiés). Sortie : **une** phrase ≤ 200 caractères, dans la langue de l'utilisateur, à la première personne de l'utilisateur, ou rien si aucune suite évidente. | Coût ≈ 0,3 centime par tour ; latence courte. |
| D3 | Déclenchement **par l'écran à la fin du tour** (événement de fin de flux) : `POST /api/atelier/terminals/{id}/next-prompt` (filtre `user_id`, 404 si terminal d'un autre compte). Réponse mémorisée par dernier message assistant (pas de double appel au rechargement ; cache mémoire ou colonne, au choix du dev). | Un tour peut finir sans écran ouvert (le tour vit dans le flux) : ne rien payer pour personne. |
| D4 | **Affichage façon Claude Code** : texte fantôme grisé dans la zone de saisie vide ; **Tab** ou **→** (curseur en fin) l'accepte dans le champ ; Échap ou toute frappe l'efface ; **Entrée sur un champ vide n'envoie jamais la suggestion**. Sur mobile (pas de Tab) : une puce unique « Suggestion : … » qu'un toucher place dans le champ. | Règle produit : rien ne part sans geste. |
| D5 | Les puces F-144 / SF-144-01 restent le **repli** quand l'appel échoue, est coupé, ou ne rend rien. Une seule suggestion visible à la fois (la prédite a priorité). | Rien n'est retiré. |
| D6 | Coupe-circuit `APP_ATELIER_NEXT_PROMPT_ENABLED` (défaut `true`). Clé : BYOK du propriétaire si présente, sinon plateforme (motif Radar / F-174). Jetons comptés dans le journal d'usage annexe existant (pas sur le quota du tour). | Réversible sans redéploiement ; coût mesurable. |
| D7 | Rien n'est stocké du contenu au-delà de la suggestion elle-même ; pas de journalisation du texte. | Données client. |

## 4. Découpage

| SF | Titre | Contenu |
|---|---|---|
| SF-144-02 | La suite prédite | Backend D1-D3, D6, D7 (endpoint, appel `AIProvider`, bornes, mémorisation, coupe-circuit, tests d'isolation `user_id`) + frontend D4-D5 (texte fantôme, Tab/→/Échap, repli mobile, repli puces SF-144-01). Backend avant frontend dans la même SF si < 2 jours, sinon scinder en SF-144-02 (back) / SF-144-03 (front). |

## 5. Hors périmètre

- Plusieurs suggestions au choix, ou un historique de suggestions.
- Suggestion pendant la frappe (autocomplétion de phrase).
- Le chat passerelle hors terminal.

## 6. Préoccupations transversales

- Plans / limites : l'appel annexe ne doit pas consommer le quota de tours ni déclencher le
  plafond de consommation (lister les gates dans la mini-spec).
- Composer du terminal : ne pas casser les mentions `@`, les slash-commands (SF-121-23), le
  brouillon pré-rempli par « Demander à la Forge » (F-173 SF-07, état `radarDraft`) ni la dictée.

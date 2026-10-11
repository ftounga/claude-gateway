# Cadrage F-193 — Plume : « Enrichir » la demande

**Demande PO du 2026-10-11** (vidéo « mods », mod « Plume »). Précision PO : *« pourquoi seulement pour une dictée ? Je saisis une petite phrase, je clique sur un bouton, tu lances une petite analyse en fonction du contexte pour mettre en place un prompt bien plus détaillé. À moi de relire, corriger, apporter des détails. »*

## Existant

Dictée F-145 (`shared/dictation/`), textes collés F-146. **Aucune** réécriture de demande nulle part.

## Cible (maquette validée : `design/maquette-cap-plume.html`, partie Plume)

- Bouton **« ✨ Enrichir »** **toujours présent** dans la barre de la saisie du terminal (grisé si la saisie est vide).
- Au clic : un panneau d'analyse montre ce qui est lu (fil récent du sujet, `STATE.md`, carte du projet, attentes ouvertes), puis **la saisie est remplacée** par une demande structurée : **Objectif · Contexte · Fichiers concernés · Contraintes · Critère de fin**. Le texte d'origine reste rappelé en tête (« Votre texte : … »). Ce que le modèle **suppose** est marqué **« à confirmer »** (surligné ambre).
- Bouton **« Revenir à mon texte »** (restaure exactement l'original). Badge « enrichi par Plume ». **Rien n'est envoyé** sans geste ; la demande reste éditable.
- Backend : route `POST /api/workspaces/{id}/enrich-prompt` (isolation `user_id`, 404 sur le terminal d'autrui), appel **dédié** via l'interface `AIProvider` (Provider Independence), consigne : **détailler sans inventer** (aucun fichier, valeur ou contrainte absent du contexte fourni ; sinon « à confirmer »), modèle configurable (`app.atelier.enrich.model`, défaut = modèle principal en effort bas — justesse avant coût), jetons comptés au journal d'usage (comme F-144-02), coupe-circuit `APP_ATELIER_ENRICH_ENABLED`.
- Contexte fourni borné (derniers échanges, `STATE.md`, sommaire de carte) pour maîtriser le coût.

## Découpage

- **SF-193-01** — Backend : route d'enrichissement, assemblage du contexte borné, consigne, usage, tests (isolation, saisie vide 400, coupe-circuit).
- **SF-193-02** — Frontend : bouton, panneau d'analyse, rendu structuré, « à confirmer », « Revenir à mon texte », mobile.

## Hors périmètre

Enrichissement automatique ; enrichir dans le chat simple hors terminal.

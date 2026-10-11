# Cadrage F-191 — Le fil des tâches

**Demande PO du 2026-10-11** : étude de la vidéo « Claude Code vient de lancer les mods », mod « Fil ». Décision PO : intégré au produit, **actif par défaut**, pas un système de mods à activer.

## Problème

Depuis le terminal d'un sujet ou de la racine du poste, **on ne voit rien de ce qui tourne ailleurs**. La seule vue transverse est la mosaïque « Voir travailler » (F-83, `frontend/src/app/mosaique/`), une **page à part**. Le rail de la Forge n'indique pas qu'un sujet travaille. Aucune barre de progression : le plan (F-39 / SF-39-13) est une liste dans le flux, la ligne vivante (F-30) donne l'action, les étapes, les tokens et le temps, sans pourcentage. **Aucun son** de fin (`tab-alert.service.ts` ne fait que le titre et le favicon).

## Cible (maquette validée : `design/maquette-fil.html`)

Une **bande au-dessus de la saisie** de tout terminal (`atelier-terminal.component.html`, juste au-dessus du formulaire de saisie, sous les bandes existantes F-176/F-175/F-185).

- **Portée : le poste** (tous les sujets + la racine d'un même poste). Jamais les autres postes. Isolation `user_id` obligatoire.
- **Repliée par défaut** sur une ligne : pastilles « N en cours », « N attend votre accord » (ambre §12), « ✓ N terminée(s) » + mini-barres. **Absente quand rien ne tourne ni n'a fini depuis 10 min.**
- **Dépliée** : une ligne par tâche — sujet (« ici » marqué d'un filet or), demande (tronquée), **barre de progression** = étapes du plan faites / prévues, barre animée si pas de plan, outil courant (« Lecture », « Commande », « Écriture »…), durée ; **sous-agents** listés sous leur tâche (nom, état, durée).
- **Ordre** : attend une décision → en cours (du plus ancien) → terminé (✓ vert, 10 min puis disparaît).
- **Un clic** ouvre le sujet. Pas d'« Interrompre » depuis la bande.
- **Fin d'une tâche d'un autre sujet** : bandeau discret « ✓ <sujet> · « <demande> » est terminé · durée · Ouvrir ».
- **Son de fin** : option dans les préférences de notification (F-185 / SF-185-06), **coupé par défaut**, ne joue que si l'utilisateur n'est pas sur le sujet qui finit. Son court généré (Web Audio), pas de fichier tiers.
- Mobile : lignes sur trois niveaux, barres de sous-agents masquées.
- Charte : or = progression, ambre = attend votre accord, vert = terminé. **Aucune couleur nouvelle.** AA vérifié sur `--cg-primary`.

## Contraintes

- **Aucun appel modèle, aucun token** : lecture seule de l'état des tours. Réutiliser le mécanisme de la mosaïque (`mosaique/live-turn-view.ts`, flux `GET .../chat/attach` de F-84, pool `turnAttachExecutor`) **sans prendre de place au registre F-70**.
- La mosaïque ne connaît que les terminaux à onglet ouvert : un tour continue côté serveur onglet fermé (F-84). Il faut donc une route légère **« tours en cours de mon poste »** (résumé : workspace, sujet, demande, étape/total, outil courant, début, état attente, sous-agents) — polling ou SSE au choix du dev, borné.
- Ne pas casser F-70 (registre), F-83 (mosaïque), F-175 (bande des attentes), F-185 (notifications).

## Découpage

- **SF-191-01** — Backend : route `GET` des tours en cours/récemment finis du poste (isolation `user_id` + poste), résumé de progression ; tests d'isolation.
- **SF-191-02** — Frontend : la bande (repliée/dépliée, lignes, sous-agents, clic → sujet, mobile).
- **SF-191-03** — Frontend : bandeau de fin hors sujet courant + option « Son de fin » dans les préférences F-185 (défaut coupé).

## Hors périmètre

Interrompre ou répondre depuis la bande ; autres postes ; remplacement de la mosaïque (elle reste la vue détaillée).

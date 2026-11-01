# Mini-spec — F-231 / SF-231-02 L'écran Jarvis

## Identifiant
`F-231 / SF-231-02` — Feature parente : F-231 « Le mode Jarvis » (cadrage, décisions **D1, D6**).
Statut : `ready` — 2026-11-01 — Branche `feat/SF-231-02-l-ecran-jarvis`. Dépend de SF-231-01 (voix) et SF-231-06 (champ `via`), mergées.

## Objectif
Parler au terminal du sujet depuis un écran plein écran, téléphone d'abord : un appui pour parler, un orbe qui dit l'état, une réponse dite en une phrase et dont le détail reste consultable.

## Comportement attendu

### Cas nominal
1. Route **`/jarvis`** (hors coquille, **garde d'authentification existante**), `?sujet=<id>` choisit le sujet ; sinon le dernier retenu sur l'appareil (`localStorage cg.jarvis.subject`), sinon le premier de la liste.
2. En tête : retour (`/atelier`), **sujet courant** (menu pour changer), bouton « ouvrir le terminal du sujet » (`/atelier/{id}`).
3. **Orbe neuronal** animé (canvas, 96 neurones sur une sphère, liens) : **repos** (lent), **écoute** (réagit au niveau du micro), **réflexion** (tourne plus vite, liens allumés), **parole** (pulse avec la voix). Couleurs du design system uniquement ; mouvement réduit si l'utilisateur le préfère.
4. **Appuyer pour parler** (l'orbe ou le bouton « Parler ») : la **dictée F-145 est réutilisée** (`DictationService` → `POST /api/atelier/transcription`). La phrase se clôt **au silence** (1,4 s après de la voix ; 8 s sans un mot ⇒ arrêt) ou par un second appui (« J'ai fini »).
5. Ce qui est dit part dans **le fil du sujet** par le flux existant (`POST /chat/stream`, `via: "JARVIS"`), comme un message tapé : même fil, mêmes outils, même modèle (D1).
6. À la fin du tour : la **phrase orale** (ligne « 🎙 » si présente, sinon deux premières phrases de prose, ≤ 400 caractères) est dite par `POST /api/jarvis/speech` et jouée **en flux** (MediaSource si l'mp3 est lisible en flux, sinon audio entier) ; le **détail** (réponse complète, Markdown assaini) est consultable par « Voir le détail ».
7. **« Passe sur CAGIP »** (passe/bascule/va/change + sur/à/au/vers/dans + nom) change de sujet **sur l'appareil**, sans tour ni coût, si un seul sujet correspond ; sinon la phrase part au cerveau.
8. **Interruption** : un appui pendant que Jarvis parle coupe la voix et l'écoute repart. Un appui pendant la réflexion ajoute une précision (le serveur la verse dans le tour vivant, F-84) ; seule la dernière réponse est lue.
9. L'écran Jarvis **n'inscrit pas de visite** du terminal (l'encart « Pendant ton absence » de SF-231-06 reste juste).

### Contrat API (consommé, aucun ajout)
`GET /api/workspaces`, `POST /api/atelier/transcription`, `POST /api/workspaces/{id}/chat/stream` (+ `via`), `POST /api/jarvis/speech`.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Micro refusé / navigateur sans enregistrement | message « autorise le micro… », rien n'est envoyé |
| Dictée non configurée (503) | message lisible |
| Voix : plafond atteint (429) / non configurée (503) / échec | la phrase **reste écrite**, message dédié |
| Tour refusé (`error`) | message ; état repos |
| Flux coupé sans fin | « La réponse n'est pas arrivée ici : elle sera dans le terminal. » |
| Aucun sujet | « Aucun sujet : ouvre d'abord un terminal. » |

## Contraintes de validation
Phrase orale ≤ 400 caractères. Extrait dicté : bornes de la dictée F-145 (2 min). Changement de sujet : correspondance unique, sans accents ni casse.

## Critères d'acceptation
- [ ] `/jarvis` est protégé par l'authGuard, hors coquille.
- [ ] Le sujet du lien est choisi ; le choix est retenu sur l'appareil.
- [ ] Parler → l'envoi part au fil du sujet avec `via: "JARVIS"` → la phrase orale est dite, le détail consultable.
- [ ] « Passe sur X » change de sujet sans tour.
- [ ] Un appui pendant la parole l'interrompt et réécoute.
- [ ] Sans voix, la réponse reste écrite.

## Plan de test
- **Unitaires** : `jarvis-oral.spec` (phrase orale, changement de sujet, fin de phrase au silence, orbe).
- **Composant** : `jarvis.component.spec` (sujet du lien, envoi marqué + phrase dite, changement de sujet sans tour, interruption, repli écrit).
- **Routes** : `app.routes.spec` (garde, hors coquille).
- **Isolation** : aucune donnée nouvelle ; les sujets et le fil sont ceux du compte (isolation des routes existantes, testée côté passerelle en SF-231-06).

## Tables / endpoints / composants impactés
Aucune table, aucun endpoint. Front : `app.routes`, nouveau dossier `jarvis/` (`JarvisComponent`, `JarvisOrbComponent`, `jarvis-oral`, `jarvis-mic-meter`), `JarvisVoiceService`, `AtelierService.streamChat` (paramètre `via`), `DictationService.stream` (lecture du flux pour le niveau).

## Préoccupations transversales
- **Navigation / routing** ✅ — nouvelle route `/jarvis` (garde `authGuard`), placée avant le parent authentifié et le joker ; vérifiés : routes du terminal (`/atelier`, `/atelier/:id`) inchangées, retour depuis `/jarvis` vers `/atelier`, aucune redirection ajoutée. Le service worker (`navigationUrls`) sert déjà `/jarvis` en navigation d'application (`/**`).
- **Contexte tenant** ✅ — l'écran ne lit que `GET /api/workspaces` (compte connecté) et écrit par les routes existantes, isolées par `user_id`.

## Hors périmètre
La consigne orale côté serveur, l'accusé et l'annonce de fin (SF-231-03) ; la confirmation orale (SF-231-04) ; le mot d'éveil, le lancement par l'assistant et les alertes (SF-231-05). Le changement de **chantier** à la voix : l'envoi part au fil commun du sujet (arbitrage réversible).

# Cadrage F-215 — Le cours vidéo libre (court et long)

**Demande PO du 2026-10-24.** Après l'étude de la vidéo « Comment faire du Motion Design PRO avec Claude Opus 5.5 » et l'essai réel `cours-eks-alb.mp4` (2 min 34, 1080p, voix off, sous-titres, flux animés, fabriqué à partir de la page « EKS expliqué » ; jugé « époustouflant… excellent ») :
- « que la vidéo soit détaillée comme un vrai cours […] les icônes, le nom des ports, le réseau, le DNS, tout ce qui est notion technique, je veux que ce soit sur l'écran, avec l'animation qui va bien » ;
- « est-ce qu'il y a un prompt par défaut ? J'espère que non […] si demain je fais la requête sur EdenRed, qui est sur Azure, que tu sois capable de faire la même chose […] je veux que la vidéo soit libre » ;
- « je veux être capable de demander de m'expliquer tout un sujet […] toute l'architecture AWS de CAGIP, le réseau, les flux, le besoin, les modules, les plateformes, l'infra […] une vidéo de 15 à 20 minutes » ; « que l'outil me propose de le faire sous forme de chapitres, d'une galerie vidéo […] un tutoriel divisé en plusieurs vidéos » ;
- « l'outil qui permet d'enrichir le prompt, je vais l'utiliser très systématiquement […] j'espère qu'il pourra m'aider aussi dans ce cas-là ».

## Ce que l'essai a prouvé (2026-10-24, sur la machine du PO)
- **Aucun plugin Claude Code** : `hyperframes` (npm, open source, HeyGen) filme une page HTML animée (GSAP) image par image dans un Chrome sans écran et encode avec ffmpeg. Node ≥ 22 requis.
- **Chaîne utilisée** : faits extraits de la source → narration par chapitre → voix (OpenAI `gpt-4o-mini-tts`) → horodatage de chaque mot (Whisper `timestamp_granularities=word`) → animations calées **au mot** → `hyperframes lint` + `snapshot` (relecture des images clés, 3 défauts corrigés) → rendu.
- **Icônes officielles** déjà présentes avec la lib `diagrams` (F-142) : AWS, **Azure**, GCP, Kubernetes, on-prem… ⇒ rien de spécifique à AWS.
- **Mesures** : ~10 000 jetons de composition pour 2 min 34 ; rendu 7 min (4 workers) ; coût estimé dans l'app 2–4 $ (+ ~0,06 $ voix/horodatage).

## Principe directeur : une méthode, jamais un contenu
Aucun gabarit de vidéo, aucun prompt de contenu par défaut. L'agent reçoit une **méthode** (comment fabriquer et vérifier) et des **briques graphiques** ; le sujet, le découpage, la narration et chaque animation viennent de la demande et des sources (carte du poste, pages, repo, comptes rendus). Fonctionne pour tout cloud et tout domaine.

## Décisions proposées
1. **Savoir-faire « cours vidéo »** chargé **à la demande** (jamais dans la consigne système stable — règle du préfixe stable) : paquet du `savoir-durable` (nouveau genre `GUIDE`, sur le modèle du genre `AGENT` de F-182) lu par l'agent quand une vidéo est demandée. Contenu : la chaîne ci-dessus ; règles de lisibilité (texte ≥ 20 px à 1080p, une idée par plan, tout terme technique **affiché** à l'écran : CIDR, ports, noms DNS, ressources) ; procédure de relecture ; pièges connus (`<audio>` sans `id` ⇒ vidéo muette, espaces perdus en flex, chevauchements).
2. **Briques graphiques** livrées sur le poste au premier usage (dossier de travail du sujet) : feuille de style de charte (thème clair/sombre), composants animables (zone/sous-réseau, icône + libellé, flèche qui se dessine, **paquet qui circule**, table de routage, requête DNS tapée, badge OK/KO, sous-titres calés), **icônes multi-cloud** (copie depuis la lib `diagrams`). Briques = point de départ, l'agent compose librement.
3. **Voix off derrière une interface** (Provider Independence) : `SpeechProvider` (implémentation OpenAI TTS, clé = clé OpenAI déjà en prod), outil serveur `voix_off(texte, voix?, consignes?)` ⇒ mp3 déposé sur le poste **+ horodatage des mots** (via le fournisseur de transcription existant, mode mots). **Auditions** : `voix_off(auditions: true)` produit une même phrase avec 3–4 voix, publiées par `audio_publier` (F-213) et proposées par une question structurée (F-164). Usage compté au journal d'usage, coupe-circuit `APP_ATELIER_SPEECH_ENABLED`.
4. **Outillage du poste** : capacité runner `video_render` (patron F-190 `video`) : vérifie/installe Node 22, `hyperframes` (version épinglée), Chrome sans écran, ffmpeg (déjà rapatrié par F-190) ; échec clair et actionnable sur un poste verrouillé (« Node 22 introuvable / installation refusée par la politique du poste ») — jamais de contournement de politique (cf. règle « porte de validation vs policy client »). Le rendu long tourne détaché (`HYPERFRAMES_RENDER_DETACHED`) pour survivre à la fin du tour, avec suivi d'avancement.
5. **Relecture obligatoire avant livraison** : `hyperframes lint` sans erreur + images clés (`snapshot`) **regardées** par l'agent (lisibilité, chevauchements, textes coupés) + contrôle du son ; la vidéo n'est publiée (`video_publier`) qu'après. Reprend la doctrine « vérifier avant de conclure » (F-168).
6. **Format long = une série** : au-delà de ~3 min ou sur un sujet large, l'agent **propose** une série d'épisodes (F-213) plutôt qu'un seul fichier :
   - **plan d'abord** : sommaire de 5 à 15 épisodes (1–3 min), objectif et sources de chacun, durée totale estimée, coût estimé — **validé par le PO** (question structurée F-164) avant toute fabrication ;
   - **charte commune** fixée une fois pour la série ; **un épisode = une composition**, fabriquée et rendue séparément (sous-agents par épisode possibles — F-182 / worktree), publiée comme épisode de la série ;
   - corrections par épisode (F-214) sans refaire les autres ; avancement suivi comme un chantier (plan F-208).
7. **Exactitude des faits** (risque n°1 sur 20 min) : chaque affirmation technique de la narration doit être **sourcée** (fait de carte, page, fichier du repo, CR) ; une **fiche des sources** par épisode accompagne la vidéo (affichée sous le lecteur) ; ce qui n'est pas vérifié est soit retiré, soit dit comme hypothèse et listé « à confirmer ». Aucune adresse, port ou nom inventé : une valeur inconnue s'affiche comme générique (`10.180.171.x`) — règle appliquée dans l'essai.
8. **Plume pour la vidéo** (extension de F-193) : quand la demande vise une vidéo, « ✨ Enrichir » la structure en **cahier de réalisation** à partir du contexte du sujet : public et niveau, objectif pédagogique, format (vidéo unique ou série) et durée, **chapitres proposés depuis les sources réellement disponibles**, notions qui doivent apparaître à l'écran, sources à utiliser, style/charte, voix, « à confirmer » surlignés. Rien n'est envoyé sans geste (comportement F-193 inchangé).

## Préoccupations transversales
- **Plans / limites** : nouvel usage payant (voix) ⇒ compté au journal d'usage comme Plume ; quota hébergé appliqué aux tours de fabrication comme à tout tour.
- **Contexte tenant** : `voix_off` écrit dans le dossier du sujet du poste de l'utilisateur seulement ; usage rattaché à `user_id`.
- Aucune préoccupation auth / navigation (pas d'écran nouveau : lecteur et galerie = F-213).

## Questions à trancher par le PO
- **D1** — Charte par défaut des vidéos : celle de l'application (navy/orange/crème, comme l'essai) ou une charte par client/poste (couleurs du client) ?
- **D2** — Voix par défaut (proposé : voix « onyx », consignes « formateur posé » de l'essai ; auditions à la première vidéo d'une série).
- **D3** — Seuil au-delà duquel l'agent propose une série plutôt qu'une vidéo unique (proposé : > 3 min ou > 4 notions majeures).

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-215-01 | Voix off : `SpeechProvider` + OpenAI, outil `voix_off` (mp3 + mots horodatés), auditions, usage, coupe-circuit ; tests | 🟢 |
| SF-215-02 | Outillage du poste : capacité runner `video_render` (vérif/installation épinglée, échec explicite), rendu détaché + avancement ; tests contrat | 🟠 |
| SF-215-03 | Savoir-faire + briques : paquet « cours vidéo » chargé à la demande, briques graphiques et icônes multi-cloud déposées sur le poste, relecture obligatoire avant `video_publier` | 🟠 |
| SF-215-04 | Format long : proposition de série, plan validé, charte commune, épisodes fabriqués séparément (sous-agents), fiche des sources, suivi en chantier | 🟠 |
| SF-215-05 | Plume vidéo : cahier de réalisation (public, objectif, format, chapitres depuis les sources, notions à l'écran, « à confirmer ») | 🟢 |

**Dépendances** : F-213 (publication, séries, lecteur) avant SF-215-03/04 ; F-214 pour les corrections par épisode. Ordre : F-213 → SF-215-01/02 → SF-215-03 → F-214 → SF-215-04 → SF-215-05.

## Hors périmètre
Vidéo générée par IA d'image (personnages, prises de vue réalistes) ; montage manuel sur une timeline ; musique générée ; diffusion publique ; terminal hébergé (sandbox) — extension possible plus tard.

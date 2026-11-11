# Cadrage — F-255 Rejeu de séance Jarvis

**Demande PO du 2026-11-11.** Jarvis (écran `/jarvis`) paraît peu fiable : il n'entend pas, répète, se trompe de
sujet, est lent, dit « Voici » sans rien montrer. Diagnostiquer la séance du 09/11 (16:11–16:27 UTC) a demandé de
recouper **à la main** quatre tables en prod. Le PO veut un **rejeu de séance automatique** : tour par tour, ce qu'il a
dit (audio réécoutable), ce que Jarvis a compris, le temps de chaque étape, ce qui a été affiché ou refusé, avec un
**verdict par tour calculé sans IA**, et un **bilan de séance** comparable d'une séance à l'autre.

Deux lecteurs : le PO (page d'administration, en français) et Claude Code (même rejeu en JSON, route admin).

## 1. L'existant (vérifié le 2026-11-11)

| Source | Ce qu'elle porte | Limite pour le rejeu |
|---|---|---|
| `jarvis_listen_traces` (F-233-15, 7 j) | événements de la boucle d'écoute, sans contenu : `listen_open`, `listen_end`, `transcript`, `speech_rejected`, `stream_fallback`, `brain_done said=…`, `show`/`show_refused` (SF-253-06)… | `at_ms` relatif à l'ouverture de **page** ; `created_at` = heure d'**envoi du lot** (jusqu'à 4 s plus tard). Aucun lien au tour. |
| `jarvis_turn_timings` (F-233-01, 90 j) | une mesure par tour : marques en ms depuis la fin de parole, cerveau, spéculation, repli de flux, rejet, diagnostic de tour vide | envoyée **en fin de tour** (après la voix) ; **perdue** si la page se recharge avant (tours délégués de 17:20 et 17:24:31 Paris) ; ni page ni tour. |
| `jarvis_audio_captures` (F-239-03, 7 j, ADMIN qui l'a demandé) | audio WAV + texte du flux + texte retenu | seulement en écoute en flux et coupure ; aucun lien au tour. |
| `atelier_messages` | USER / ASSISTANT (fil Jarvis, `via=JARVIS`), LINK (délégation, sujet cible), USER délégué dans le sujet cible | aucun lien au tour ; seul l'horodatage serveur. |
| `/api/admin/jarvis/latency` | agrégats p50/p90 par étape | pas de tour par tour. |

**Corrélation actuelle : non fiable.** Recoupée à la main sur le 09/11 : 3 pages, horloge de page décalée de ~3,3 s par
rapport à l'heure d'envoi des lots, 27 mesures pour ~30 paroles (2 tours délégués sans mesure), captures pour 12 tours
seulement. Un rapprochement par l'heure est possible mais approximatif ; il reste utile pour les séances **passées**.

## 2. Principes

- **Aucun contenu nouveau dans le journal d'écoute** (sans contenu par conception) : un identifiant de tour aléatoire et
  l'heure d'envoi du lot ne sont pas du contenu. Le rejeu **joint côté serveur** les contenus qui existent déjà
  (captures, messages).
- **Aucun changement** de la consigne, des outils ou du modèle du cerveau rapide : aucun banc (V8). La seule retouche
  du chemin du cerveau rapide est un point de trace dans son **contrôleur** (le tour est reçu), jamais dans
  `JarvisFastBrainService`.
- **Verdicts sans IA** : règles déterministes, seuils écrits ici et dans le code.
- **ADMIN seulement**, filtre `user_id` partout, test d'isolation.
- Ce n'est **pas** l'écran holographique de Jarvis (aucun texte sur celui-ci) : une page d'administration.

## 3. Les verdicts (sans IA)

| Verdict | Règle |
|---|---|
| **Entendu** | une transcription non vide, acceptée par la porte de parole |
| **Perdu** | parole refusée (`speech_rejected` / `rejected_kind`) ou transcription vide |
| **Répété** | la réponse dite est identique (normalisée) à une réponse des 5 tours précédents de la séance |
| **Lent** | premier son > **3 000 ms** après la fin de parole (seuil du PO) |
| **Mauvais sujet** | une délégation part vers un sujet ≠ **sujet courant** ; sujet courant = dernier sujet du compte **nommé par le PO** dans la séance, sinon le sujet de l'écran ; une délégation dont la demande nomme elle-même la cible n'est jamais « mauvais sujet » |
| **Montré** | un ordre d'écran appliqué (`show`) dans le tour |
| **« Voici » sans affichage** | la réponse annonce un affichage (« voici », « voilà », « je vous montre », « à l'écran ») et aucun `show` dans le tour |
| **Délégation muette** | délégation partie et rien n'a été dit (`brain_done said=0`) |
| **Repli de transcription** | le flux n'a pas servi (`stream_fallback` de la mesure ou du journal), avec sa raison |
| **Mesure absente** | une parole sans mesure de tour (page rechargée, chemin non mesuré) |

**Bilan de séance** : tours, entendus, perdus (taux), répétitions, lents, médiane et p90 du premier son, mauvais
sujets, montrés, « Voici » sans affichage, délégations (dont muettes), replis (taux), mesures absentes. Une **séance** =
des tours séparés de moins de 10 min (plusieurs pages possibles).

## 4. Découpage

| SF | Titre | Contenu | Est. |
|---|---|---|---|
| SF-255-01 | Un identifiant de tour commun | l'écran tire un `turnId` à chaque écoute ; il part avec les événements du journal, la mesure, la capture et la requête au cerveau rapide ; le lot porte l'heure de page à l'envoi ⇒ chaque événement reçoit son heure serveur (`occurred_at`) ; le contrôleur du cerveau rapide trace la réception du tour (`srv_converse`, sujet de l'écran) — un tour délégué existe même si la page se recharge. Migration additive. | 1 j |
| SF-255-02 | Le rejeu en JSON : tours, verdicts, bilan | `GET /api/admin/jarvis/replay/sessions` et `GET /api/admin/jarvis/replay?from=&to=` : séances et leur bilan, tours (journal, mesure, capture, messages, délégations), corrélation exacte par `turnId`, **estimée** par l'heure pour les séances d'avant SF-255-01 ; verdicts déterministes (§3) et bilan ; validé sur la séance du 09/11. | 2 j |
| SF-255-03 | L'écran d'administration | onglet « Rejeu » de la page Jarvis d'administration : séances comparées, tours avec verdicts, étapes chronométrées, audio réécoutable (route existante des captures), ce qui a été montré ou refusé. | 1,5 j |

Total ≈ 4,5 j. Ordre de valeur : 01 → 02 → 03 (le JSON sert Claude Code dès SF-255-02).

> **Re-découpage du 2026-11-11** (pendant SF-255-02) : le cadrage initial séparait l'assemblage (1,5 j) et les
> verdicts (1 j). Les verdicts n'ont de sens que sur le rejeu assemblé et la route rend les deux ; les garder ensemble
> évite une route intermédiaire sans verdicts. SF-255-02 reste ≤ 2 j ; l'écran devient SF-255-03.

## 5. Hors périmètre

- Corriger les causes des défauts (enquête parallèle, F-251/F-252/F-253).
- Garder l'audio des tours en **dictée de repli** (WebM) : la capture reste WAV, flux et coupure (piste notée).
- Tracer la valeur brute de `sujet` reçue du modèle pour les cas `no_subject` : demande de l'enquête, mais elle se
  place dans `JarvisFastBrainService`, modifié par SF-253-03 ; reportée et signalée.
- Garder les bilans au-delà des 7 jours du journal (table de bilans) : piste si la comparaison longue est demandée.

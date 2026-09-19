# F-133 — Capture d'onglet par extension Chrome (robuste à la navigation Teams)

> Cadrage du 2026-09-19, à la demande du PO. **Cadrage seul : livraison SI le réglage de timing (SF-128-16)
> échoue.** C'est la voie **robuste** de repli pour la capture de réunion (F-128).

## 1. Pourquoi (l'échec de l'approche « injection dans la page »)
La capture actuelle **injecte** `getDisplayMedia` + `MediaRecorder` dans le **JS de la page Teams** (via CDP eval). Teams v2 est une SPA qui **navigue** (pré-join → in-call, redirections) ; à chaque navigation, le **contexte JS de la page est détruit** → le capteur meurt. On a tenté 4 correctifs (SF-128-12 ré-injection, -13 re-résolution de cible, -14 start à l'état stable, -16 in-call réel) ; même après un `reattach:ok`, la **ré-injection échoue** (`browser_unreachable`) sur la page in-call. **Conclusion : capturer dans le contexte d'une page qui navigue est structurellement fragile.**

## 2. La bonne architecture
Une **extension de navigateur** (Chromium) utilise **`chrome.tabCapture`** depuis un **contexte persistant** (service worker / offscreen document). La capture est **au niveau du navigateur, liée à l'ONGLET**, pas au JS de la page → elle **survit à toutes les navigations internes** de Teams (pré-join → in-call → changements de vue). **Ni injection, ni ré-injection, ni course de cible CDP.** C'est la manière standard et fiable de capturer un onglet.

## 3. Comment ça s'intègre (le runner pilote déjà le Chrome managé)
- **Charger l'extension** : le Chrome managé (F-122) est lancé par le runner → ajouter `--load-extension=<chemin>` (extension **non empaquetée**, livrée avec le runner) + `--allowlisted-extension-id` si besoin. Extension **dédiée** à ce Chrome managé, jamais le navigateur perso.
- **Déclenchement** : « Rejoindre & capturer » demande à l'extension (via CDP `Runtime.evaluate` sur le service worker, ou un message) de **démarrer `tabCapture`** sur l'onglet de réunion ; l'arrêt de même. L'extension n'a **pas besoin** que la page soit stable.
- **Audio + vidéo/frames** : `tabCapture` fournit le flux **audio de l'onglet** (les autres participants) ; le **micro** (ta voix) reste capté par `getUserMedia` **dans l'extension** (offscreen doc) et **mixé** (WebAudio), comme aujourd'hui mais dans un contexte qui ne meurt pas. Images clés : échantillonnage du flux vidéo dans l'extension.
- **Remontée** : l'extension écrit le média (offscreen `MediaRecorder`) et le **remonte au runner** — via un endpoint local du runner, ou `chrome.runtime` + un petit pont (native messaging / fetch vers la boucle locale du runner) → puis upload gateway par les endpoints existants (`…/audio`, `…/images`).
- **Consentement / auto-accept** : `tabCapture` déclenché par l'extension **n'a pas** le sélecteur de partage d'onglet de `getDisplayMedia` (pas d'invite hors écran) — un point de friction en moins. Micro : pré-autorisation SF-122-05 conservée.

## 4. Découpage
| SF | Titre | Contenu |
|---|---|---|
| **SF-133-01** | **Extension + chargement runner** | Extension non empaquetée (manifest v3, service worker + offscreen doc), livrée avec le runner ; `ManagedChrome` la charge (`--load-extension`) ; « ping » de vie via CDP. |
| **SF-133-02** | **Capturer via `tabCapture` (audio onglet + micro), remonter** | Démarrer/arrêter la capture depuis « Rejoindre & capturer » ; mix onglet+micro ; `MediaRecorder` offscreen ; remontée au runner → upload gateway (endpoints existants). |
| **SF-133-03** | **Images clés du deck** | Échantillonnage du flux vidéo sur changement, dans l'extension → upload (endpoint existant). |
| **SF-133-04** *(option)* | **Bascule** | Remplace le chemin injection (F-128 SF-02/12/13/14/16) par l'extension ; garde l'injection en repli documenté, ou la retire. |

**Ordre** : 133-01 → 02 → 03 (→ 04).

## 5. Risques / points durs
- **Politique d'entreprise** : `--load-extension` d'une extension non empaquetée peut être **bloqué par une policy** Chrome/Edge (ExtensionInstallBlocklist / DisableLoadExtensionCommandLineSwitch). À **vérifier sur le poste** (comme le remote-debugging, qu'on avait pu activer). Repli : extension empaquetée + allowlist, ou l'injection actuelle.
- **Remontée du flux** extension→runner : concevoir un pont local simple et borné (pas de port entrant ; boucle locale).
- **`tabCapture`** requiert que l'onglet soit **actif/ciblé** et un déclenchement conforme MV3 — à câbler proprement.
- **À valider sur call réel** (comme tout ce volet) — mais **sans** la fragilité de navigation.

## 6. Hors périmètre
- La capture OS (Mac : nécessiterait un pilote audio virtuel — écarté).
- Le live (F-128-08).

## 7. Préoccupations transversales
- **Confidentialité** : capture bornée à l'onglet Teams du Chrome managé dédié ; média traité comme aujourd'hui (rétention SF-128-07) ; aucun secret.
- **Composants** : nouvelle **extension** (livrée avec le runner), `ManagedChrome` (chargement), `TeamsTools` (déclenchement via l'extension au lieu de l'injection), remontée→upload (endpoints F-128 existants), F-132 (diags).

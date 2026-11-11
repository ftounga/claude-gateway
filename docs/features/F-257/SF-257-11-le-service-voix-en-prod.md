# Mini-spec — [F-257 / SF-257-11] Le service voix en prod

## Identifiant

`F-257 / SF-257-11`

## Feature parente

`F-257` — Jarvis converse en flux (cadrage `CADRAGE-F-257-jarvis-converse-en-flux.md`, ADR-021)

## Statut

`in-review`

## Date de création

2026-11-12

## Branche Git

`feat/SF-257-11-service-voix-en-prod`

---

## Objectif

Mettre `jarvis-voice` en ligne dans la prod unique (`claude-gateway-staging`, EKS legalcase-shared) par un chemin
**reproductible** et ouvrir le coupe-circuit serveur, pour que le PO puisse essayer la « Conversation en flux » en usage
réel — **éteinte par défaut** sur chaque appareil, il l'allume lui-même.

---

## Comportement attendu

### Cas nominal

1. `jarvis-voice/deploy.sh <worktree détaché>` (nouveau) :
   - crée le dépôt ECR `claude-gateway-jarvis-voice` s'il manque (scan à l'envoi) ;
   - construit l'image depuis `jarvis-voice/`, la pousse sous `staging-<sha court>` ;
   - remplace `REGISTRY_PLACEHOLDER` / `VOICE_IMAGE_TAG` **à la volée** (le fichier reste générique) et applique
     `deployment.yaml` (Deployment + Service), `networkpolicy.yaml`, `ingress.yaml` dans `claude-gateway-staging` ;
   - attend `rollout status`, puis vérifie `/health` dans le pod et `https://portal.ng-itconsulting.com/voice/ws`.
   - Il ne touche **ni** au backend, **ni** au frontend, **ni** à `k8s/**` (partagé avec legalCase — incident du 10/10).
2. Le coupe-circuit backend `APP_JARVIS_VOICE_STREAM_ENABLED=true` est posé **dans le configmap `backend-config`**
   (pas par `kubectl set env` : `deploy2.sh` ne remplace que l'image, le configmap survit aux déploiements), puis le
   backend est redémarré après vérification d'activité prod nulle.
3. Aucun secret dans le service : le jeton d'écoute ElevenLabs est demandé à la passerelle avec le ticket de
   l'utilisateur (SF-257-04). La clé `APP_ELEVENLABS_API_KEY` reste dans `backend-secrets`, jamais lue ni affichée.
4. Le réglage par appareil « Conversation en flux (essai) » garde son défaut `legacy` (`cg.jarvis.loop` absent ⇒
   ancienne boucle) — rien n'est changé côté écran.
5. Les commentaires « PRÉPARÉ, NON DÉPLOYÉ » des manifestes deviennent « déployé par `jarvis-voice/deploy.sh` ».

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Un déploiement `deploy2.sh` tourne | Ne pas lancer `deploy.sh` (même cluster, même namespace) ; attendre | — |
| Build de l'image en échec (ONNX absents) | Le script s'arrête avant tout `kubectl apply` (`set -euo pipefail`) | — |
| Pod non prêt en 300 s | Le script échoue ; le coupe-circuit backend n'est **pas** ouvert | — |
| Urgence en usage | `APP_JARVIS_VOICE_STREAM_ENABLED=false` dans `backend-config` + redémarrage backend : aucun ticket émis, l'écran revient à l'ancienne boucle ; et/ou `kubectl scale deploy/jarvis-voice --replicas=0` | 503 sur `/api/jarvis/voice-session` |
| Ticket absent/faux au premier message WebSocket | Fermeture 4401 (comportement SF-257-02, inchangé) | — |

---

## Critères d'acceptation

- [ ] Dépôt ECR `claude-gateway-jarvis-voice` présent, image `staging-<sha>` poussée.
- [ ] Pod `jarvis-voice` `Running` et `Ready` (1/1), `/health` = `{"status":"ok",...}`.
- [ ] `wss://portal.ng-itconsulting.com/voice/ws` accepte la poignée de main WebSocket (101) et ferme sans ticket valide.
- [ ] Les autres chemins de l'hôte (`/`, `/api/actuator/health`) répondent comme avant (pas de régression d'ingress).
- [ ] `backend-config` contient `APP_JARVIS_VOICE_STREAM_ENABLED=true` ; `POST /api/jarvis/voice-session` rend un ticket
      (et non 503) pour un utilisateur connecté.
- [ ] Le défaut par appareil reste `legacy` (`readVoiceLoop` inchangé, test existant vert).
- [ ] Aucune valeur de secret écrite dans le dépôt, affichée ou journalisée.
- [ ] Rien n'est modifié sous `k8s/**`, ni dans le dépôt legalCase.

---

## Plan de test minimal

- **Unitaires** : aucun code applicatif modifié ; tests existants du service (`pytest` dans `jarvis-voice/`) et
  `jarvis-voice-shadow.spec.ts` (défaut `legacy`) relancés.
- **Intégration (prod)** : `kubectl rollout status`, `/health` dans le pod, poignée de main WebSocket HTTPS (101),
  ticket sans valeur valide ⇒ fermeture 4401 ; non-régression `/` et `/api/actuator/health`.
- **Isolation utilisateur** : inchangée — le ticket porte `sub = user_id` et le sujet est vérifié par
  `findByIdAndUserId` (SF-257-02) ; le service n'a aucun accès direct aux données.

---

## Impacts

- **Fichiers** : `jarvis-voice/deploy.sh` (nouveau), `jarvis-voice/k8s/*.yaml` (commentaires seulement).
- **Prod** : Deployment/Service/Ingress/NetworkPolicy `jarvis-voice`, configmap `backend-config` (1 clé), dépôt ECR.
- **Tables / endpoints / composants** : aucun changement de code.

## Préoccupations transversales

- [ ] Auth / Principal — non (ticket SF-257-02 inchangé)
- [ ] Contexte tenant — non
- [ ] Plans / limites — non (budget voix inchangé)
- [x] **Navigation / routing (ingress)** — un Ingress à part ajoute le chemin `/voice` sur `portal.ng-itconsulting.com`.
      Composants impactés : `claude-gateway-ingress` (`/api`, `/oauth2`, `/login/oauth2`, `/` → frontend). nginx retient
      le préfixe le plus long : seul `/voice*` change de destination ; aucune route Angular ne commence par `/voice`
      (vérifié dans `app.routes.ts`). Non-régression : `/` et `/api/actuator/health` vérifiés après application.

---

## Périmètre

### Hors scope (explicite)

- Bascule par défaut vers la boucle en flux (critères chiffrés au banc F-256, plus tard).
- Lecture de l'audio descendant (SF-257-07), Android (SF-257-10).
- Intégration dans `deploy2.sh` (le service a son propre rythme ; il ne se redéploie que si `jarvis-voice/` change).
- Mise à l'échelle (une réplique, sessions en mémoire).

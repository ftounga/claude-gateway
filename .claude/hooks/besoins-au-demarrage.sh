#!/bin/bash
# SessionStart (startup) : liste les besoins EN_ATTENTE du registre (F-205) en prod
# et les injecte dans le contexte de la session, pour qu'elle les prenne d'elle-même.
# Un besoin pris par une session est « réservé » 24 h par un fichier témoin, pour que
# deux sessions parallèles ne le traitent pas en double.
# Silencieux (sortie vide) si rien n'est en attente ou si la prod est injoignable.
set -uo pipefail
PRIS=~/.local/share/claude-gateway/besoins-pris
mkdir -p "$PRIS"
find "$PRIS" -type f -mmin +1440 -delete 2>/dev/null

rows=$(timeout 50 bash ~/.local/share/claude-gateway/q-prod-ro.sh \
  "select id, kind, to_char(created_at,'YYYY-MM-DD'), replace(title,'|','/') from app_requests where status='EN_ATTENTE' and amends_request_id is null order by (kind='COMMANDE') desc, created_at;" 2>/dev/null \
  | grep -E '^[0-9a-f-]{36}\|' ) || exit 0
[ -z "$rows" ] && exit 0

# F-269 : les COMMANDE de l'administrateur d'abord (à exécuter telles quelles), puis BUG/EVOLUTION.
commandes=""
liste=""
while IFS='|' read -r id kind date titre; do
  [ -f "$PRIS/$id" ] && continue
  if [ "$kind" = "COMMANDE" ]; then
    commandes+="- [COMMANDE] $titre (créée le $date) — id $id"$'\n'
  else
    liste+="- [$kind] $titre (créé le $date) — id $id"$'\n'
  fi
done <<< "$rows"
[ -z "$commandes$liste" ] && exit 0

ctx=""
if [ -n "$commandes" ]; then
  ctx+="COMMANDES DE L'ADMINISTRATEUR, à traiter EN PREMIER — les EXÉCUTER TELLES QUELLES : la description est la commande mot pour mot (lire la description, pas seulement le titre), comme si l'administrateur la tapait dans cette session. Ne pas la reformuler, ne pas l'élargir ; si elle demande une feature, suivre la gouvernance CLAUDE.md.
${commandes}
"
fi
if [ -n "$liste" ]; then
  ctx+="BESOINS EN ATTENTE dans le registre (app_requests, statut EN_ATTENTE) — règle PO du 2026-11-18 : dès le démarrage, les prendre et les implémenter en autonomie, sans attendre qu'on le demande.
${liste}
"
fi
ctx+="Mode d'emploi :
1. Réserver chaque besoin pris : touch ~/.local/share/claude-gateway/besoins-pris/<id> (évite le doublon avec une autre session).
2. Lire le détail : bash ~/.local/share/claude-gateway/q-prod-ro.sh \"select description, reformulation, observed_context from app_requests where id='<id>';\" (+ compléments JOINT : amends_request_id = <id>).
3. Kind COMMANDE = une instruction de l'administrateur à exécuter telle quelle (lancer des tests, un banc, une action) ; BUG/EVOLUTION = feature à livrer selon la gouvernance CLAUDE.md (mini-spec → … → merge, déploiement selon les règles habituelles).
4. Traiter en tâche de fond (agent) si la demande du PO dans cette session est autre chose ; lui dire en une ligne ce qui a été pris.
5. Une fois livré : passer le besoin en LIVRE (PATCH /api/admin/app-requests/<id>) ou le signaler au PO."

jq -n --arg c "$ctx" '{hookSpecificOutput:{hookEventName:"SessionStart",additionalContext:$c}, systemMessage:"Besoins en attente détectés dans le registre — pris en charge."}'

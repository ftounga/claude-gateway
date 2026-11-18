#!/usr/bin/env bash
#
# F-256 — banc à vraie voix : AMORCE du compte de test sur la passerelle locale (passerelle.sh).
#
# Crée le compte banc-voix@banc.local (ADMIN : l'admin a tout, comme le PO), et un univers FICTIF mais de la forme
# réelle : deux clients (CAGIP, EDENRED) et leurs sujets (agenor, data-platform, waf). Aucune donnée réelle.
# Écrit le jeton de session sur la sortie standard (jeton LOCAL, signé par le secret JWT tiré au lancement).
#
# Usage : amorce.sh   (BANC_PORT=18256, BANC_DB=<base H2 de passerelle.sh>, H2_JAR=<jar H2 2.3.x>)
set -euo pipefail

PORT="${BANC_PORT:-18256}"
DB="${BANC_DB:-${TMPDIR:-/tmp}/banc-jarvis-voix-db}"
H2_JAR="${H2_JAR:-$HOME/.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar}"
API="http://localhost:$PORT/api"
EMAIL="banc-voix@banc.local"
PASSWORD="Banc-voix-2026!"

curl -sf -o /dev/null -XPOST "$API/auth/register" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" || true

# F-272 — un schéma d'architecture AWS sur data-platform (graphe 3D tiré de la fixture du front) : la démo du 17/11
# (« présente-moi l'architecture », « en 3D ») a besoin d'une pièce diag dans le sujet. Apostrophes doublées (SQL).
FIXTURE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/src/app/jarvis/stage3d/architecture/fixtures/trois-tiers.graph.json"
read -r NODES GRAPH < <(python3 -I - "$FIXTURE" <<'PY'
import json, sys
g = json.load(open(sys.argv[1]))
print(len(g["nodes"]), json.dumps(g, separators=(",", ":"), ensure_ascii=False).replace("'", "''"))
PY
)

SQL="$(mktemp)"
trap 'rm -f "$SQL"' EXIT
cat > "$SQL" <<EOF
UPDATE users SET role = 'ADMIN', email_verified = TRUE WHERE email = '$EMAIL';
DELETE FROM diagram_renders WHERE user_id = (SELECT id FROM users WHERE email = '$EMAIL');
DELETE FROM workspaces WHERE user_id = (SELECT id FROM users WHERE email = '$EMAIL');
DELETE FROM runner_hosts WHERE user_id = (SELECT id FROM users WHERE email = '$EMAIL');
INSERT INTO runner_hosts (id, user_id, name, created_at, updated_at, mission_status)
  SELECT '00000000-0000-0000-0256-0000000000c1', id, 'CAGIP', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'ACTIVE' FROM users WHERE email = '$EMAIL';
INSERT INTO runner_hosts (id, user_id, name, created_at, updated_at, mission_status)
  SELECT '00000000-0000-0000-0256-0000000000e1', id, 'EDENRED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'ACTIVE' FROM users WHERE email = '$EMAIL';
INSERT INTO workspaces (id, user_id, name, source, execution_target, host_id)
  SELECT '00000000-0000-0000-0256-0000000000a1', id, 'agenor', 'LOCAL', 'RUNNER', '00000000-0000-0000-0256-0000000000c1' FROM users WHERE email = '$EMAIL';
INSERT INTO workspaces (id, user_id, name, source, execution_target, host_id)
  SELECT '00000000-0000-0000-0256-0000000000a2', id, 'data-platform', 'LOCAL', 'RUNNER', '00000000-0000-0000-0256-0000000000c1' FROM users WHERE email = '$EMAIL';
INSERT INTO workspaces (id, user_id, name, source, execution_target, host_id)
  SELECT '00000000-0000-0000-0256-0000000000a3', id, 'waf', 'LOCAL', 'RUNNER', '00000000-0000-0000-0256-0000000000e1' FROM users WHERE email = '$EMAIL';
INSERT INTO diagram_renders (id, user_id, workspace_id, engine, title, graph_json, graph_version, node_count, created_at)
  SELECT '00000000-0000-0000-0256-0000000000d1', id, '00000000-0000-0000-0256-0000000000a2', 'cloud',
    'Architecture AWS de data-platform', '$GRAPH', 1, $NODES, CURRENT_TIMESTAMP FROM users WHERE email = '$EMAIL';
EOF
java -cp "$H2_JAR" org.h2.tools.RunScript -url "jdbc:h2:file:$DB;AUTO_SERVER=TRUE" -user sa -script "$SQL" >&2

curl -sf -XPOST "$API/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" \
    | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])'

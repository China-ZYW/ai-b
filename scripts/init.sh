#!/usr/bin/env bash
# =============================================================================
# aibi full-stack local bootstrap (Linux / macOS / Git Bash).
# Clean checkout -> running system: prereq check -> local.env -> backend build
# -> frontend deps -> (optional) demo DB -> start backend + frontend.
#
# Non-destructive: never overwrites an existing backend/local.env, never drops
# DBs, never prints secret values. Idempotent: safe to re-run.
# Optional overrides: AIBI_JAVA_HOME, AIBI_MVN, AIBI_NODE.
#
# Flags:
#   --no-prompt             skip confirmations (build+deps yes, demo-db no, start yes)
#   --skip-backend-build    do not run mvn package
#   --skip-frontend-install do not run npm install
#   --skip-demo-db          do not offer sample schema import
#   --no-start              prepare only
#   --backend-port N        default 8080
#   --frontend-port N       default 5173
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$REPO_ROOT/backend"
FRONTEND_DIR="$REPO_ROOT/frontend"
LOCAL_ENV="$BACKEND_DIR/local.env"
LOCAL_ENV_EX="$BACKEND_DIR/local.env.example"
SAMPLE_SCHEMA="$REPO_ROOT/poc/nl2sql-poc/sql/sample_schema.sql"

NO_PROMPT=0 SKIP_BACKEND_BUILD=0 SKIP_FRONTEND_INSTALL=0 SKIP_DEMO_DB=0 NO_START=0
BACKEND_PORT=8080 FRONTEND_PORT=5173
while [ $# -gt 0 ]; do
  case "$1" in
    --no-prompt) NO_PROMPT=1 ;;
    --skip-backend-build) SKIP_BACKEND_BUILD=1 ;;
    --skip-frontend-install) SKIP_FRONTEND_INSTALL=1 ;;
    --skip-demo-db) SKIP_DEMO_DB=1 ;;
    --no-start) NO_START=1 ;;
    --backend-port) BACKEND_PORT="$2"; shift ;;
    --frontend-port) FRONTEND_PORT="$2"; shift ;;
    -h|--help) grep -E '^#( |=|$)' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown flag: $1" >&2; exit 2 ;;
  esac
  shift
done

head_() { printf '\n\033[36m== %s ==\033[0m\n' "$1"; }
ok()   { printf '  [ok]  %s\n' "$1"; }
warn() { printf '  [WARN] %s\n' "$1" >&2; }
fail() { printf '  [FAIL] %s\n' "$1" >&2; }
ask()  { # returns 0 if yes
  [ "$NO_PROMPT" -eq 1 ] && return 0
  local ans; read -r -p "$1 [y/N] " ans || true
  case "$ans" in [Yy]*) return 0 ;; *) return 1 ;; esac
}

echo "aibi bootstrap :: repo root = $REPO_ROOT"

# ---------------------------------------------------------------------------
head_ "[1/6] Prerequisite check"
PREREQ_OK=1

if [ -n "${AIBI_JAVA_HOME:-}" ] && [ -x "$AIBI_JAVA_HOME/bin/java" ]; then JAVA_BIN="$AIBI_JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then JAVA_BIN="$(command -v java)"
else JAVA_BIN=""; fi
if [ -n "$JAVA_BIN" ]; then
  JMJ="$("$JAVA_BIN" -version 2>&1 | sed -n 's/.*version "\?\([0-9]*\)\(\.\([0-9]*\)\)\?.*/\1/p' | head -n1)"
  [ "$JMJ" = "1" ] && JMJ="$("$JAVA_BIN" -version 2>&1 | sed -n 's/.*version "1\.\([0-9]*\).*/\1/p' | head -n1)"
  if [ "${JMJ:-0}" -ge 17 ]; then ok "Java $JMJ ($JAVA_BIN)"; else fail "Java ${JMJ:-?} < 17 (need JDK 17+): $JAVA_BIN"; PREREQ_OK=0; fi
else fail "java not found (set AIBI_JAVA_HOME or add JDK 17+ to PATH)"; PREREQ_OK=0; fi

if [ -n "${AIBI_MVN:-}" ] && [ -x "${AIBI_MVN}" ]; then MVN_BIN="$AIBI_MVN"
elif command -v mvn >/dev/null 2>&1; then MVN_BIN="$(command -v mvn)"
else MVN_BIN=""; fi
if [ -n "$MVN_BIN" ]; then ok "Maven $MVN_BIN"; else fail "mvn not found (add Maven to PATH or set AIBI_MVN)"; PREREQ_OK=0; fi

if [ -n "${AIBI_NODE:-}" ] && [ -x "${AIBI_NODE}" ]; then NODE_BIN="$AIBI_NODE"
elif command -v node >/dev/null 2>&1; then NODE_BIN="$(command -v node)"
else NODE_BIN=""; fi
if [ -n "$NODE_BIN" ]; then ok "Node $("$NODE_BIN" -v) ($NODE_BIN)"; else fail "node not found (add Node to PATH or set AIBI_NODE)"; PREREQ_OK=0; fi

if command -v npm >/dev/null 2>&1; then NPM_BIN="$(command -v npm)"; ok "npm $NPM_BIN"; else NPM_BIN=""; warn "npm not found"; fi

if command -v mysql >/dev/null 2>&1; then MYSQL_BIN="$(command -v mysql)"; ok "mysql CLI $MYSQL_BIN"; else MYSQL_BIN=""; fi

if [ "$PREREQ_OK" -ne 1 ]; then echo; echo "Prerequisites missing. Fix [FAIL] items and re-run." >&2; exit 1; fi
[ -n "${AIBI_JAVA_HOME:-}" ] && export JAVA_HOME="$AIBI_JAVA_HOME"

# ---------------------------------------------------------------------------
head_ "[2/6] backend/local.env"
REQUIRED_KEYS="AIBI_DB_URL AIBI_DB_USERNAME AIBI_DB_PASSWORD JWT_SECRET AIBI_CIPHER_KEY"

env_val() { # $1=key ; parse local.env like Java SecretsGuard (skip comments/blank)
  [ -f "$LOCAL_ENV" ] || { echo ""; return; }
  awk -F '=' -v k="$1" '
    /^[[:space:]]*#/ {next} {line=$0}
    { sub(/^[[:space:]]+/,"",line); idx=index(line,"=");
      if (idx>0) { key=substr(line,1,idx-1); val=substr(line,idx+1);
        gsub(/^[[:space:]]+|[[:space:]]+$/,"",key); gsub(/^[[:space:]]+|[[:space:]]+$/,"",val);
        gsub(/^"|"$/,"",val); gsub(/^\047|\047$/,"",val);
        if (key==k) print val } }' "$LOCAL_ENV"
}

if [ ! -f "$LOCAL_ENV" ]; then
  cp "$LOCAL_ENV_EX" "$LOCAL_ENV"
  warn "Copied local.env.example -> local.env"
  echo "  EDIT IT NOW and fill real values (all gitignored, never committed):"
  echo "    $LOCAL_ENV"
  echo "  Required: $REQUIRED_KEYS"
  [ "$NO_PROMPT" -eq 0 ] && { read -r -p "  Press Enter after you saved the file (Ctrl+C to stop)" _ || true; }
fi

MISSING=""
for k in $REQUIRED_KEYS; do
  v="$(env_val "$k")"
  if [ -z "$v" ] || printf '%s' "$v" | grep -q '<'; then MISSING="$MISSING $k"; fi
done
JWT="$(env_val JWT_SECRET)"
if [ -n "$JWT" ] && ! printf '%s' "$JWT" | grep -q '<' && [ "${#JWT}" -lt 32 ]; then MISSING="$MISSING JWT_SECRET(<32bytes)"; fi
if [ -n "$MISSING" ]; then
  warn "Unfilled/invalid keys:$MISSING"
  echo "         Backend will fail-fast on start until these are set (see local.env.example)."
else
  ok "local.env has all required keys"
fi

# ---------------------------------------------------------------------------
head_ "[3/6] Build backend (mvn -DskipTests package)"
if [ "$SKIP_BACKEND_BUILD" -eq 1 ]; then echo "  skipped (--skip-backend-build)"
else
  ( cd "$BACKEND_DIR" && "$MVN_BIN" -q -DskipTests package )
  JAR="$BACKEND_DIR/target/aibi.jar"
  [ -f "$JAR" ] || { fail "expected artifact not found: $JAR"; exit 1; }
  ok "built $JAR"
fi

# ---------------------------------------------------------------------------
head_ "[4/6] Frontend deps (npm install)"
if [ "$SKIP_FRONTEND_INSTALL" -eq 1 ]; then echo "  skipped (--skip-frontend-install)"
elif [ -d "$FRONTEND_DIR/node_modules" ]; then ok "node_modules present, skip install"
else
  [ -n "$NPM_BIN" ] || { warn "npm missing, cannot install frontend deps"; }
  ( cd "$FRONTEND_DIR" && "$NPM_BIN" install )
  ok "npm install done"
fi

# ---------------------------------------------------------------------------
head_ "[5/6] Demo business DB (optional)"
if [ "$SKIP_DEMO_DB" -eq 1 ]; then echo "  skipped (--skip-demo-db)"
elif [ -z "$MYSQL_BIN" ] || [ ! -f "$SAMPLE_SCHEMA" ]; then echo "  skipped (mysql CLI or sample_schema.sql absent)"
else
  echo "  The seeded demo datasource (DataSeeder) points to aibi_demo by default."
  echo "  Import local sample schema? (creates DB aibi_demo, drops its tables; idempotent)"
  echo "  WARNING: import targets a LOCAL MySQL. Do NOT point it at the shared remote demo instance."
  if ask "  Import $SAMPLE_SCHEMA (creates DB aibi_demo)?"; then
    printf '  host [127.0.0.1]: '; read -r MY_HOST || true; MY_HOST="${MY_HOST:-127.0.0.1}"
    printf '  port [3306]: '; read -r MY_PORT || true; MY_PORT="${MY_PORT:-3306}"
    printf '  user [root]: '; read -r MY_USER || true; MY_USER="${MY_USER:-root}"
    if [ -n "${MYSQL_PWD:-}" ]; then MY_PW="$MYSQL_PWD"
    elif [ "$NO_PROMPT" -eq 1 ]; then warn "no MYSQL_PWD in env, cannot authenticate non-interactively"; MY_PW=""
    else printf '  password: '; read -rs MY_PW || true; echo; fi
    if [ -n "$MY_PW" ]; then
      if MYSQL_PWD="$MY_PW" "$MYSQL_BIN" -h "$MY_HOST" -P "$MY_PORT" -u "$MY_USER" < "$SAMPLE_SCHEMA"; then ok "demo schema imported into aibi_demo"
      else warn "import failed (non-fatal)"; fi
      MY_PW=""
    else echo "  skipped (no password)"; fi
  else echo "  skipped by user"; fi
fi

# ---------------------------------------------------------------------------
head_ "[6/6] Start backend + frontend"
if [ "$NO_START" -eq 1 ]; then
  echo "  skipped (--no-start)."
  echo; echo "Bootstrap prepared. All steps green = ready to run."; exit 0
fi
if ! ask "  Start backend (:$BACKEND_PORT) and frontend (:$FRONTEND_PORT) now?"; then
  echo "  Not starting. Re-run later."; exit 0
fi

JAR="$BACKEND_DIR/target/aibi.jar"
RUN_LOG="$BACKEND_DIR/run.log"; RUN_ERR="$BACKEND_DIR/run.err.log"
echo "  launching backend (auto-loads local.env, log -> backend/run.log)"
( cd "$BACKEND_DIR" && nohup "$JAVA_BIN" -jar "$JAR" >"$RUN_LOG" 2>"$RUN_ERR" & echo $! >"$BACKEND_DIR/.backend.pid" )

echo "  waiting for backend health ..."
HEALTH="http://127.0.0.1:$BACKEND_PORT/actuator/health"; READY=0
for i in $(seq 1 60); do
  sleep 3
  if command -v curl >/dev/null 2>&1; then
    if curl -fsS "$HEALTH" 2>/dev/null | grep -q '"UP"'; then READY=1; break; fi
  else
    if "$JAVA_BIN" -version >/dev/null 2>&1 && grep -q 'Started AiBiApplication' "$RUN_LOG" 2>/dev/null; then READY=1; break; fi
  fi
  printf '    ...%ss\n' "$((i*3))"
done
if [ "$READY" -eq 1 ]; then ok "backend UP at $HEALTH"; else warn "backend not confirmed within 180s; check backend/run.log"; fi

export VITE_BACKEND="http://127.0.0.1:$BACKEND_PORT"
echo "  launching frontend (VITE_BACKEND=$VITE_BACKEND)"
( cd "$FRONTEND_DIR" && nohup "${NPM_BIN:-npm}" run dev -- --port "$FRONTEND_PORT" >"$FRONTEND_DIR/vite.log" 2>&1 & echo $! >"$FRONTEND_DIR/.vite.pid" )

echo
printf '\033[32mDone. Open:\033[0m\n'
echo "  Frontend: http://localhost:$FRONTEND_PORT/"
echo "  Backend : http://localhost:$BACKEND_PORT/actuator/health"
echo "  Login   : admin / [see-startup-log]  (superadmin uses AIBI_SUPERADMIN_PASSWORD)"
echo "Logs: backend/run.log , frontend/vite.log   (stop: kill \$(cat backend/.backend.pid frontend/.vite.pid))"

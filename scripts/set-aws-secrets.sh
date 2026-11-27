#!/usr/bin/env bash
# Überträgt die (temporären) Learner-Lab-Credentials aus ~/.aws/credentials als GitHub
# Secrets in Backend- und Frontend-Repo. Nach JEDEM Lab-Neustart erneut ausführen
# (zuerst die neuen Werte aus "AWS Details" in ~/.aws/credentials kopieren).
#   ./scripts/set-aws-secrets.sh [pfad-zum-frontend-repo]
set -euo pipefail

PROFILE="${AWS_PROFILE:-default}"
FE_REPO="${1:-../refcard-05-react-vite}"

aws sts get-caller-identity --profile "$PROFILE" >/dev/null \
  || { echo "Credentials im Profil '$PROFILE' sind ungültig/abgelaufen." >&2; exit 1; }

for repo in . "$FE_REPO"; do
  (
    cd "$repo"
    for key in aws_access_key_id aws_secret_access_key aws_session_token; do
      aws configure get "$key" --profile "$PROFILE" | gh secret set "$(echo "$key" | tr a-z A-Z)"
    done
  )
done
echo "AWS-Secrets in beiden Repos aktualisiert."

#!/usr/bin/env bash
# RefCard 05: Legt die AWS-Infrastruktur für Backend + Frontend auf ECS (Fargate) an.
# Dokumentiert, was im Learner Lab existiert (ALBs, ECS, RDS, ECR, S3) - einmalig auf einem
# LEEREN Lab-Account ausführen, nachdem die Lab-Credentials in ~/.aws/credentials stehen:
#   ./scripts/aws-setup.sh [pfad-zum-frontend-repo]
# Danach trägt das Skript Account-ID, RDS-Endpoint, Secret-ARN und Frontend-URL in beide
# task-definition.json ein (inkl. S3-Bucket-Name) und setzt VITE_API_BASE_URL im Frontend-Repo.
set -euo pipefail

export AWS_PROFILE="${AWS_PROFILE:-default}"
export AWS_REGION=us-east-1
export AWS_PAGER=""

FE_REPO="${1:-../refcard-05-react-vite}"
BE_TD=task-definition.json
FE_TD="$FE_REPO/task-definition.json"

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "AWS-Account: $ACCOUNT_ID (Region $AWS_REGION)"

# --- Netzwerk: Default-VPC, zwei Subnets in verschiedenen AZs (für den ALB nötig) ---
VPC_ID=$(aws ec2 describe-vpcs --filters Name=is-default,Values=true --query "Vpcs[0].VpcId" --output text)
SUBNETS=$(aws ec2 describe-subnets \
  --filters Name=vpc-id,Values="$VPC_ID" Name=availability-zone,Values=us-east-1a,us-east-1b \
  --query "Subnets[].SubnetId" --output text)
SUBNET_CSV=$(echo "$SUBNETS" | tr '\t' ',')
SG_DEFAULT=$(aws ec2 describe-security-groups --filters Name=vpc-id,Values="$VPC_ID" Name=group-name,Values=default \
  --query "SecurityGroups[0].GroupId" --output text)
echo "VPC $VPC_ID, Subnets $SUBNET_CSV"

# --- Security Groups: Internet -> ALB :80 -> Tasks -> RDS :3306 (nur vom Backend) ---
sg() { aws ec2 create-security-group --group-name "$1" --description "$1" --vpc-id "$VPC_ID" --query GroupId --output text; }
SG_ALB=$(sg refcard05-alb-sg)
SG_BE=$(sg refcard05-backend-sg)
SG_FE=$(sg refcard05-frontend-sg)
aws ec2 authorize-security-group-ingress --group-id "$SG_ALB" --protocol tcp --port 80 --cidr 0.0.0.0/0 >/dev/null
aws ec2 authorize-security-group-ingress --group-id "$SG_BE" --protocol tcp --port 8080 --source-group "$SG_ALB" >/dev/null
aws ec2 authorize-security-group-ingress --group-id "$SG_FE" --protocol tcp --port 80 --source-group "$SG_ALB" >/dev/null
aws ec2 authorize-security-group-ingress --group-id "$SG_DEFAULT" --protocol tcp --port 3306 --source-group "$SG_BE" >/dev/null

# --- RDS MariaDB (dauert ~5-10 Minuten). Passwort verwaltet RDS selbst in Secrets Manager ---
# Die Datenbank "db_biztrips" legt das Backend beim ersten Start an (createDatabaseIfNotExist=true).
aws rds create-db-instance \
  --db-instance-identifier springboot-backend \
  --db-instance-class db.t4g.micro \
  --engine mariadb \
  --master-username admin \
  --manage-master-user-password \
  --allocated-storage 20 \
  --vpc-security-group-ids "$SG_DEFAULT" \
  --no-publicly-accessible >/dev/null

# --- ECR-Repositories + ECS-Cluster (je eines pro Service) ---
aws ecr create-repository --repository-name springboot-backend >/dev/null
aws ecr create-repository --repository-name react-vite-frontend >/dev/null
aws ecs create-cluster --cluster-name biztripsBackend >/dev/null
aws ecs create-cluster --cluster-name biztripsFrontend >/dev/null

# --- S3-Bucket für die Bilder: privat, verschlüsselt, Zugriff nur für LabRole auf images/* ---
# Learner Lab erlaubt keine eigene Task-Rolle -> Least Privilege über die Bucket-Policy statt IAM.
BUCKET="refcard05-biztrips-images-$ACCOUNT_ID"
aws s3api create-bucket --bucket "$BUCKET" >/dev/null
aws s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-encryption --bucket "$BUCKET" --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
sed -E "s/[0-9]{12}/$ACCOUNT_ID/g" "$(dirname "$0")/s3-bucket-policy.json" > /tmp/refcard05-bucket-policy.json
aws s3api put-bucket-policy --bucket "$BUCKET" --policy file:///tmp/refcard05-bucket-policy.json

# --- Load Balancer + Target Groups (Typ ip, weil Fargate-Tasks eigene IPs haben) ---
alb() { aws elbv2 create-load-balancer --name "$1" --subnets $SUBNETS --security-groups "$SG_ALB" --query "LoadBalancers[0].LoadBalancerArn" --output text; }
ALB_BE=$(alb refcard05-backend-alb)
ALB_FE=$(alb refcard05-frontend-alb)
TG_BE=$(aws elbv2 create-target-group --name refcard05-backend-tg --protocol HTTP --port 8080 --vpc-id "$VPC_ID" \
  --target-type ip --health-check-path /actuator/health --query "TargetGroups[0].TargetGroupArn" --output text)
TG_FE=$(aws elbv2 create-target-group --name refcard05-frontend-tg --protocol HTTP --port 80 --vpc-id "$VPC_ID" \
  --target-type ip --health-check-path / --query "TargetGroups[0].TargetGroupArn" --output text)
aws elbv2 create-listener --load-balancer-arn "$ALB_BE" --protocol HTTP --port 80 --default-actions Type=forward,TargetGroupArn="$TG_BE" >/dev/null
aws elbv2 create-listener --load-balancer-arn "$ALB_FE" --protocol HTTP --port 80 --default-actions Type=forward,TargetGroupArn="$TG_FE" >/dev/null
BE_URL="http://$(aws elbv2 describe-load-balancers --load-balancer-arns "$ALB_BE" --query "LoadBalancers[0].DNSName" --output text)"
FE_URL="http://$(aws elbv2 describe-load-balancers --load-balancer-arns "$ALB_FE" --query "LoadBalancers[0].DNSName" --output text)"

# --- Auf RDS warten, Endpoint + Secret-ARN in die Task Definitions eintragen ---
echo "Warte auf RDS (kann bis zu 10 Minuten dauern) ..."
aws rds wait db-instance-available --db-instance-identifier springboot-backend
RDS_ENDPOINT=$(aws rds describe-db-instances --db-instance-identifier springboot-backend \
  --query "DBInstances[0].Endpoint.Address" --output text)
SECRET_ARN=$(aws rds describe-db-instances --db-instance-identifier springboot-backend \
  --query "DBInstances[0].MasterUserSecret.SecretArn" --output text)

# ersetzt die Werte des bisherigen Accounts durch die des aktuellen
sed -i.bak -E \
  -e "s/[0-9]{12}/$ACCOUNT_ID/g" \
  -e "s#jdbc:mariadb://[^:]+:3306#jdbc:mariadb://$RDS_ENDPOINT:3306#" \
  -e "s#arn:aws:secretsmanager:[^\"]+-[A-Za-z0-9]{6}:#$SECRET_ARN:#" \
  -e "s#http://refcard05-frontend-alb[^\"]*#$FE_URL#" \
  "$BE_TD" && rm "$BE_TD.bak"
sed -i.bak -E -e "s/[0-9]{12}/$ACCOUNT_ID/g" "$FE_TD" && rm "$FE_TD.bak"

# --- Task Definitions registrieren und Services anlegen ---
# Circuit Breaker: wird ein neuer Task nie gesund (Container-/ALB-Health-Check), rollt ECS
# automatisch auf die letzte funktionierende Task-Definition zurück.
# Die Images baut und pusht erst GitHub Actions beim nächsten Push auf main. Bis dahin
# schlagen die Tasks mit "CannotPullContainerError" fehl - das ist erwartet.
aws ecs register-task-definition --cli-input-json "file://$BE_TD" >/dev/null
aws ecs register-task-definition --cli-input-json "file://$FE_TD" >/dev/null
aws ecs create-service --cluster biztripsBackend --service-name biztrips-backend-service \
  --task-definition biztrips-backend --desired-count 1 --launch-type FARGATE \
  --deployment-configuration "deploymentCircuitBreaker={enable=true,rollback=true},maximumPercent=200,minimumHealthyPercent=100" \
  --health-check-grace-period-seconds 120 \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNET_CSV],securityGroups=[$SG_BE],assignPublicIp=ENABLED}" \
  --load-balancers "targetGroupArn=$TG_BE,containerName=biztrips-backend,containerPort=8080" >/dev/null
aws ecs create-service --cluster biztripsFrontend --service-name biztrips-frontend-service \
  --task-definition biztrips-frontend --desired-count 1 --launch-type FARGATE \
  --health-check-grace-period-seconds 30 \
  --deployment-configuration "deploymentCircuitBreaker={enable=true,rollback=true},maximumPercent=200,minimumHealthyPercent=100" \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNET_CSV],securityGroups=[$SG_FE],assignPublicIp=ENABLED}" \
  --load-balancers "targetGroupArn=$TG_FE,containerName=biztrips-frontend,containerPort=80" >/dev/null

# --- Backend-URL als GitHub-Variable im Frontend-Repo (wird beim Build einkompiliert) ---
(cd "$FE_REPO" && gh variable set VITE_API_BASE_URL --body "$BE_URL/")

echo
echo "Infrastruktur fertig. Jetzt ./scripts/set-aws-secrets.sh ausführen und beide Repos pushen."
echo "  Backend:  $BE_URL/v1/trips   (Health: $BE_URL/actuator/health)"
echo "  Frontend: $FE_URL"

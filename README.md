# RefCard 05 – Spring Boot Backend auf AWS (ECR, ECS, RDS)

REST-API-Backend für die BusinessTrips-Applikation (Mitarbeitende, Business
Trips, Flüge, Meetings). Teil der CI/CD-Übungssequenz **RefCard 02–05**
(siehe [`docs/future/RefCard-03-RefCard-05-own-repos.md`](docs/future/RefCard-03-RefCard-05-own-repos.md))
— dieses Repository ist der **Backend-Teil von RefCard 05**: Spring Boot Backend,
als Container auf ECS Fargate, Datenbank auf RDS (MariaDB).

Das Frontend liegt im eigenen Repo
[`refcard-05-react-vite`](https://github.com/Koetbullar-Droide/refcard-05-react-vite),
die lokale Gesamtumgebung (Frontend + Backend + MariaDB) im Ordner `refcard-05-compose`.

## Tech-Stack

- Java 21, Spring Boot 4.1 (Web, Data JPA)
- MariaDB (lokal via Docker Compose) / H2 (Default, für Tests und schnellen lokalen Start)
- Maven (Maven Wrapper, kein lokales Maven nötig)

## Schnellstart

### Ohne Datenbank-Setup (H2, Default)

```bash
./mvnw spring-boot:run
```

Startet auf Port 8080 mit einer flüchtigen In-Memory-H2-Datenbank (Daten sind nach jedem Neustart weg, werden aber automatisch neu geseedet).

### Mit MariaDB (persistent, wie in Produktion)

```bash
docker compose -f docker/docker-compose.yml up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=mariadb
```

- MariaDB läuft dann auf `localhost:3306` (Datenbank `db_biztrips`, User `root`, Passwort `bbw123`)
- phpMyAdmin ist unter [http://localhost:8082](http://localhost:8082) erreichbar (gleiche Zugangsdaten)
- Die Demo-Daten werden nur beim allerersten Start eingefügt — ein Neustart der Applikation dupliziert sie nicht

### Tests ausführen

```bash
./mvnw test
```

## API

Basis-Pfad: `/v1`

| Endpoint                                                                                                                                           | Beschreibung                                                              |
| -------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| `GET /v1/trips`, `POST /v1/trips`, `GET /v1/trips/{id}`, `DELETE /v1/trips/{id}`                                                                   | Business Trips                                                            |
| `GET /v1/employees`, `POST /v1/employees`, `GET /v1/employees/{id}`, `PUT /v1/employees/{id}`, `DELETE /v1/employees/{id}`, `DELETE /v1/employees` | Mitarbeitende                                                             |
| `GET /v1/flights`, `POST /v1/flights`, `GET /v1/flights/{id}`, `PUT /v1/flights/{id}`, `DELETE /v1/flights/{id}`, `DELETE /v1/flights`             | Flüge                                                                     |
| `GET /v1/meetings`, `POST /v1/meetings`, `GET /v1/meetings/{id}`, `PUT /v1/meetings/{id}`, `DELETE /v1/meetings/{id}`, `DELETE /v1/meetings`       | Meetings                                                                  |
| `POST /v1/signIn`                                                                                                                                  | Login (Demo-User: `joe` / `1234`)                                         |
| `GET /actuator/health`                                                                                                                             | Health-Check (Spring Boot Actuator) — prüft u. a. die Datenbankverbindung |

## Deployment auf AWS (RefCard 05)

```
Internet ─► ALB refcard05-backend-alb :80 ─► Target Group (ip, :8080, /actuator/health)
                                              └─► ECS-Service biztrips-backend-service (Fargate)
                                                     ├─► RDS MariaDB springboot-backend :3306
                                                     └─► S3 refcard05-biztrips-images-891377136681/images/*
```

| Ressource             | Name                                                                                                  |
| --------------------- | ----------------------------------------------------------------------------------------------------- |
| ECR-Repository        | `springboot-backend`                                                                                  |
| ECS-Cluster / Service | `biztripsBackend` / `biztrips-backend-service`                                                        |
| Task Definition       | `biztrips-backend` ([`task-definition.json`](task-definition.json))                                   |
| Load Balancer         | `refcard05-backend-alb` → http://refcard05-backend-alb-791071793.us-east-1.elb.amazonaws.com/v1/trips |
| Datenbank             | RDS MariaDB `springboot-backend` (nicht öffentlich, Port 3306 nur aus `refcard05-backend-sg`)         |
| DB-Zugangsdaten       | von RDS verwaltetes Secret in Secrets Manager, ECS liest `username`/`password` daraus                 |

- **Pipeline** ([`.github/workflows/deploy.yml`](.github/workflows/deploy.yml)): `test` → `build` → `deploy-ecs`
  (Image mit Tags `<commit-sha>` und `latest` nach ECR, Task Definition rendern, ECS-Service rolling aktualisieren).
  `deploy-ecs` läuft nur bei Push auf `main`.
- **Datenbank `db_biztrips`** legt das Backend beim ersten Start selbst an (`createDatabaseIfNotExist=true`),
  Tabellen über `ddl-auto=update`, Demo-Daten über den Seed in `BusinessTripsBackendApplication`.
- **CORS**: lokal `*`; auf ECS per `APP_CORS_ALLOWED_ORIGINS` auf die Frontend-ALB-URL eingeschränkt.
- **Rollen**: Learner Lab erlaubt keine eigenen IAM-Rollen → `LabRole` als Execution- und Task-Role.

### Bilder-Storage: lokal (RefCard 04) → S3 (RefCard 05)

Der Endpoint `/v1/images` spricht nur mit dem Interface `ImageStorage` (`ch.bbw.trips.storage`).
Welche Implementierung aktiv ist, entscheidet `APP_STORAGE_TYPE`:

| `APP_STORAGE_TYPE` | Implementierung | Wo |
| --- | --- | --- |
| `local` (Default) | `LocalImageStorage` | Verzeichnis `APP_STORAGE_LOCAL_DIR` bzw. Docker-Volume (lokal, Compose) |
| `s3` | `S3ImageStorage` | Bucket `APP_STORAGE_S3_BUCKET` unter `images/` (ECS) |

Warum S3 statt Volume auf ECS: Fargate-Tasks haben nur ein flüchtiges Dateisystem. Bei 2 Tasks hätte
jeder seine eigenen Bilder, und bei jedem Deployment wären sie weg. S3 wird von allen Tasks geteilt
und überlebt Deployments.

- **Keine Zugangsdaten im Code**: Der AWS SDK holt sie über die Default Credential Chain, auf ECS
  aus der Task-Rolle `LabRole`.
- **Least Privilege trotz Learner Lab**: Eine eigene Rolle ist nicht möglich. Darum schränkt die
  [Bucket-Policy](scripts/s3-bucket-policy.json) ein: nur `LabRole` darf `images/*` lesen, schreiben
  und löschen, alle anderen Principals (ausser der Lab-Konsole `voclabs`) sind gesperrt, nur HTTPS.
- Bucket ist privat (Block Public Access) und mit SSE-S3 verschlüsselt. Bilder werden über das
  Backend ausgeliefert (`GET /v1/images/{key}`), nicht direkt aus S3.
- Schlüssel generiert der Server (UUID + Endung), Uploads sind auf Bildtypen beschränkt.

S3-Variante lokal ausprobieren (nutzt die Lab-Credentials aus `~/.aws`):

```bash
APP_STORAGE_TYPE=s3 APP_STORAGE_S3_BUCKET=refcard05-biztrips-images-891377136681 ./mvnw spring-boot:run
curl -F file=@bild.png http://localhost:8080/v1/images
```

### Learner-Lab-Credentials (statt OIDC)

AWS Academy Learner Lab erlaubt weder `iam:CreateRole` noch eigene OIDC-Provider. Die Pipeline nutzt
deshalb die temporären Lab-Credentials als GitHub Secrets `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
`AWS_SESSION_TOKEN`. Diese laufen mit der Lab-Session ab. Nach jedem Lab-Neustart:

1. Werte aus _AWS Details_ → `~/.aws/credentials` kopieren
2. `./scripts/set-aws-secrets.sh` (setzt die Secrets in Backend- **und** Frontend-Repo, braucht `gh`)

In einem produktiven AWS-Account wäre OIDC (`role-to-assume`) der richtige Weg.

## Projektstruktur

```
src/main/java/ch/bbw/trips/   Applikationscode (Controller, Entities, Repositories, DTOs)
docker/docker-compose.yml     MariaDB + phpMyAdmin für die lokale Entwicklung
Dockerfile                    Multi-Stage-Build (JDK baut das JAR → JRE-Alpine-Image)
task-definition.json          ECS-Task-Definition (Fargate, RDS, Secrets Manager)
.github/workflows/deploy.yml  CI/CD: test → build → ECR → ECS
src/main/java/ch/bbw/trips/storage/  Bilder-Endpoint + Storage (lokal / S3)
scripts/                      AWS-Setup, S3-Bucket-Policy und Learner-Lab-Secrets
docs/exercises/               CI/CD-Übungen (EX-01 EC2, EX-02 Docker Hub, EX-03 ECS/RDS)
docs/reviews/                 Code-Reviews
docs/future/                  Planungsentscheidungen für RefCard 03–05
```

## Für Studierende

Der Tag [`v1-start`](../../releases/tag/v1-start) markiert den Ausgangspunkt für die Übungen in `docs/exercises/` — bewusst **ohne** `Dockerfile`, `.dockerignore`, `.github/workflows/deploy.yml` und `task-definition.json`, da ihr genau diese Dateien in EX-01/EX-02/EX-03 selbst erstellt.

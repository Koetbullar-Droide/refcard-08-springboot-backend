# RefCard 05 – Spring Boot Backend auf AWS (ECR, ECS, RDS)

REST-API-Backend für die BusinessTrips-Applikation (Mitarbeitende, Business
Trips, Flüge, Meetings). Teil der CI/CD-Übungssequenz **RefCard 02–05**
(siehe [`docs/future/RefCard-03-RefCard-05-own-repos.md`](docs/future/RefCard-03-RefCard-05-own-repos.md))
— dieses Repository ist der **Backend-Teil von RefCard 05**: Spring Boot Backend,
als Container auf ECS Fargate, Datenbank auf RDS (MariaDB).

## Überblick RefCard 05

| Teil | Repo | Läuft auf AWS als |
| --- | --- | --- |
| Frontend (React + Vite, nginx) | [refcard-05-react-vite](https://github.com/Koetbullar-Droide/refcard-05-react-vite) | ECR `react-vite-frontend` → ECS `biztripsFrontend/biztrips-frontend-service` |
| Backend (Spring Boot) | dieses Repo | ECR `springboot-backend` → ECS `biztripsBackend/biztrips-backend-service`, RDS MariaDB, S3 |
| Lokale Gesamtumgebung | dieses Repo, [`docker-compose.yml`](docker-compose.yml) | – (nur lokal, siehe [unten](#gesamtsystem-lokal-starten-frontend--backend--datenbank)) |

**Releases**: Beide Repos werden mit SemVer-Git-Tags versioniert, der Tag wird zum Image-Tag in ECR.
Ein Release der Gesamtapplikation besteht aus je einem Frontend- und Backend-Tag:

| Release | Frontend | Backend | Inhalt |
| --- | --- | --- | --- |
| RefCard 05 | `v1.0.0` | `v1.0.0` | ECS-Deployment, RDS, S3-Bilder-Storage, Health-Checks, Smoke-Tests |

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

### Gesamtsystem lokal starten (Frontend + Backend + Datenbank)

Frontend und Backend liegen in getrennten Repos. [`docker-compose.yml`](docker-compose.yml) in diesem
Repo baut beide und startet sie zusammen mit MariaDB. Voraussetzung: Docker (Compose v2) und beide
Repos nebeneinander geklont:

```
RefCard05/
├── refcard-08-springboot-backend/  <- git clone git@github.com:Koetbullar-Droide/refcard-08-springboot-backend.git
└── refcard-05-react-vite/          <- git clone git@github.com:Koetbullar-Droide/refcard-05-react-vite.git
```

```bash
cd refcard-08-springboot-backend
docker compose up --build
```

| Dienst | URL |
| --- | --- |
| Frontend | http://localhost:3000 (Version: `/version.json`) |
| Backend | http://localhost:8080/v1/trips · Health: `/actuator/health` · Version: `/actuator/info` |
| Bild hochladen | `curl -F file=@bild.jpg http://localhost:8080/v1/images` (lokal auf Volume, auf AWS im S3-Bucket) |
| phpMyAdmin (optional) | `docker compose --profile tools up` → http://localhost:8082 (root / bbw123) |

- **Startreihenfolge über Healthchecks**: MariaDB → Backend (wartet, bis die DB gesund ist) → Frontend
  (wartet auf das Backend). Der erste Start dauert wegen des Backend-Builds einige Minuten.
- **Sinnvolle Defaults**: läuft ohne `.env`. Optional `.env.example` nach `.env` kopieren, um Passwort,
  Pfad zum Frontend-Repo oder fertige Images zu setzen.
- Lokal liegen Bilder auf einem Docker-Volume (`APP_STORAGE_TYPE=local`), auf AWS in S3. Es sind
  keine AWS-Zugangsdaten nötig.

**Variante: fertige Images aus ECR** (z. B. genau das Release `v1.0.0`): in `.env` `BACKEND_IMAGE`/
`FRONTEND_IMAGE` einkommentieren, dann

```bash
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin 891377136681.dkr.ecr.us-east-1.amazonaws.com
docker compose pull && docker compose up
```

Hinweis: `VITE_API_BASE_URL` ist im ECR-Frontend-Image fest auf das AWS-Backend einkompiliert. Für
den rein lokalen Betrieb darum die Variante mit `--build` verwenden.

**Prüfen und stoppen**:

```bash
docker compose ps                           # alle Dienste "healthy"
curl http://localhost:8080/actuator/info    # Backend-Version
curl http://localhost:3000/version.json     # Frontend-Version
docker compose down                         # stoppen (Daten bleiben)
docker compose down -v                      # stoppen und Daten löschen
```

### Tests ausführen

```bash
./mvnw test
```

Braucht **JDK 21** (wie CI und Dockerfile). Ab JDK 23 läuft Lombok nicht mehr automatisch und der
Build bricht mit vielen `cannot find symbol`-Fehlern ab. Dann z. B.
`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test`.

## API

Basis-Pfad: `/v1`

| Endpoint                                                                                                                                           | Beschreibung                                                              |
| -------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| `GET /v1/trips`, `POST /v1/trips`, `GET /v1/trips/{id}`, `DELETE /v1/trips/{id}`                                                                   | Business Trips                                                            |
| `GET /v1/employees`, `POST /v1/employees`, `GET /v1/employees/{id}`, `PUT /v1/employees/{id}`, `DELETE /v1/employees/{id}`, `DELETE /v1/employees` | Mitarbeitende                                                             |
| `GET /v1/flights`, `POST /v1/flights`, `GET /v1/flights/{id}`, `PUT /v1/flights/{id}`, `DELETE /v1/flights/{id}`, `DELETE /v1/flights`             | Flüge                                                                     |
| `GET /v1/meetings`, `POST /v1/meetings`, `GET /v1/meetings/{id}`, `PUT /v1/meetings/{id}`, `DELETE /v1/meetings/{id}`, `DELETE /v1/meetings`       | Meetings                                                                  |
| `POST /v1/signIn`                                                                                                                                  | Login (Demo-User: `joe` / `1234`)                                         |
| `GET /v1/images`, `POST /v1/images` (multipart `file`), `GET /v1/images/{key}`, `DELETE /v1/images/{key}`                                       | Bilder (lokal oder S3, siehe unten)                                       |
| `GET /actuator/health`                                                                                                                             | Health-Check (Spring Boot Actuator) — prüft u. a. die Datenbankverbindung |
| `GET /actuator/info`                                                                                                                               | Deployte Version (`app.version` = Git-Tag)                                |

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

- **Pipeline** ([`.github/workflows/deploy.yml`](.github/workflows/deploy.yml)):
  `version` + `test` → `build` → `deploy-ecs` (Lab-Session prüfen, Image nach ECR, Task Definition rendern,
  ECS-Service rolling aktualisieren, Smoke-Test). `deploy-ecs` läuft bei Push auf `main` und bei Tags `v*.*.*`,
  nicht bei Pull Requests. Maven-Abhängigkeiten werden gecacht (`setup-java` und Docker-Layer-Cache
  über `docker/build-push-action` mit `type=gha`). Main- und Tag-Runs laufen nacheinander (`concurrency`).
- **Datenbank `db_biztrips`** legt das Backend beim ersten Start selbst an (`createDatabaseIfNotExist=true`),
  Tabellen über `ddl-auto=update`, Demo-Daten über den Seed in `BusinessTripsBackendApplication`.
- **CORS**: lokal `*`; auf ECS per `APP_CORS_ALLOWED_ORIGINS` auf die Frontend-ALB-URL eingeschränkt.
- **Rollen**: Learner Lab erlaubt keine eigenen IAM-Rollen → `LabRole` als Execution- und Task-Role.
- **Konfiguration nach Umgebung getrennt**: Default-Profil = H2 (Tests), Profil `mariadb` = lokale
  Defaults (`application-mariadb.properties`, nur für Docker Compose). Auf ECS überschreiben
  Umgebungsvariablen aus der Task Definition URL, CORS und Storage, User und Passwort kommen aus
  Secrets Manager. Im Repo stehen keine AWS-Zugangsdaten.

### Versionierung (Git-Tags → Image-Tag in ECR)

Versionen folgen [SemVer](https://semver.org/lang/de/) und werden als Git-Tag gesetzt. Der Job `version`
leitet daraus mit `git describe` den Image-Tag ab:

| Situation | Image-Tag in ECR | Beispiel |
| --- | --- | --- |
| Commit hat einen Release-Tag | `<tag>` | `v1.1.0` |
| Commits nach dem letzten Tag (Push auf `main`) | `<tag>-<anzahl>-g<sha>` | `v1.1.0-2-g3f9c2ab` |
| Noch kein Tag im Repo | `0.0.0-g<sha>` | `0.0.0-g623e120` |

Zusätzlich bekommt jedes Image den vollen Commit-SHA und `latest`. Die Version steckt auch im Image
(Label `org.opencontainers.image.version`) und ist im laufenden Container unter `GET /actuator/info`
abrufbar. So ist jederzeit klar, welcher Commit gerade auf ECS läuft.

Release erstellen:

```bash
git tag -a v1.1.0 -m "S3-Bilder-Storage, Health-Checks"
git push origin v1.1.0        # startet die Pipeline -> Image springboot-backend:v1.1.0
```

Frontend und Backend werden unabhängig versioniert. Welche Versionen zusammengehören, steht in der
Release-Tabelle oben im [Überblick](#überblick-refcard-05).

### Deployment absichern und überwachen

| Ebene | Mechanismus |
| --- | --- |
| Container | `healthCheck` in [`task-definition.json`](task-definition.json): `GET /actuator/health` alle 30 s, 120 s Startzeit |
| Load Balancer | Target Group prüft `/actuator/health`. Ein Task bekommt erst Traffic, wenn er gesund ist |
| ECS-Service | Rolling Update (min. 100 % / max. 200 %) mit **Circuit Breaker + Rollback**: wird der neue Task nicht gesund, rollt ECS auf die letzte funktionierende Task-Definition zurück |
| Pipeline | `wait-for-service-stability`, danach **Smoke-Test** über den ALB: `/actuator/health` = `UP` und `/actuator/info` meldet genau die neue Version. Sonst wird der Job rot |
| Ressourcen | Fargate 0.5 vCPU / 1 GB RAM (Spring Boot braucht ~350 MB Heap, Puffer für Lastspitzen) |

Abgelaufene Lab-Credentials erkennt der Schritt *Lab-Session prüfen* und bricht mit einer klaren
Fehlermeldung ab, statt erst beim ECR-Login zu scheitern.

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
- **Fehlerfälle**: Verweigert S3 den Zugriff (403, z. B. falsche Bucket-Policy oder abgelaufene
  Credentials) oder ist S3 nicht erreichbar, antwortet `/v1/images` mit `503` und
  `{"error": "Bilder-Storage nicht verfügbar (S3 PutObject)"}` statt mit einem rohen 500er. Im Log
  (CloudWatch `/ecs/biztrips-backend`) stehen Operation, Objekt und bei 403 der Hinweis auf
  Bucket-Policy und Task-Rolle. Fehlt `APP_STORAGE_S3_BUCKET`, bricht der Start sofort ab.
  Abgedeckt durch `S3ImageStorageTest` und `ImageControllerTest`.

**Was die Least-Privilege-Policy erlaubt** ([`scripts/s3-bucket-policy.json`](scripts/s3-bucket-policy.json)):

| Statement | Wirkung |
| --- | --- |
| `AllowBackendTaskRoleObjectAccess` | `LabRole` (Task-Rolle des Backends): nur `GetObject`, `PutObject`, `DeleteObject` auf `images/*` |
| `AllowBackendTaskRoleListImagesPrefix` | `LabRole`: `ListBucket` nur mit Prefix `images/` |
| `DenyEveryoneElse` | alle anderen Principals werden explizit gesperrt (Ausnahme: `voclabs` = Lab-Konsole, sonst sperrt man sich selbst aus) |
| `DenyInsecureTransport` | Zugriff nur über HTTPS |

Kein `s3:*`, kein Zugriff auf andere Buckets und kein Löschen des Buckets. In einem eigenen
AWS-Account würde man dieselben Rechte als IAM-Policy an eine eigene Task-Rolle hängen statt an `LabRole`.

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

Läuft die Session ab, bricht der Pipeline-Schritt *Lab-Session prüfen* mit einer klaren Meldung ab.
Dann Lab neu starten, Schritte 1–2 wiederholen und den fehlgeschlagenen Run mit *Re-run jobs* neu starten.
Die laufenden ECS-Services sind davon nicht betroffen: Sie nutzen `LabRole`, nicht die Secrets.

**Vergleich: Learner Lab vs. produktiver Account (OIDC, wie in EX-03)**

| | Learner Lab (hier) | Produktiv: OIDC |
| --- | --- | --- |
| Anmeldung in der Pipeline | statische Keys aus GitHub Secrets | GitHub stellt pro Run ein OIDC-Token aus, AWS tauscht es gegen kurzlebige Credentials (`role-to-assume`) |
| Gespeicherte Geheimnisse | 3 Secrets pro Repo | keine |
| Gültigkeit | Lab-Session (~4 h), danach manuell erneuern | pro Run, automatisch |
| Einschränkung | jeder mit den Keys hat die vollen `voclabs`-Rechte | Trust-Policy beschränkt auf Repo/Branch (`repo:Koetbullar-Droide/…:ref:refs/heads/main`), Rolle mit minimalen Rechten |
| Risiko bei Leak | Keys bis Sessionende nutzbar | nichts Statisches, das leaken kann |
| Voraussetzung | – | `iam:CreateOpenIDConnectProvider` + `iam:CreateRole` (im Learner Lab gesperrt) |

In einem produktiven Account wäre OIDC klar besser. Die Pipeline müsste dafür nur im Schritt
`configure-aws-credentials` die drei Keys durch `role-to-assume: arn:aws:iam::<account>:role/github-deploy`
ersetzen und `permissions: id-token: write` setzen.

## Nachweise (Screenshots)

Alle Screenshots liegen in [`docs/screenshots/`](docs/screenshots/).

| Was | Screenshot |
| --- | --- |
| Commits | ![Commits](docs/screenshots/be-01-commits.jpg) |
| Git-Tag `v1.0.0` | ![Tags](docs/screenshots/be-01-tags.jpg) |
| Pipeline-Run für `v1.0.0` (alle Jobs grün) | ![Workflow](docs/screenshots/be-02-workflow-run.jpg) |
| Run-Zusammenfassung: Version, Image in ECR, Smoke-Test OK | ![Workflow-Summary](docs/screenshots/be-02-workflow-summary.jpg) |
| GitHub Secrets (Lab-Credentials) | ![Secrets](docs/screenshots/be-03-secrets.jpg) |
| ECR-Repository: Image mit Tags `v1.0.0`, Commit-SHA, `latest` | ![ECR](docs/screenshots/be-04-ecr.jpg) |
| ECS-Service: Deployment mit Circuit Breaker und Health-Checks | ![ECS](docs/screenshots/be-05-ecs-service.jpg) |
| ECS-Task: Running, Health-Status *Healthy* | ![ECS-Task](docs/screenshots/be-06-ecs-task-health.jpg) |
| RDS-Instanz (MariaDB, verwaltetes Secret) | ![RDS](docs/screenshots/be-07-rds.jpg) |
| S3-Bucket: über die API hochgeladene Bilder unter `images/` | ![S3](docs/screenshots/be-08-s3-bucket.jpg) |
| S3-Bucket-Policy (Least Privilege) | ![S3-Policy](docs/screenshots/be-09-s3-policy.jpg) |
| API über den ALB: `/actuator/info` meldet die deployte Version | ![API](docs/screenshots/be-10-api.png) |
| Lokal mit Docker Compose: App auf `localhost:3000` | ![Compose App](docs/screenshots/compose-01-app.jpg) |
| Lokal mit Docker Compose: Backend-Health (MariaDB verbunden) | ![Compose Health](docs/screenshots/compose-02-health.png) |

Lokal `docker compose ps` nach dem Start:

```
SERVICE    STATUS                    PORTS
backend    Up 16 seconds (healthy)   0.0.0.0:8080->8080/tcp
frontend   Up 10 seconds (healthy)   0.0.0.0:3000->80/tcp
mariadb    Up 22 seconds (healthy)   3306/tcp
```

## Projektstruktur

```
src/main/java/ch/bbw/trips/   Applikationscode (Controller, Entities, Repositories, DTOs)
docker-compose.yml            Gesamtumgebung lokal: Frontend + Backend + MariaDB
docker/docker-compose.yml     nur MariaDB + phpMyAdmin (für ./mvnw spring-boot:run)
Dockerfile                    Multi-Stage-Build (Maven-Image baut das JAR → JRE-Alpine-Image)
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

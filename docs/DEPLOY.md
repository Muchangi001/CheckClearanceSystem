# Deploying CTS

How the app gets from this repo to a running URL, and the traps already hit
on the way. Secrets are never in this file: their values live in the local,
gitignored `.env` at the repo root.

## Shape

```
GitHub (Muchangi001/CheckClearanceSystem, main)
   │  SnapDeploy pulls and builds the Dockerfile
   ▼
SnapDeploy container (CPU, Small: 512 MB / 0.25 vCPU, free tier)
   │  JDBC over TLS, Supabase session pooler
   ▼
Supabase Postgres 17 (CTS project, eu-west-1; ref in .env)
```

SnapDeploy builds only the `Dockerfile`. It never runs `compose.yaml`, which
is for local development.

## Running locally

Docker is required. On this machine that means Docker installed **from apt
inside the Ubuntu WSL distro**, not snap: snap's Docker can only read files
under `/home`, and the repo lives on `/mnt/n/...`.

From a WSL terminal in the repo:

```bash
./mvnw spring-boot:run                  # starts Postgres from compose.yaml automatically
./mvnw test                             # Testcontainers, needs Docker
docker compose --profile app up --build # whole stack in containers, as deployed
```

Run these from WSL, not Windows. Windows-side Maven can't see the WSL
Docker daemon without extra configuration.

## SnapDeploy settings

| Field | Value |
|---|---|
| Repository | `CheckClearanceSystem` |
| Root directory / Dockerfile / build context | defaults (`/`, `Dockerfile`, `.`) |
| Start command | **empty**: anything here overrides the Dockerfile `ENTRYPOINT` |
| Port | `8080`, or empty. The app reads `PORT` if the platform sets it. |
| Compute | CPU, Small |
| PostgreSQL dialog | **"I'm using an external / hosted PostgreSQL"**. Never "Create PostgreSQL": that makes a second database the app never uses. |

### Environment variables

| Key | Where the value is | Secret |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `.env` | no |
| `SPRING_DATASOURCE_USERNAME` | `.env` | yes |
| `SPRING_DATASOURCE_PASSWORD` | `.env` | yes |
| `CTS_IMAGE_DIR` | `/data/images` (detected from the repo) | no |
| `PORT` | managed by SnapDeploy | no |

The URL uses Supabase's **session pooler** (`aws-0-eu-west-1.pooler.supabase.com:5432`),
not the direct `db.<ref>.supabase.co` host. The direct host is IPv6-only, and
container platforms usually connect over IPv4. The pooler username is
`postgres.<project-ref>`, not plain `postgres`.

## What happens on boot

1. Hikari opens a connection to Supabase. With no `SPRING_DATASOURCE_URL` the
   app exits immediately ("Failed to configure a DataSource"). This is
   intended: a payment system should not run without its database.
2. Flyway applies `src/main/resources/db/migration`.
3. Hibernate validates the schema against the entities (`ddl-auto: validate`).
   It never creates or alters tables. Only Flyway changes the schema.

Expect about a minute to boot on 0.25 vCPU. The free tier **auto-sleeps**
when idle, and a cold request takes the same minute. Wake it a few minutes
before anyone reviews it.

Until real users are seeded, Spring Security prints a generated password for
the user `user` in the container log on every boot.

## JVM sizing

The `ENTRYPOINT` is tuned for 512 MB:
`-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k`.
The heap is capped at about 300 MB, leaving room for metaspace and threads.
SerialGC and C1-only compilation favour startup time over peak throughput. On
a larger container, drop the last three flags and raise the percentage.

## Storage

`/data/images` is inside the container. On SnapDeploy it is **lost on every
redeploy or restart** unless a volume is attached, which is acceptable for
the MVP. Before real use, move cheque images to object storage (Supabase
Storage or S3) and keep only the reference and SHA-256 hash in Postgres.

## Redeploying

Push to `main`. SnapDeploy rebuilds if auto-deploy is on; otherwise redeploy
from its dashboard. The image build skips tests (Testcontainers needs Docker),
so run `./mvnw test` locally before pushing.

## Traps already hit

- **start.spring.io wrote `4.1.1.RELEASE`** as the Boot version. Maven Central
  publishes it as `4.1.1`, and the `.RELEASE` form fails to resolve the parent POM.
- **No Maven wrapper in the image build.** `mvnw` downloads and untars Maven,
  and SnapDeploy's build sandbox fails that with `tar: ... Cannot open: Function
  not implemented`. The build stage uses `maven:3.9-eclipse-temurin-21` instead.
- **No BuildKit features in the Dockerfile.** `# syntax=` and
  `RUN --mount=type=cache` were removed because a hosted builder may not support them.
- **Secrets:** tick "Secret" on the username and password in SnapDeploy.
  `.env` is gitignored. Never paste the values into commits, docs or issues.

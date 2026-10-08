# Deploying and running CTS

How the app gets from this repo to a running URL, how to run it locally, and
the traps already hit. No secret values appear here: they live in the local,
gitignored `.env` at the repo root and in the host's environment settings.

## Where it runs

```
GitHub  Muchangi001/CheckClearanceSystem  (main)
   │  push → Render builds the Dockerfile
   ▼
Render web service  https://checkclearancesystem.onrender.com   (Docker, free instance)
   │  JDBC over TLS through the Supabase session pooler
   ▼
Supabase Postgres 17   separate CTS project, eu-west-1, schema `cts`
```

This is a demo stack. Production belongs in an **Indian region** (for example
AWS Mumbai, `ap-south-1`): RBI requires payment system data to be stored only
in India. The app is portable: the same image, the same three database
variables, and Flyway builds the schema on first boot.

## Render

| Setting | Value |
|---|---|
| Service type | **Web Service** (not Private Service, not Postgres) |
| Repository / branch | `CheckClearanceSystem` / `main` |
| Runtime | **Docker** (detected from the `Dockerfile`) |
| Instance | Free |
| Health check path | `/actuator/health` |
| Auto-deploy | on: every push to `main` redeploys |

Don't create a Render Postgres. The database is Supabase.

### Environment variables

| Key | Value | Notes |
|---|---|---|
| `SPRING_DATASOURCE_URL` | in `.env` | `jdbc:postgresql://aws-0-eu-west-1.pooler.supabase.com:5432/postgres?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` | in `.env` | `postgres.<project-ref>`: the pooler needs the ref suffix |
| `SPRING_DATASOURCE_PASSWORD` | in `.env` | |
| `CTS_CLEARING_PHASE` | optional, `1` or `2` | item expiry rule; default `1` |
| `PORT` | set by Render | the app reads it (`server.port: ${PORT:8080}`) |

Render's "Add from .env" accepts the three `SPRING_DATASOURCE_*` lines pasted
as `KEY=value`. Paste only those: the Supabase API keys in `.env` aren't used
by the app.

The URL uses Supabase's **session pooler**, not the direct `db.<ref>.supabase.co`
host. The direct host is IPv6-only, and hosted containers connect over IPv4.

### Behaviour to expect

- **Cold starts.** The free instance sleeps after about 15 minutes idle; the
  next request waits roughly a minute while it boots. Open the URL a minute
  before anyone reviews it.
- **Redeploys.** A push rebuilds the image (about 5 minutes) and swaps the
  container. Don't push while the client is looking at it.
- **Sessions** are in memory, so a restart signs everyone out.

## What happens on boot

1. Hikari connects to Supabase. Without `SPRING_DATASOURCE_URL` the app exits
   at once ("Failed to configure a DataSource"). That's intended: a payment
   system must not run without its database.
2. Flyway creates the `cts` schema if needed and applies
   `src/main/resources/db/migration` (`V1__schema.sql`, `V2__seed.sql`).
3. Hibernate validates the entities against the tables (`ddl-auto: validate`).
   It never changes the schema; only Flyway does.
4. `SigningService` generates this boot's RSA key pair and stores the public
   half in `signing_key`, so items signed by earlier boots still verify.

## Running locally

### Without Docker (how the MVP was built and tested)

Run the packaged jar against the same Supabase database, using `.env`:

```bash
./mvnw -DskipTests package
set -a; . ./.env; set +a
java -jar target/cts-0.0.1-SNAPSHOT.jar      # http://localhost:8080
```

This shares the demo database with the deployed app: anything captured
locally shows up on Render.

### With Docker

Docker isn't installed on this machine. If you add it, install from apt
inside the Ubuntu WSL distro, not snap: snap's Docker can only read files
under `/home`, and the repo is on `/mnt/n/...`. Then, from WSL:

```bash
./mvnw spring-boot:run                    # starts Postgres from compose.yaml automatically
docker compose --profile app up --build   # the whole stack in containers, as deployed
./mvnw test                               # Testcontainers context test
```

## Testing status

The MVP was verified end to end against the real database: every role signed
in, every page rendered for the roles allowed to see it, and 403 for the rest.
A full clearing cycle was run: duplicate and stale rejection, maker-checker,
presentment, drawee returns 01/20/88/12, confirmation, settlement twice (the
second posted nothing), ledger balanced, and net positions summing to zero.
That run was done with `curl` scripts, not committed tests. The repo's only
automated test is the Spring context test, which needs Docker. Unit tests for
`MicrLine`, amount parsing, `ExpiryPolicy` and the drawee rules are the next
thing to add.

## Resetting the demo database

Drop the `cts` schema. On the next boot Flyway recreates it with the seed
data: demo users, banks, drawer accounts, the stop payment and the Positive Pay
registration. Nothing else lives in that schema. Reset after any rehearsal of
the README walkthrough, because the walkthrough uses up cheque `000310`.

Note that the Positive Pay seed is dated **the day the schema was created**,
and that date must match on capture.

## JVM sizing

The `ENTRYPOINT` is tuned for 512 MB:
`-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k`.
The heap is capped near 300 MB to leave room for metaspace and threads.
SerialGC and C1-only compilation favour startup time over peak throughput. On
a larger instance, drop the last three flags and raise the percentage.

## Storage

Cheque images are stored in Postgres (`cheque_image.data`), so they survive
redeploys on any host. Before real volumes, move them to object storage and
keep only the reference and SHA-256 hash in the database.

## Traps already hit

- **SnapDeploy was abandoned.** Its free tier allows 5 deploys per 12 hours,
  which ran out mid-build. Its build sandbox also couldn't untar the Maven
  wrapper (below). Render has neither limit.
- **The devforge VPS is not an option.** It has 1 vCPU and 934 MB of RAM,
  runs the devforge bot engine with live Deriv sockets, and accepts no
  inbound traffic by design. This app needs about 400 MB.
- **start.spring.io wrote `4.1.1.RELEASE`** as the Boot version. Maven Central
  publishes it as `4.1.1`; the `.RELEASE` form fails to resolve the parent POM.
- **Own schema, not `public`.** Supabase's `public` is non-empty, so Flyway
  refuses it ("Found non-empty schema(s) \"public\" but no schema history
  table"). It is also exposed through Supabase's REST API. Everything lives in
  `cts` (Flyway `schemas`, Hikari `schema`, Hibernate `default_schema`). Don't
  "fix" it with `baselineOnMigrate`.
- **No Maven wrapper in the image build.** `mvnw` downloads and untars Maven,
  and SnapDeploy's sandbox failed that with `tar: ... Cannot open: Function not
  implemented`. The build stage uses `maven:3.9-eclipse-temurin-21` instead.
- **No BuildKit features in the Dockerfile.** `# syntax=` and
  `RUN --mount=type=cache` were removed so any hosted builder can build it.
- **Redirects must be relative.** The platform proxy terminates TLS and didn't
  reliably forward the scheme, so Spring redirected to `http://`.
  `server.tomcat.use-relative-redirects: true` sends `Location: /login`, and
  the browser keeps `https`.
- **Postgres aborts the transaction on any failed statement.** Catching a
  duplicate-key error and carrying on doesn't work. Ledger idempotency uses
  `INSERT ... ON CONFLICT DO NOTHING` instead.
- **SpEL can't index a map by an enum value.** The dashboard keys its counts
  by status name.
- **Secrets:** never paste the values into commits, docs or issues. `.env` is
  gitignored; `docs/DOMAIN.md` is excluded locally and never pushed.

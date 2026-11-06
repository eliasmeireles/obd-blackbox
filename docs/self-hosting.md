# Self-hosting OBD Black Box

OBD Black Box receives OBD-II telemetry from one or several phones using ELM327-compatible adapters.
PostgreSQL stores sessions, samples, events, diagnostic snapshots and app logs. The API serves the dashboard
on the same origin. Each API-key name identifies a device. The deployment is intended for trusted devices
and operators sharing one collection of vehicle data; device filters are not tenant access controls.

## Docker Compose

Install Docker Engine with the Compose plugin, then run from the repository root:

```sh
cp .env.example .env
# Edit .env: replace API_KEYS, COOKIE_SECRET and POSTGRES_PASSWORD.
# Keep the password in DATABASE_URL synchronized with POSTGRES_PASSWORD.
docker compose up --build -d
```

Open <http://localhost:8080> and sign in with the key value after `phone:` in `API_KEYS`.
The example uses `COOKIE_SECURE=false` so dashboard cookies work over local HTTP.
PostgreSQL 17 has a named volume and is available only on the Compose network. The API waits for its
healthcheck, applies embedded migrations automatically, and exposes `/healthz` and `/readyz`.

For an external deployment, put a TLS reverse proxy in front of the API, set `APP_URL` to the public origin,
and set `COOKIE_SECURE=true`. Forward WebSocket upgrades for `/api/v1/ingest` and allow unbuffered SSE for
`/api/v1/live/stream`. Keep one API replica: live subscriptions and device commands use process-local state.
If changing `HTTP_ADDR` or port 8080, update the Compose port mapping and healthcheck as well.

```sh
docker compose logs -f api
docker compose ps
docker compose down
```

Stopping the stack preserves the database volume. Back up PostgreSQL before upgrading.
Migrations run once, transactionally, and retain existing telemetry. Old sessions receive an empty
`device` value; new session upserts derive it from the authenticated key name.

## Configuration

The application reads environment variables only. Missing required values or invalid settings stop startup
with messages naming the setting and without printing secret values.

| Variable | Default | Meaning |
|---|---|---|
| `DATABASE_URL` | Required | PostgreSQL connection URL. Use TLS when connecting across untrusted networks. |
| `API_KEYS` | Required | Unique comma-separated `name:key` pairs; key values are at least 32 bytes. Names must be non-empty and contain no spaces, tabs, newlines or `\|`. |
| `COOKIE_SECRET` | Required | At least 32 bytes for HMAC signing of dashboard sessions. |
| `COOKIE_SECURE` | `true` | Set to `false` for plain HTTP on localhost; use `true` behind HTTPS. |
| `HTTP_ADDR` | `:8080` | Address used by the HTTP listener. |
| `APP_URL` | `http://localhost:8080` | Canonical external HTTP(S) origin, without a path, query, credentials or fragment. Available to login providers as `Sessions.BaseURL()`. |
| `DISPLAY_TIMEZONE` | `UTC` | IANA timezone for CSV and readable log timestamps, e.g. `America/Sao_Paulo`. |
| `SESSION_TTL` | `720h` | Positive Go duration controlling dashboard cookie expiry. |
| `LOG_LEVEL` | `info` | Server log level: `debug`, `info`, `warn`, `error`. |
| `LOG_RETENTION` | `720h` | Positive duration to retain application logs by their logging timestamp. |
| `LOG_PRUNE_INTERVAL` | `1h` | Positive duration between log cleanup runs; cleanup also runs at startup. |
| `POSTGRES_DB` | `obd_blackbox` in Compose | Database created by the bundled PostgreSQL image; not read by the API. |
| `POSTGRES_USER` | `obd_blackbox` in Compose | Database user for the bundled PostgreSQL image; not read by the API. |
| `POSTGRES_PASSWORD` | Required by Compose | Bundled database password; must match `DATABASE_URL`. |
| `TEST_DATABASE_URL` | Unset | Test-only PostgreSQL URL. Repository tests skip when absent. |

Changing the database initialization variables does not change passwords or users in an existing volume.
Changing `COOKIE_SECRET` invalidates every dashboard session. Removing a key prevents new bearer requests
and API-key logins; previously signed dashboard cookies remain valid until expiry or secret rotation.

## Creating keys and connecting phones

Generate a different random key for each phone and a separate cookie signing secret. For example, run
`openssl rand -hex 32` once per credential, then configure:

```dotenv
API_KEYS=phone-a:replace-with-random-key-a-at-least-32-bytes,phone-b:replace-with-random-key-b-at-least-32-bytes
COOKIE_SECRET=replace-with-a-separate-random-secret-at-least-32-bytes
```

Restart the API after changing the environment. Key names and values must be unique; do not reuse a name
for phones that can connect concurrently because the most recent connection for a name replaces the old one.
A key grants telemetry access; using it to sign in creates a dashboard session which also permits commands
and history deletion. Give keys only to trusted devices and operators.

The Android app needs the server URL **at build time** and the device key in the app (Menu → Data → Server).
Build it with this deployment's reachable server URL (see [Build the app](../README.md#build-the-app)), then
enter the corresponding key value in the app. A phone cannot reach the server through its own `localhost`; use a
reachable host or LAN address. Plain `http://` is allowed only when the build URL itself is `http://`.
The app sends `Authorization: Bearer <key>` for HTTP and WebSocket ingestion. Session metadata and log
entries are attributed to the key name; client JSON cannot select a different device identity.
An ELM327-compatible OBD-II adapter is required for real telemetry; simulated sessions use `source=sim`.

Use the dashboard's device selector, or `device=phone-a` on history, diagnostics, logs and live requests.
Commands must name the device when several phones are connected. See [the API specification](backend-spec.md)
for request bodies, filters, authentication details and the complete endpoint list.

## Kubernetes

The Kustomize base includes a namespace, example Secret, API Deployment and Service, and an optional
PostgreSQL 17 StatefulSet with a persistent volume claim. The API runs as UID/GID 65532 with a read-only
root filesystem, resource limits, and startup/readiness/liveness probes. A default storage class is needed
for the bundled database's 10 GiB claim.

1. Copy/edit `deploy/k8s/secret.example.yaml`, replacing every placeholder. Keep the URL and database
   initialization password synchronized. Do not commit a populated Secret to a public repository.
2. Build `docker build -t obd-blackbox:local ./backend` and load that image into your cluster's local image
   store, or push an image to your own registry and change `deployment.yaml` accordingly.
3. Set the deployment's `APP_URL` to your public origin. Keep a single API replica.
4. Render and apply:

```sh
kubectl kustomize deploy/k8s
kubectl apply -k deploy/k8s
kubectl -n obd-blackbox rollout status deployment/obd-blackbox
```

`deploy/k8s/ingress.example.yaml` adds an optional TLS Ingress with the placeholder host `obd.example.com`.
Change the host, provide the referenced TLS Secret and configure your ingress controller, then use
`kubectl apply -f deploy/k8s/ingress.example.yaml`. The base itself does not create an external listener.
For local port forwarding, set `COOKIE_SECURE=false` and `APP_URL=http://localhost:8080` in the deployment,
then run `kubectl -n obd-blackbox port-forward service/obd-blackbox 8080:8080`.

To use an existing PostgreSQL server, remove `- postgres` from the base `kustomization.yaml` and set
`DATABASE_URL` in the application Secret to that server's connection URL. The database role must be able
to create tables and indexes and apply migrations. The `POSTGRES_*` initialization values are then unused.
The bundled StatefulSet is a basic single-instance example; production database availability and backups
are the operator's responsibility.

## Custom login

A private deployment can supply a login implementation without changing or importing internal packages.
Build a small Go executable importing:

```go
"github.com/eliasmeireles/obd-blackbox/backend/pkg/auth"
"github.com/eliasmeireles/obd-blackbox/backend/pkg/server"
```

`server.Run(ctx, ...server.Option)` starts the same backend. `server.WithLogin(provider)` installs:

```go
type LoginProvider interface {
    Mount(*http.ServeMux, auth.Sessions)
    Methods() auth.Methods
    AllowAPIKeyLogin() bool
}
```

`auth.Sessions` provides `Start(w, r, identity) error`, `End(w, r) error` and `BaseURL() string`.
`Start` signs a dashboard cookie for an identity which the provider has already authenticated and authorized.
The identity must be non-empty and contain neither `|` nor newlines. Mount provider routes outside the
reserved `/api/v1/auth/*`, `/login`, health and dashboard routes. The server owns logout and cookie cleanup;
providers can mount additional external logout routes if needed. Device bearer keys remain enabled even
when `AllowAPIKeyLogin()` returns false.

Here is a pseudo-OIDC provider. `VerifiedFlow` represents your chosen authentication implementation; its
`Begin`/`Verify` must handle discovery, code exchange, PKCE, state, nonce, token signature, issuer, audience,
expiry and authorization before returning an identity. They must not trust a subject sent in a query parameter.

```go
type VerifiedFlow interface {
    Begin(http.ResponseWriter, *http.Request, string)
    Verify(*http.Request) (string, error)
}
type Login struct{ Flow VerifiedFlow }

func (p Login) AllowAPIKeyLogin() bool { return false }
func (p Login) Methods() auth.Methods {
    return auth.Methods{
        Methods: []string{"oidc"}, LoginURL: "/auth/login", Label: "Entrar com SSO",
    }
}
func (p Login) Mount(mux *http.ServeMux, sessions auth.Sessions) {
    mux.HandleFunc("GET /auth/login", func(w http.ResponseWriter, r *http.Request) {
        p.Flow.Begin(w, r, sessions.BaseURL()+"/auth/callback")
    })
    mux.HandleFunc("GET /auth/callback", func(w http.ResponseWriter, r *http.Request) {
        identity, err := p.Flow.Verify(r)
        if err != nil {
            http.Error(w, "login failed", http.StatusUnauthorized)
            return
        }
        if err := sessions.Start(w, r, identity); err != nil {
            http.Error(w, "session unavailable", http.StatusInternalServerError)
            return
        }
        http.Redirect(w, r, "/", http.StatusSeeOther)
    })
}
// In your custom main, after configuring the verified flow:
// err := server.Run(context.Background(), server.WithLogin(Login{Flow: flow}))
```

The methods endpoint then advertises the external login URL and label, and `POST /api/v1/auth/login`
returns 404. Implementing a provider does not add a built-in external identity dependency to the project.

## Backups and restores

Keep regular PostgreSQL backups outside the container volume. A Compose backup can use:

```sh
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > obd-blackbox.dump
```

Restore into an empty database while the API is stopped, then start the API to apply any newer migrations:

```sh
docker compose stop api
docker compose exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --no-privileges' < obd-blackbox.dump
docker compose start api
```

The restore command assumes the target database is empty; an existing database needs a separately planned
restore procedure. In Kubernetes or with an external database, use `pg_dump`/`pg_restore` through your chosen
database access method and preserve the same tables, including `schema_migrations`. Test restores periodically.
Protect backups and `.env` because telemetry, diagnostic snapshots and logs can contain vehicle identifiers.

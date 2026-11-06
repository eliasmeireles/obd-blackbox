# OBD Black Box — backend specification

Receives telemetry from OBD-II devices using ELM327-compatible adapters, stores it in PostgreSQL,
and serves a live dashboard and analysis endpoints. Supports several independently named devices
in one self-hosted deployment. See [self-hosting.md](self-hosting.md) for installation.

## Stack and layout

Go 1.27.3, standard library `net/http` (method + path patterns), `github.com/jackc/pgx/v5` (pgxpool),
`log/slog` (JSON), `github.com/coder/websocket` v1.8.15 for ingestion,
`github.com/stretchr/testify` for tests, with no external login dependency.

```
backend/
  cmd/obd-blackbox/main.go         calls the public server.Run entrypoint
  pkg/server/                     public Run(ctx, ...Option), WithLogin(provider)
  pkg/auth/                       public LoginProvider and Sessions contracts
  internal/config/                env configuration
  internal/telemetry/             domain types, validation, service, live hub (fan-out of the latest sample)
  internal/storage/postgres/      repository on pgxpool + embedded SQL migrations + migrator
  internal/httpapi/               router, handlers, auth middleware, SSE, CSV export
  web/                            embedded dashboard (web.Static() returns its fs.FS) — separate task
  Dockerfile
```

Layering is strict: handler → service → repository, each depending on a narrow interface declared by the consumer.
Handlers never import the storage package. `main.go` only calls constructors.

## Configuration (environment)

| Variable | Default | Notes |
|---|---|---|
| `HTTP_ADDR` | `:8080` | |
| `DATABASE_URL` | — (required) | `postgres://user:pass@host:5432/db?sslmode=disable` |
| `API_KEYS` | — (required) | Comma-separated `name:key` pairs. Each key ≥ 32 bytes. Compared in constant time (SHA-256 digests). |
| `COOKIE_SECRET` | — (required) | ≥ 32 bytes. HMAC key for the dashboard session cookie. |
| `SESSION_TTL` | `720h` | Dashboard cookie lifetime. |
| `COOKIE_SECURE` | `true` | `false` only for local development over plain HTTP. |
| `DISPLAY_TIMEZONE` | `UTC` | Used for the CSV `timestamp` column. |
| `LOG_LEVEL` | `info` | |
| `APP_URL` | `http://localhost:8080` | Canonical external HTTP(S) origin for custom login redirects. |
| `LOG_RETENTION` | `720h` | Positive log retention duration. |
| `LOG_PRUNE_INTERVAL` | `1h` | Positive cleanup interval. |

Invalid or missing required configuration aborts start-up with a clear message (never print secret values).

## Authentication

- Devices send `Authorization: Bearer <key>`. `API_KEYS` contains unique `name:key` pairs; each name
  identifies one device. Key names and values must be unique. Keys contain at least 32 bytes.
- `POST /api/v1/auth/login` accepts `{"key":"…"}` without prior authentication. A valid key returns
  `{"name":"phone"}` and sets the signed `tm_session` cookie. An absent or wrong key returns 401.
  Digests use SHA-256 and constant-time comparisons; login responses retain a 300 ms delay.
- Cookies are `HttpOnly`, `SameSite=Strict`, and `Secure` unless `COOKIE_SECURE=false`; their identity
  is the API-key name, or a custom provider's verified identity. `SESSION_TTL` controls expiry.
- `GET /api/v1/auth/me` returns `{"name":"…"}` for an authenticated cookie or bearer key, otherwise 401.
- `POST /api/v1/auth/logout` expires the cookie and returns `{}`, including for expired sessions.
- Public `GET /api/v1/auth/methods` returns `{"methods":["api_key"]}` by default. A custom provider can
  replace it with `{"methods":["oidc"],"login_url":"/auth/login","label":"Entrar com SSO"}`.
- DELETE session routes and `POST /api/v1/commands` require a verified dashboard cookie. Bare device keys
  receive 403, including when a bearer header accompanies a cookie. No credentials returns 401.
- Other API routes accept either bearer keys or cookies. These credentials are for trusted operators
  and devices of one deployment: device filters organize data, and do not implement per-device read ACLs.
- `/healthz`, `/readyz`, `/login`, dashboard files, login/logout/methods and provider-mounted login routes
  are public. Other unauthorized API requests return JSON `{"error":"unauthorized"}` with status 401.
- A custom executable imports `pkg/server`, calls `Run(ctx, WithLogin(provider))`, and implements the
  public `pkg/auth.LoginProvider` interface. Providers mount their own routes, describe their methods,
  may disable API-key dashboard login, and start cookies through `auth.Sessions.Start` only after
  verifying the external identity. Device bearer authentication remains enabled. See
  [custom login](self-hosting.md#custom-login) for the complete extension contract and example.

## Signals

Sample values use the same keys as the app CSV columns:

`speed_kmh, rpm, coolant_c, load_pct, timing_deg, pedal_pct, throttle_pct, cmd_throttle_pct, map_kpa, intake_c,
stft_pct, ltft_pct, o2_b1s1_v, voltage_v, fuel_level_pct, fuel_rate_lph_est`

Known keys map to typed columns. Unknown keys with finite numeric values go to the `extra` JSONB column
(future signals, e.g. from CAN reverse engineering). Non-finite numbers (NaN, ±Inf) are rejected.

Alert ids (strings): `LOW_VOLTAGE, OVERHEAT, IDLE_DROP, NO_THROTTLE_RESPONSE, LEAN_MIXTURE, RICH_MIXTURE`.

## Database schema

```sql
CREATE TABLE sessions (
  id             UUID PRIMARY KEY,
  vehicle        TEXT NOT NULL DEFAULT 'obd-ii',
  device         TEXT NOT NULL DEFAULT '',
  source         TEXT NOT NULL CHECK (source IN ('sim', 'obd')),
  app_version    TEXT NOT NULL DEFAULT '',
  started_at     TIMESTAMPTZ NOT NULL,
  last_sample_at TIMESTAMPTZ,
  sample_count   BIGINT NOT NULL DEFAULT 0,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE samples (
  session_id        UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  t                 TIMESTAMPTZ NOT NULL,
  speed_kmh REAL, rpm REAL, coolant_c REAL, load_pct REAL, timing_deg REAL, pedal_pct REAL, throttle_pct REAL,
  cmd_throttle_pct REAL, map_kpa REAL, intake_c REAL, stft_pct REAL, ltft_pct REAL, o2_b1s1_v REAL,
  voltage_v REAL, fuel_level_pct REAL, fuel_rate_lph_est REAL,
  extra             JSONB,
  alerts            TEXT[] NOT NULL DEFAULT '{}',
  event             TEXT,
  PRIMARY KEY (session_id, t)
);
CREATE INDEX samples_t_brin ON samples USING BRIN (t);

CREATE TABLE events (
  session_id UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  t          TIMESTAMPTZ NOT NULL,
  label      TEXT NOT NULL,
  automatic  BOOLEAN NOT NULL,
  PRIMARY KEY (session_id, t, label)
);
CREATE INDEX events_t_idx ON events (t DESC);

CREATE TABLE dtcs (
  session_id UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  t          TIMESTAMPTZ NOT NULL,
  kind       TEXT NOT NULL CHECK (kind IN ('stored', 'pending', 'permanent')),
  code       TEXT NOT NULL,
  PRIMARY KEY (session_id, t, kind, code)
);
```

Migration `0004_devices.sql` adds `sessions.device TEXT NOT NULL DEFAULT ''` and an index on
`(device, started_at DESC)`. Existing sessions retain the empty device name. The application sets
`device` from the authenticated identity on REST upserts and WebSocket session messages; client-supplied
`device` fields cannot override it. New unspecified vehicle labels default to `obd-ii`.

Migrations are embedded (`embed.FS`), applied at start-up in order, each in a transaction, tracked in
`schema_migrations(version TEXT PRIMARY KEY, applied_at TIMESTAMPTZ)`, guarded by `pg_advisory_lock`.

## HTTP API (JSON, UTF-8; times are epoch milliseconds unless stated)

1. `PUT /api/v1/sessions/{id}` — idempotent upsert. `{id}` must be a UUID.
   Body `{"source":"sim","vehicle":"obd-ii","started_at":"2026-11-05T21:31:33-03:00","app_version":"0.1.0"}`.
   → 200 session object.
2. `POST /api/v1/sessions/{id}/samples` — body ≤ 2 MiB, ≤ 1000 samples:
   ```json
   {"samples":[{"t":1793925150857,"v":{"rpm":800.1,"voltage_v":14.1},"alerts":["IDLE_DROP"],
                "event":{"label":"marca_1","automatic":false}}]}
   ```
   Inserts with `ON CONFLICT (session_id, t) DO NOTHING` (the app retries batches from an offline queue, so
   duplicates are normal), inserts events, updates `last_sample_at` (max) and `sample_count`, all in one
   transaction. → 200 `{"accepted":n,"duplicates":d}`. 404 if the session does not exist (the app must PUT it
   first). 400 with the offending index for invalid samples (t ≤ 0, non-finite value, > 1000 samples).
   After commit, batches of at most 10 samples publish every sample in ascending `t` order to the live hub.
   Larger batches (offline backlog) publish only the newest sample. Source tagging is preserved; retries may
   publish duplicates to live subscribers even though duplicate rows are ignored in storage.
3. `POST /api/v1/sessions/{id}/dtcs` — `{"t":1793925150857,"kind":"stored","codes":["P0101"]}` → 200 `{"stored":n}`.
4. `GET /api/v1/sessions?limit=50&source=obd|sim&device=phone` (max 500) — newest first:
   `{"sessions":[{"id","device","source","vehicle","app_version","started_at","last_sample_at","sample_count","event_count"}]}`
   (`started_at`/`last_sample_at` as epoch ms, `last_sample_at` null when empty).
   Optional `source` restricts sessions to that source; omitted returns all. Invalid source → 400.
5. `GET /api/v1/sessions/{id}` — one session object, 404 if missing.
6. `GET /api/v1/sessions/{id}/samples?from=&to=&limit=&step=` — ordered by `t`. `limit` default 5000, max 50000.
   Optional `step` (ms ≥ 200) keeps the last sample of each time bucket (for charts).
   → `{"samples":[{"t":..,"v":{..},"alerts":[..],"event":"marca_1"|null}]}`; `v` contains only non-null values
   (typed columns plus `extra`).
7. `GET /api/v1/sessions/{id}/events` and `GET /api/v1/events?limit=100&source=obd|sim&device=phone` —
   `{"events":[{"session_id","t","label","automatic"}]}` newest first.
   Optional `source` filters by the event session source; omitted returns all. Invalid source → 400.
8. `GET /api/v1/sessions/{id}/dtcs` — `{"dtcs":[{"t","kind","code"}]}`.
9. `GET /api/v1/sessions/{id}/export.csv` — streamed CSV, header identical to the app:
   `timestamp,epoch_ms,speed_kmh,rpm,coolant_c,load_pct,timing_deg,pedal_pct,throttle_pct,cmd_throttle_pct,map_kpa,intake_c,stft_pct,ltft_pct,o2_b1s1_v,voltage_v,fuel_level_pct,fuel_rate_lph_est,alerts,event`
   (`timestamp` ISO-8601 with offset in `DISPLAY_TIMEZONE`, dot decimals, empty cell for null, alerts joined by
   `|`). `Content-Disposition: attachment; filename="obd-ii-<session id>.csv"`.
10. `GET /api/v1/live?source=obd|sim&device=phone` — `{"session_id":"…","device":"phone","source":"obd","sample":{...},"session":{...}}`
    for the most recently received sample of that source, 204 if none. `session` preserves the existing metadata.
    Omitted `source` returns the latest of any source; invalid source → 400.
11. `GET /api/v1/live/stream?source=obd|sim&device=phone` — Server-Sent Events. On connect send the current latest (if any) as
    `event: sample`; then every published sample as `event: sample` with data
    `{"session_id":"...","device":"phone","source":"obd","sample":{...}}`; a `: ping` comment every 15 s.
    Both the initial snapshot and subsequent samples match the requested source and device. Omitted source
    subscribes to all sources; omitted device subscribes to all devices; invalid source → 400. The hub never blocks publishers:
    slow subscribers miss samples (64-sample buffered channel, drop when full). Unsubscribe on client disconnect.
12. `GET /healthz` → 200 `ok`. `GET /readyz` → 200 when the database answers, else 503.
13. `GET /` and other paths → dashboard files from `web.Static()` (index.html for `/`).

14. `DELETE /api/v1/sessions/{id}` — 200 `{"deleted":1}`; 404 if missing. Samples, events, DTCs and diagnostics cascade.
15. `DELETE /api/v1/sessions?source=sim|obd|all&device=phone` — 200 `{"deleted":<sessions removed>}`. `all` removes all
    sessions. Missing, empty or invalid source → 400. Zero matching sessions returns `{"deleted":0}`.
    Both DELETE routes require a verified dashboard session cookie; device bearer keys
    receive 403 `{"error":"forbidden"}`. Unauthenticated requests receive 401.
    Successful deletes clear live snapshots and queued samples for every removed session. If the latest
    snapshot was deleted, `/live` returns 204 until another sample is published, including for filtered sources.

## Application logs

All log routes accept the same API key or dashboard cookie authentication as telemetry.
`POST /api/v1/logs` accepts `{"logs":[…]}` and returns 200 `{"accepted":N,"duplicates":M}`.
Each entry has this format (the optional `session_id` may also be null):

```json
{"id":"00000000-0000-0000-0000-000000000001","t":1793925150857,"level":"warn","tag":"elm","message":"adapter did not answer 010C","session_id":"00000000-0000-0000-0000-000000000002"}
```

A batch must contain 1–1000 entries. IDs must be UUIDs, `t` positive Unix milliseconds,
`level` one of `debug`, `info`, `warn`, `error`, and `tag` 1–32 characters matching `[a-z0-9_-]`.
`message` is required and truncated to 4000 Unicode characters. Invalid entries return 400 before any insertion.
The server takes `device` from the authenticated identity name, never from the body or API key value.
Migration `0002_app_logs.sql` stores logs in `app_logs`, with UUID primary key, device, logging timestamp,
level, tag, message, nullable session UUID and server receipt timestamp. Session IDs have no foreign key,
so logs can arrive before telemetry sessions. Batch insertion is atomic; repeated UUIDs are ignored.
Indexes on `t DESC` and `(device, t DESC)` support reading and retention.

`GET /api/v1/logs` returns `{"logs":[{"id","device","t","level","tag","message","session_id"}]}`.
Here `t` is RFC3339 with exactly three fractional digits in `DISPLAY_TIMEZONE`; absent session IDs are null.
`GET /api/v1/logs.txt` accepts the same filters and returns `text/plain; charset=utf-8`, one entry per line:

```text
2026-11-06T12:00:00.123-03:00 example WARN elm adapter did not answer 010C
```

CRLF, CR and LF inside messages are replaced by `↵`. Both read endpoints accept:

| Parameter | Meaning |
|---|---|
| `since`, `until` | Inclusive bounds, RFC3339 or Unix milliseconds; since must not exceed until. |
| `level` | Minimum severity; `warn` includes warn and error. |
| `tag`, `device`, `session_id` | Exact match; session ID must be a UUID. |
| `limit` | Default 500; range 1–5000. |
| `order` | `asc` (default) or `desc`, by logging time then receipt time. |

Invalid or explicitly empty parameters return 400. Empty results return `{"logs":[]}` or an empty text body.

The existing `/api/v1/ingest` WebSocket also accepts:

```json
{"type":"logs","seq":3,"logs":[{"id":"00000000-0000-0000-0000-000000000001","t":1793925150857,"level":"info","tag":"app","message":"connected"}]}
```

It shares HTTP validation and replies `{"type":"ack","seq":3,"accepted":1,"duplicates":0}`.
Invalid logs receive the usual error reply with status 400; the connection remains open.

Logs older than `LOG_RETENTION` (default 30 days) are pruned at startup and every `LOG_PRUNE_INTERVAL` (default one hour).
The worker is canceled and joined when the app stops. Cleanup failures are logged and retried next hour.

## WebSocket ingestion

`GET /api/v1/ingest` upgrades to a persistent WebSocket using the same authentication middleware as REST:
missing or invalid credentials return HTTP 401 before upgrade. Default origin checking rejects cross-site
browser handshakes (HTTP 403); clients without an `Origin` header are accepted. The server negotiates
`permessage-deflate` with context takeover when offered.

Send one JSON object per text message. Messages are processed strictly in connection order, one at a time:

```json
{"type":"session","seq":1,"id":"00000000-0000-0000-0000-000000000001","source":"sim","vehicle":"obd-ii","started_at":"2026-11-05T21:31:33-03:00","app_version":"0.1.0"}
{"type":"samples","seq":2,"session_id":"00000000-0000-0000-0000-000000000001","samples":[{"t":1793925150857,"v":{"rpm":800.1},"alerts":[],"event":null}]}
```

- `seq` is a required positive integer client counter (signed 64-bit); replies echo it.
- `session` shares the validation and idempotent upsert semantics of `PUT /api/v1/sessions/{id}`.
- `samples` shares parsing, validation and limits with `POST /api/v1/sessions/{id}/samples`: at most 1000
  samples, duplicates ignored, HTTP-equivalent 404 when the session does not exist. Live publishing follows
  the same 10-sample threshold as HTTP.

The server returns exactly one reply for each text message, in order:

```json
{"type":"ack","seq":1}
{"type":"ack","seq":2,"accepted":1,"duplicates":0}
{"type":"error","seq":2,"status":404,"error":"session not found"}
```

Errors use the equivalent HTTP status: 400 for invalid input or unknown `type`, 404 for unknown sessions,
500 with `"internal server error"` for unexpected failures (the real error is logged privately).
Malformed JSON returns `{"type":"error","seq":0,"status":400,"error":"invalid message"}`; a readable positive
`seq` before the malformed portion is echoed when recoverable. An error reply leaves the connection open.

Messages are limited to 2 MiB, including after decompression. Oversized messages close with WebSocket
`StatusMessageTooBig` (1009); binary messages close with `StatusUnsupportedData` (1003), without a JSON reply.
The server sends a ping every 20 seconds with a 10-second pong timeout; failed keepalive closes the connection.
Request cancellation during shutdown closes with `StatusGoingAway` (1001).
Each upgraded connection logs one summary at disconnect: authenticated identity name (never its key), duration,
messages handled and close reason. HTTP fallback endpoints remain available.

Errors are JSON `{"error":"message"}` with proper status codes. Request logging via slog (method, path, status,
duration; never log keys or cookies). Graceful shutdown on SIGINT/SIGTERM (10 s).

## Tests

Follow `golang.md`: testify `require`/`assert`, no conditionals inside test functions, camelCase names,
`t.Run("given … then …")` sub-tests, one test file per code file. Unit tests use fakes for the narrow
interfaces. Repository tests run against a real PostgreSQL only when `TEST_DATABASE_URL` is set (a helper
outside the test functions calls `t.Skip` otherwise).

## Container

Multi-stage `Dockerfile`: `golang:1.27.3-alpine` builder (`CGO_ENABLED=0`, `-trimpath -ldflags="-s -w"`), runtime
`alpine:3.22` with CA certificates, timezone data and UID/GID 65532, `EXPOSE 8080`, entrypoint the binary. Must build for `linux/arm64`.

## Full vehicle diagnostics

Migration `0003_diagnostics.sql` adds `diagnostics(session_id UUID NOT NULL REFERENCES sessions(id)
ON DELETE CASCADE, t TIMESTAMPTZ NOT NULL, data JSONB NOT NULL, PRIMARY KEY(session_id,t))` and an index on
`t DESC`. Each snapshot is a JSON object with a positive `t` (epoch ms) and a required `reason`:
`connect`, `manual`, `before_clear` or `after_clear`. The whole snapshot must be at most 64 KiB.
Every other field may be missing or null. Future fields are kept; the ECU does not need to support every
PID. The JSON is stored as is in JSONB (unknown fields are not dropped).

```json
{"t":1793981471775,"reason":"connect","adapter":"ELM327 v1.5","protocol":"7","ecu_id":"18DAF110","vin":null,"calibration_id":null,"ecu_name":null,"mil_on":true,"dtc_count":1,"monitors":[{"name":"misfire","available":true,"complete":true}],"fuel_system":"closed_loop","supported_pids":["01","03","04"],"values":{"run_time_s":512,"distance_mil_km":120,"baro_kpa":92,"o2_b1s2_v":0.65},"dtcs":{"stored":["P0130"],"pending":[],"permanent":[]},"freeze_frame":{"dtc":"P0130","values":{"rpm":850,"coolant_c":80}}}
```

`monitors[].name`: `misfire`, `fuel_system`, `components`, `catalyst`, `heated_catalyst`, `evap`,
`secondary_air`, `ac_refrigerant`, `o2_sensor`, `o2_heater`, `egr`. `available` and `complete` tell whether
the self-test is supported and finished. `fuel_system`: `open_loop_cold`, `closed_loop`, `open_loop_load`,
`open_loop_fault` or `closed_loop_fault`.

`values` and `freeze_frame.values` use the sample signal names, plus `run_time_s`,
`distance_mil_km`, `distance_since_clear_km`, `warmups_since_clear`, `baro_kpa`, `o2_b1s2_v`,
`relative_throttle_pct`, `throttle_b_pct`, `pedal_e_pct` and `obd_standard`. The dashboard shows
unknown keys by their own name. `freeze_frame` is the snapshot of the moment of the fault kept by the ECU;
the time shown in the history is when the snapshot was collected, not an independent time of the fault.

- `POST /api/v1/sessions/{id}/diagnostics`: body = the snapshot. Returns 200 `{"stored":1}`, or
  `{"stored":0}` when the same session and instant already exist (`ON CONFLICT DO NOTHING`). Returns 404
  for an unknown session and 400 for an invalid UUID, JSON, timestamp, reason or size.
- `GET /api/v1/diagnostics?source=obd|sim&device=phone&limit=20`: returns
  `{"diagnostics":[{"session_id":"…","t":1793981471775,"data":{…}}]}`, newest first.
  Default limit 20, maximum 200, minimum 1. Without `source` every source is included;
  an invalid or empty source is 400. No results: `{"diagnostics":[]}`.
- Ingestion WebSocket: `{"type":"diagnostics","seq":4,"session_id":"…","diagnostics":{…}}` uses the
  same validation and storage and returns `{"type":"ack","seq":4}`. Errors use the usual envelope,
  including 400 for snapshots over 64 KiB (the WebSocket envelope limit stays 2 MiB).

## Connected devices and remote commands

Each authenticated connection open on `/api/v1/ingest` joins an in-memory registry, keyed by identity name
(never by key). The most recent connection wins. A reconnection cancels the pending commands of the previous
one; closing an old connection does not remove the new one. On disconnect, pending commands fail
immediately. The registry is local to the process: the dashboard and the app must use the same instance.

`GET /api/v1/devices` accepts the usual authentication and returns every identity configured in
`API_KEYS`, plus those already seen in sessions or logs, sorted by name:

```json
{"devices":[{"name":"phone","connected":true,"connected_since":1793981471775,"last_seen":1793981471775}]}
```

`connected_since` and `last_seen` are epoch ms, or `null` when unknown. Devices without a connection
stay in the list with `connected:false` and `connected_since:null`. Past activity combines session times
and log receipt; the current connection updates `last_seen` when frames arrive.
Old sessions with an empty identity do not create a nameless device.

`POST /api/v1/commands` requires a dashboard cookie (`requireDashboardSession`); API keys get
403 and unauthenticated calls get 401. Body `{"name":"clear_dtcs","device":"phone"}` or
`{"name":"read_diagnostics","device":"phone"}`. With more than one device connected, omitting `device`
returns 400. With exactly one, the server picks that device. A missing or disconnected device
returns 409 `{"error":"the app is not connected"}`. An unknown name gets 400. The server sends:

```json
{"type":"command","id":"00000000-0000-4000-8000-000000000001","name":"clear_dtcs"}
```

The app answers on the same socket; `ok` is a required boolean, `command_id` is the UUID sent by the server
and `seq` follows the usual ingestion rules:

```json
{"type":"command_result","seq":5,"command_id":"00000000-0000-4000-8000-000000000001","ok":true,"message":"codes cleared"}
{"type":"ack","seq":5}
```

The HTTP request waits up to 90 seconds and returns 200 `{"ok":true,"message":"codes cleared"}` (or `ok:false`
with the app's message). No answer in time: 504 `{"error":"the app did not answer in time"}`.
Disconnection or replacement before the answer: 409. Late, repeated or unmatched results are
acknowledged and ignored. A result can only complete a command of the same connection that received it.
Each executed command logs the requester identity, device, name, result and duration through slog.
Commands can interleave with acks; the app must tell `type:"command"` apart from answers to its own
messages. Ingestion acks stay in order. `coder/websocket` serialises concurrent `Write` calls; the
connection keeps a single reader.

The app handles `read_diagnostics` by collecting a new snapshot. For `clear_dtcs` it must send and store
`before_clear` before clearing and send `after_clear` afterwards; the ignition-on, engine-off condition
is checked by the app and the ECU. The server forwards the command and keeps the snapshots it receives.

The dashboard has the **Vehicle** tab (snapshots per source, MIL, described DTCs, freeze frame, self-tests,
identification, readings and history) and the **Logs** tab (per device, independent of the source selector).
Vehicle polls the devices and enables commands for the selected connected device. Clearing codes
always asks for confirmation in a dialog explaining that the previous reading is kept, that the freeze frame and
codes are cleared, that the self-tests restart, that the lamp may come back and the ignition/engine condition.
Logs reads up to 1000 entries, newest first, with level, tag, period and local search filters and an
optional refresh every 5 s. Live keeps the existing tiles and adds every other signal with its known label and
unit, or its own key when unknown. All API text is inserted with DOM text APIs.

## Device filters

`device=<name>` is an optional exact match on `GET /sessions`, `/events`, `/live`, `/live/stream`,
`/diagnostics`, `/logs`, `/logs.txt` and `DELETE /sessions?source=…`. Combine it with `source` as needed.
An explicitly empty device parameter returns 400. Omission selects every device; unfiltered `/live`
returns the most recently received sample across devices, regardless of the sample timestamp.
The live hub stores snapshots and subscriptions by device plus source. A subscription for device A
never receives device B's samples. Session responses always include `device`, including the empty name
on legacy rows. Clearing history also clears matching live snapshots and queued events.

The in-memory live hub and command registry are local to one server process; deploy one API replica.
Increasing replicas requires shared fan-out and command routing, not just a shared database.

# OBD-II Bridge protocol (TMB/1)

Contract between the bridge (ESP32 + SN65HVD230 on the OBD port, or the fake bridge used for development) and the
Android app. The bridge only talks to the car; decoding, alerts, recording and upload stay in the app.

## Transport

- A byte stream: Bluetooth Classic SPP in the car, TCP for development (`adb reverse tcp:35000 tcp:35000`).
- UTF-8 text lines terminated by `\n` (a preceding `\r` is ignored). Maximum line length: 512 bytes, except `DIAG` (8192 bytes).
- Unknown lines are ignored by both sides, so new message types can be added without breaking older peers.

## Handshake

On every new connection the bridge sends first:

```
HELLO tmb/1 fw=<version> mode=<can|sim>
```

`mode=sim` means the data is simulated (fake bridge); `mode=can` means it comes from the car.

## Commands (app → bridge)

Every command is answered with `OK <COMMAND>` or `ERR <COMMAND> <reason>`.

| Command | Meaning |
|---|---|
| `PING` | Liveness check, answered with `PONG` (no `OK`). |
| `PIDS <hex>,<hex>,…` | Mode 01 PIDs to read every cycle, two hex digits each, 1 to 32 of them (e.g. `PIDS 0C,0D,05,42`). |
| `RATE <ms>` | Cycle period, 100 to 5000 ms. Default 200. |
| `START` | Start streaming samples. |
| `STOP` | Stop streaming samples. |
| `DIAG [reason]` | Read a complete diagnostic snapshot; default reason `manual`. |
| `DTC CLEAR` | Save diagnostics, clear mode 04, and read diagnostics again (see below). |
| `DTC` | Read stored (mode 03) and pending (mode 07) trouble codes. Answered with one `DTC` line, then `OK DTC`. Never clears codes. |

The app sends `RATE`, `PIDS` and `START` after every `HELLO`; the bridge does not remember them across connections.

## Messages (bridge → app)

### Sample

```
S <seq> <uptime_ms> <pid>=<data>[ <pid>=<data>…]
```

- `seq`: increments by one per sample since the bridge booted (gaps reveal dropped samples).
- `uptime_ms`: bridge uptime when the cycle started. The app stamps samples with its own clock.
- `<pid>`: two hex digits. `<data>`: the data bytes of the mode 01 reply after `41 <pid>`, as hex without spaces
  (`0C=1AF8` means A=0x1A, B=0xF8), or `NA` when the ECU did not answer that PID in this cycle.

Example: `S 42 123456 0C=0C80 0D=00 05=7B 42=3714 49=NA`

### Trouble codes

```
DTC stored=<code>,<code>… pending=<code>,…
```

Codes in the standard form (`P0101`, `C1234`, `B0001`, `U0100`). Empty lists are allowed: `DTC stored= pending=`.

### Status (every 5 s)

```
STATUS bus=<ok|silent|error> rx=<frames> tx=<frames> err=<errors>
```

`silent` means no CAN traffic was seen in the last 5 s (ignition off or wiring problem).

### Events

```
EV <name>[ <detail>]
```

Informational, e.g. `EV can_started 500000`, `EV bus_error`. The app logs them.

## Reserved for later

`SNIFF ON|OFF` and `F <id> <hex>` lines (raw CAN frames for reverse engineering) are reserved and must not be used
for anything else.

## Engine diagnostics

`DIAG [reason]` requests a full ECU snapshot; omitted reason defaults to `manual`. The bridge replies
`DIAG <json>` followed by `OK DIAG`, or `ERR DIAG <reason>` when it cannot communicate with the ECU.
After every ECU connection it also sends an unsolicited snapshot with reason `connect`.
Only `DIAG` message lines may contain up to **8192 bytes** (including the `DIAG ` prefix, excluding newline);
other message lines retain the 512-byte limit. Snapshot JSON uses UTF-8; receivers preserve unknown fields.

The JSON snapshot contains `t` (Unix milliseconds), `reason`, `adapter`, `protocol`, `ecu_id`, `vin`,
`calibration_id`, `ecu_name`, `mil_on`, `dtc_count`, `monitors` (objects with `name`, `available`, `complete`),
`fuel_system`, `supported_pids` (hex strings), `values` (numeric values by signal key), `dtcs`
(with `stored`, `pending`, `permanent` arrays), and optional `freeze_frame` (`dtc`, `values`).
Unavailable scalar fields are omitted; clients must distinguish unavailable data from a confirmed negative result.

`DTC CLEAR` reads and saves a snapshot with reason `before_clear`, sends it as `DIAG <json>`, runs OBD mode 04,
and sends a second snapshot with reason `after_clear` before `OK DTC CLEAR` or `ERR DTC CLEAR <reason>`.
If the first snapshot cannot be read or saved, mode 04 is **never** sent. The Android in-process ELM bridge
saves that snapshot durably in its upload queue before mode 04; upload can complete later when connectivity returns.
A refusal still produces an `after_clear` snapshot when the ECU is reachable. Reasons are human-readable text
in the app language, for example `the ECU only clears with the ignition on and the engine off`.

Both commands are queued and run between polling cycles on the adapter's worker thread. The app waits up to
60 seconds for the final result. Clear requests require user confirmation in the requesting UI (including the
website for remote requests). Mode 04 also erases freeze frames and resets readiness self-tests.

Snapshots are uploaded after session registration, as WebSocket messages
`{"type":"diagnostics","seq":1,"session_id":"<uuid>","diagnostics":{...}}`, with HTTP fallback
`POST /api/v1/sessions/{id}/diagnostics` (snapshot JSON body). A 404 re-registers the session; 400/413 drops
the invalid payload; other failures retain it for retry.
The ingest socket stays open while a server is configured, even without samples. Server messages
`{"type":"command","id":"<uuid>","name":"clear_dtcs"}` (or `read_diagnostics`) are independent of `seq`
replies. The app returns an acknowledged message
`{"type":"command_result","seq":2,"command_id":"<uuid>","ok":true,"message":"diagnostics read"}`.
The server waits up to 90 seconds; unknown commands and command timeouts return `ok:false`.

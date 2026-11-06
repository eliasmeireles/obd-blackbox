"""Pure per-client TMB/1 state machine."""

import re

import protocol
import pids
from engine import Engine


class Session:
    def __init__(self, engine: Engine, unsupported: set[str] | None = None):
        self.engine = engine
        self.pids = ["0C", "0D", "05", "42"]
        self.rate_ms = 200
        self.started = False
        self.unsupported = {p.upper() for p in unsupported or set()}
        self.seq = 0
        self.rx = self.tx = self.err = 0

    def handle(self, line: str, now: float) -> list[str]:
        command, args = protocol.parse_command(line)
        self.rx += 1
        if command != command.upper():
            return [protocol.error(command.upper(), "invalid_command")]
        if command == "PING" and not args:
            return [protocol.pong()]
        if command == "PIDS":
            joined = "".join(args)
            if len(args) != 1 or not protocol.PID_LIST_RE.fullmatch(joined):
                return [protocol.error(command, "invalid_pids")]
            self.pids = joined.upper().split(",")
            return [protocol.ok(command)]
        if command == "RATE":
            if len(args) != 1 or not args[0].isdigit() or not 100 <= int(args[0]) <= 5000:
                return [protocol.error(command, "invalid_rate")]
            self.rate_ms = int(args[0])
            return [protocol.ok(command)]
        if command in ("START", "STOP") and not args:
            self.started = command == "START"
            return [protocol.ok(command)]
        if command == "DTC" and not args:
            return [protocol.dtc_line(self.engine.stored, self.engine.pending), protocol.ok(command)]
        return [protocol.error(command, "unknown_command")]

    def next_sample(self, now: float) -> str | None:
        if not self.started:
            return None
        values = self.engine.values(now)
        # PIDs the simulated engine does not model are answered "NA", like a real ECU without that PID.
        encoded = [(pid, "NA" if pid in self.unsupported or pid not in values else (pids.encode(pid, values[pid]) or "NA"))
                   for pid in self.pids]
        self.seq += 1
        self.tx += 1
        return protocol.sample(self.seq, round(now * 1000), encoded)

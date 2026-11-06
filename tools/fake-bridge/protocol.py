"""TMB/1 line parsing and formatting helpers."""

import re

PID_LIST_RE = re.compile(r"[0-9A-Fa-f]{2}(?:,[0-9A-Fa-f]{2}){0,31}\Z")
DTC_RE = re.compile(r"[PCBU][0-9A-F]{4}\Z")


def parse_command(line: str) -> tuple[str, list[str]]:
    """Return an uppercase command and its whitespace-separated arguments."""
    parts = line.strip().split()
    return (parts[0], parts[1:]) if parts else ("", [])


def hello(version: str = "0.1.0") -> str:
    return f"HELLO tmb/1 fw={version} mode=sim"


def sample(seq: int, uptime_ms: int, values: list[tuple[str, str]]) -> str:
    suffix = " ".join(f"{pid}={value}" for pid, value in values)
    return f"S {seq} {uptime_ms}" + (f" {suffix}" if suffix else "")


def dtc_line(stored: list[str], pending: list[str]) -> str:
    return f"DTC stored={','.join(stored)} pending={','.join(pending)}"


def status(bus: str, rx: int, tx: int, err: int) -> str:
    return f"STATUS bus={bus} rx={rx} tx={tx} err={err}"


def ok(command: str) -> str:
    return f"OK {command}"


def error(command: str, reason: str) -> str:
    return f"ERR {command or 'UNKNOWN'} {reason}"


def pong() -> str:
    return "PONG"

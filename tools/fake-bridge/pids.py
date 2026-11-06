"""Encode physical mode 01 readings into ECU response data bytes."""

from collections.abc import Callable


def _byte(value: float) -> int:
    return max(0, min(255, round(value)))


def _single(value: float) -> str:
    return f"{_byte(value):02X}"


def _rpm(value: float) -> str:
    raw = max(0, min(65535, round(value * 4)))
    return f"{raw:04X}"


def _voltage(value: float) -> str:
    raw = max(0, min(65535, round(value * 1000)))
    return f"{raw:04X}"


ENCODERS: dict[str, Callable[[float], str]] = {
    "04": lambda v: _single(v * 255 / 100),
    "05": lambda v: _single(v + 40),
    "06": lambda v: _single(v * 128 / 100 + 128),
    "07": lambda v: _single(v * 128 / 100 + 128),
    "0B": _single,
    "0C": _rpm,
    "0D": _single,
    "0E": lambda v: _single((v + 64) * 2),
    "0F": lambda v: _single(v + 40),
    "11": lambda v: _single(v * 255 / 100),
    "14": lambda v: f"{_byte(v * 200):02X}FF",
    "2F": lambda v: _single(v * 255 / 100),
    "42": _voltage,
    "49": lambda v: _single(v * 255 / 100),
    "4C": lambda v: _single(v * 255 / 100),
}


def encode(pid: str, value: float) -> str | None:
    """Return uppercase response bytes, or None for unsupported PIDs."""
    encoder = ENCODERS.get(pid.upper())
    return encoder(value) if encoder else None

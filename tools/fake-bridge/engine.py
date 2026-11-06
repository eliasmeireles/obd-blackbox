"""Deterministic, seedable simulated OBD-II powertrain."""

import math
import random


class Engine:
    LOOP_SECONDS = 76.0

    def __init__(self, seed: int | None = None):
        self.random = random.Random(seed)
        self.started = 0.0
        self.scenario = "normal"
        self.scenario_at = 0.0
        self.stored: list[str] = []
        self.pending: list[str] = []

    def set_scenario(self, name: str, now: float) -> None:
        self.scenario, self.scenario_at = name, now

    def values(self, now: float) -> dict[str, float]:
        t = max(0.0, now - self.started)
        phase = t % self.LOOP_SECONDS
        if phase < 20:
            rpm, speed, throttle, load, map_kpa = 800, 0, 8, 18, 32
        elif phase < 32:
            p = (phase - 20) / 12
            speed, throttle, load, map_kpa = 100 * p, 35 + 35 * p, 35 + 40 * p, 45 + 35 * p
            rpm = 1000 + 3500 * p - (500 if int(p * 5) % 2 else 0)
        elif phase < 62:
            speed, rpm, throttle, load, map_kpa = 100, 2200, 24, 32, 62
        else:
            p = (phase - 62) / 14
            speed, rpm, throttle, load, map_kpa = 100 * (1-p), 2200 * (1-p) + 800 * p, 20 * (1-p) + 8 * p, 30 * (1-p) + 18 * p, 55 * (1-p) + 32 * p

        coolant = min(92.0, 58 + t * 0.035)
        voltage = 14.1 if rpm > 0 else 12.4
        trim_short = 3 * math.sin(t * 0.7)
        o2 = 0.45 + 0.4 * math.sin(t * 2.1)
        if self.scenario == "lowvolt":
            voltage = max(12.2, 14.1 - (now - self.scenario_at) * 0.015)
        elif self.scenario == "stall":
            elapsed = now - self.scenario_at
            if elapsed < 12:
                p = max(0, elapsed / 12)
                rpm = max(0, 780 * (1-p))
                speed = min(speed, max(0, 30 * (1-p)))
                throttle = 35 if elapsed >= 7 else 8
                map_kpa = 96 if elapsed >= 7 else 32
                trim_short = min(25, max(-3, (elapsed - 7) * 6)) if elapsed >= 7 else 0
                o2 = 0.08 if elapsed >= 7 else 0.35
                if rpm < 500:
                    voltage = max(12.4, 14.1 - (500-rpm) * 0.0034)
            elif elapsed < 18:
                rpm, speed, voltage, map_kpa = 0, 0, 12.4, 96
            else:
                self.scenario = "normal"

        noise = self.random.uniform
        vals = {"04": load, "05": coolant, "06": trim_short, "07": 2.3, "0B": map_kpa,
                "0C": rpm, "0D": speed, "0E": 10, "0F": 26, "11": throttle,
                "14": o2, "2F": 55, "42": voltage, "49": throttle, "4C": throttle}
        for pid in ("04", "05", "0B", "0C", "0D", "11", "14", "42"):
            vals[pid] += noise(-0.4, 0.4)
        vals["0C"] = max(0.0, vals["0C"])
        return vals

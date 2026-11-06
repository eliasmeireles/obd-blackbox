import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import pids
from engine import Engine
from session import Session
from fake_bridge import apply_console


DECODERS = {
    "04": lambda b: b[0] * 100 / 255,
    "05": lambda b: b[0] - 40,
    "06": lambda b: (b[0] - 128) * 100 / 128,
    "07": lambda b: (b[0] - 128) * 100 / 128,
    "0B": lambda b: b[0],
    "0C": lambda b: int.from_bytes(b, "big") / 4,
    "0D": lambda b: b[0],
    "0E": lambda b: b[0] / 2 - 64,
    "0F": lambda b: b[0] - 40,
    "11": lambda b: b[0] * 100 / 255,
    "14": lambda b: b[0] / 200,
    "2F": lambda b: b[0] * 100 / 255,
    "42": lambda b: int.from_bytes(b, "big") / 1000,
    "49": lambda b: b[0] * 100 / 255,
    "4C": lambda b: b[0] * 100 / 255,
}


class PidTests(unittest.TestCase):
    def test_encoding_round_trips_within_one_byte_lsb(self):
        samples = {
            "04": [0, 35, 100], "05": [-40, 58, 92, 215], "06": [-100, -3, 25, 99],
            "07": [-100, 2.3, 99], "0B": [0, 32, 96, 255], "0C": [0, 780, 2200, 8191.75],
            "0D": [0, 30, 100, 255], "0E": [-64, 0, 10, 63.5], "0F": [-40, 26, 215],
            "11": [0, 8, 35, 100], "14": [0.05, 0.45, 0.85, 1.275],
            "2F": [0, 55, 100], "42": [0, 12.4, 14.1, 65.535],
            "49": [0, 35, 100], "4C": [0, 35, 100],
        }
        for pid, values in samples.items():
            for value in values:
                with self.subTest(pid=pid, value=value):
                    data = bytes.fromhex(pids.encode(pid, value))
                    self.assertLessEqual(abs(DECODERS[pid](data) - value), 1)


class SessionTests(unittest.TestCase):
    def setUp(self):
        self.engine = Engine(seed=1)
        self.session = Session(self.engine)

    def test_commands(self):
        self.assertEqual(self.session.handle("PING", 0), ["PONG"])
        self.assertEqual(self.session.handle("PIDS 0C,0D,05,42", 0), ["OK PIDS"])
        self.assertEqual(self.session.pids, ["0C", "0D", "05", "42"])
        self.assertTrue(self.session.handle("PIDS 0C,GG", 0)[0].startswith("ERR PIDS"))
        self.assertEqual(self.session.handle("RATE 100", 0), ["OK RATE"])
        self.assertTrue(self.session.handle("RATE 99", 0)[0].startswith("ERR RATE"))
        self.assertEqual(self.session.handle("START", 0), ["OK START"])
        self.assertIsNotNone(self.session.next_sample(0.2))
        self.assertEqual(self.session.handle("STOP", 0), ["OK STOP"])
        self.assertIsNone(self.session.next_sample(0.4))
        self.assertEqual(self.session.handle("DTC", 0), ["DTC stored= pending=", "OK DTC"])
        self.assertTrue(self.session.handle("BOGUS", 0)[0].startswith("ERR BOGUS"))

    def test_sample_format_and_unsupported(self):
        session = Session(self.engine, {"49"})
        session.pids = ["0C", "49"]
        session.started = True
        self.assertRegex(session.next_sample(1.25), r"^S 1 1250 0C=[0-9A-F]{4} 49=NA$")


class EngineTests(unittest.TestCase):
    def test_stall_reaches_zero_and_restarts(self):
        engine = Engine(seed=2)
        engine.set_scenario("stall", 0)
        self.assertEqual(engine.values(12)["0C"], 0)
        self.assertEqual(engine.values(17)["0C"], 0)
        self.assertGreater(engine.values(18)["0C"], 0)

    def test_low_voltage_and_console_dtcs(self):
        engine = Engine(seed=3)
        engine.set_scenario("lowvolt", 0)
        self.assertLess(engine.values(130)["42"], 14)
        self.assertEqual(apply_console("dtc add P0101", engine, 1), "DTC add: P0101.")
        self.assertEqual(apply_console("dtc pending P0300", engine, 1), "DTC pending: P0300.")
        self.assertEqual(engine.stored, ["P0101"])
        self.assertEqual(engine.pending, ["P0300"])
        apply_console("dtc clear", engine, 1)
        self.assertEqual((engine.stored, engine.pending), ([], []))


if __name__ == "__main__":
    unittest.main()


class ConsoleClockTests(unittest.TestCase):
    def test_console_stall_uses_the_same_clock_as_samples(self):
        from fake_bridge import apply_console

        engine = Engine(seed=1)
        engine.started = 1000.0
        apply_console("stall", engine, 1050.0)
        self.assertLess(engine.values(1056.0)["0C"], 600)
        self.assertEqual(engine.values(1063.0)["0C"], 0.0)
        engine.values(1069.0)
        self.assertEqual(engine.scenario, "normal")

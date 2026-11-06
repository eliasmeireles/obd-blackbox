"""Async TCP development stand-in for the OBD-II OBD bridge."""

import argparse
import asyncio
import re
import sys
import time

import protocol
from engine import Engine
from session import Session

CODE_RE = re.compile(r"[PCBU][0-9A-F]{4}\Z")


async def serve_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter,
                       engine: Engine, unsupported: set[str], clients: set[Session]) -> None:
    session = Session(engine, unsupported)
    clients.add(session)
    start = time.monotonic()
    next_sample = start
    try:
        writer.write((protocol.hello() + "\n").encode())
        await writer.drain()
        next_status = start + 5
        while not reader.at_eof():
            deadline = min(next_status, next_sample if session.started else next_status)
            timeout = max(0.01, deadline - time.monotonic())
            try:
                raw = await asyncio.wait_for(reader.readline(), timeout)
            except asyncio.TimeoutError:
                raw = b""
            if raw:
                if len(raw) > 512:
                    session.err += 1
                    writer.write(b"ERR UNKNOWN line_too_long\n")
                else:
                    replies = session.handle(raw.decode("ascii", errors="replace").rstrip("\r\n"), time.monotonic())
                    writer.writelines((line + "\n").encode() for line in replies)
            now = time.monotonic()
            if session.started and now >= next_sample:
                sample_line = session.next_sample(now)
                if sample_line:
                    writer.write((sample_line + "\n").encode())
                next_sample = now + session.rate_ms / 1000
            if now >= next_status:
                bus = "silent" if engine.values(now)["0C"] <= 0 else "ok"
                writer.write((protocol.status(bus, session.rx, session.tx, session.err) + "\n").encode())
                next_status = now + 5
            await writer.drain()
            if raw == b"":
                await asyncio.sleep(0)
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        clients.discard(session)
        writer.close()
        await writer.wait_closed()


def apply_console(line: str, engine: Engine, now: float) -> str:
    parts = line.strip().split()
    if not parts:
        return ""
    command = parts[0].lower()
    if command in ("stall", "lowvolt", "normal") and len(parts) == 1:
        engine.set_scenario(command, now)
        return f"Scenario: {command}."
    if command == "dtc" and len(parts) == 2 and parts[1].lower() == "clear":
        engine.stored.clear()
        engine.pending.clear()
        return "DTCs cleared."
    if command == "dtc" and len(parts) == 3 and parts[1].lower() in ("add", "pending"):
        code = parts[2].upper()
        if CODE_RE.fullmatch(code):
            target = engine.stored if parts[1].lower() == "add" else engine.pending
            if code not in target:
                target.append(code)
            return f"DTC {parts[1].lower()}: {code}."
    if command == "help" and len(parts) == 1:
        return "Commands: stall, lowvolt, normal, dtc add CODE, dtc pending CODE, dtc clear, help, quit."
    if command == "quit" and len(parts) == 1:
        return "Shutting down."
    return "Invalid command. Type help."


async def console(engine: Engine, stop: asyncio.Event) -> None:
    loop = asyncio.get_running_loop()
    while not stop.is_set():
        line = await loop.run_in_executor(None, sys.stdin.readline)
        if not line:
            return
        message = apply_console(line, engine, time.monotonic())
        if message:
            print(message, flush=True)
        if line.strip().lower() == "quit":
            stop.set()


async def run(args: argparse.Namespace) -> None:
    engine = Engine(args.seed)
    # One clock for every client and the console: all of them see the same car at the same moment.
    engine.started = time.monotonic()
    clients: set[Session] = set()
    stop = asyncio.Event()
    server = await asyncio.start_server(lambda r, w: serve_client(r, w, engine, set(args.unsupported), clients), args.host, args.port)
    print(f"Fake bridge on {args.host}:{args.port}", flush=True)
    console_task = asyncio.create_task(console(engine, stop))
    async with server:
        await stop.wait()
        server.close()
        await server.wait_closed()
    if not console_task.done():
        console_task.cancel()
    await asyncio.gather(console_task, return_exceptions=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Fake bridge TMB/1")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=35000)
    parser.add_argument("--unsupported", default="", help="comma-separated PIDs that answer NA")
    parser.add_argument("--seed", type=int, default=None)
    args = parser.parse_args()
    args.unsupported = [pid.strip().upper() for pid in args.unsupported.split(",") if pid.strip()]
    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

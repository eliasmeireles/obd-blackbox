# Fake bridge

A TCP server that speaks the TMB/1 bridge protocol ([`docs/bridge-protocol.md`](../../docs/bridge-protocol.md)) with a
simulated car, so the app can be developed without a vehicle or an adapter.

```sh
python3 tools/fake-bridge/fake_bridge.py
```

It listens on `127.0.0.1:35000` by default. Use `--host`, `--port`, `--seed` and `--unsupported 49,4C` to change
the address, make the simulation repeatable and simulate PIDs that the car does not answer. Each connection has its
own settings; all of them share the same car.

Point a debug build of the app to it through `adb` (`-S` restarts the app so it reads the setting):

```sh
adb reverse tcp:35000 tcp:35000
adb shell am start -S -n io.github.eliasmeireles.obdblackbox/.ui.MainActivity --es bridge_tcp localhost:35000
# --es bridge_tcp off goes back to the Bluetooth adapter
```

Simulation mode (Menu → Data) takes precedence over the bridge; turn it off to read the fake bridge.

Type in the console:

- `stall`: the engine stalls and restarts.
- `lowvolt`: the alternator fails.
- `normal`: back to the normal drive cycle.
- `dtc add P0101` or `dtc pending P0300`: adds a trouble code.
- `dtc clear`: clears the codes.
- `help`: lists the commands; `quit`: exits.

Tests: `python3 -m unittest discover -s tools/fake-bridge/tests`.

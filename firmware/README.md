# Firmware (planned)

An ESP32 + CAN transceiver (e.g. SN65HVD230) bridge that speaks the TMB/1 protocol described in
[`docs/bridge-protocol.md`](../docs/bridge-protocol.md), as an alternative to a ready-made ELM327 adapter.

It is not written yet. Until then the app works with any ELM327-compatible adapter (Bluetooth Classic or BLE), and
[`tools/fake-bridge`](../tools/fake-bridge) simulates a TMB/1 bridge for development.

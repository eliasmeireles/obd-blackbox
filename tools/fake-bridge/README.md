# Fake bridge

Simulador TCP TMB/1 para desenvolver o app Android sem o ESP32.

```sh
python3 tools/fake-bridge/fake_bridge.py
```

Por padrão escuta em `127.0.0.1:35000`. Use `--host`, `--port`, `--seed` e
`--unsupported 49,4C` para configurar o servidor e simular PIDs sem resposta.
Cada conexão tem sua própria configuração; todas compartilham o mesmo carro.

Digite no console:

- `stall`: simula a falha e a retomada do motor.
- `lowvolt`: simula falha do alternador.
- `normal`: volta ao ciclo de condução.
- `dtc add P0101` ou `dtc pending P0300`: adiciona um código.
- `dtc clear`: limpa os códigos.
- `help`: mostra os comandos; `quit`: encerra.

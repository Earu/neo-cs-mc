# neo-chatsounds (Minecraft Edition:tm:)

Memes ported into your own Minecraft free of charge.

A Kotlin port of [neo-chatsounds](https://github.com/Earu/neo-chatsounds) for **NeoForge** and **Fabric** (MC 1.21.1; other branches: `1.21.11` (default), `1.20.1`).

- Works client-side on any server. An optional server component adds spam control, repo sync and hearing range.
- Same parser and all 18 modifiers as the original, legacy syntax and `[expr]` expressions included.
- Sounds download on demand from GitHub repos you configure. The mod ships no audio and no repos.
- Chat autocomplete with Tab cycling.
- Reverb and occlusion via Sound Physics Remastered or Dynamic Surroundings when installed.

## Usage

Type sound triggers in chat, that's it. `standing here%50` plays for everyone nearby running the mod. `sh` stops sounds.

Sound repos go in `config/chatsounds/repo_config.json`, see [repo_config.example.json](repo_config.example.json) (GMod-compatible format). Other settings live in `client_config.json` and `server_config.json` next to it.

## Commands

| Command | Effect |
|---|---|
| `/chatsounds toggle` / `volume <0-4>` / `hidetext` / `shmode <0-2>` / `invertprefix` | client settings |
| `/chatsounds block/unblock sound <index> <key>` (or `realm`, `repository`) | blacklist |
| `/chatsounds reload` / `reloadfull` / `clearcache` | maintenance |

## Building

Requires JDK 21 (plus JDK 17 on the `1.20.1` branch).

```sh
./gradlew build                  # everything + tests
./gradlew :neoforge:runClient    # NeoForge dev client
./gradlew :fabric:runClient      # Fabric dev client
```

Jars land in `neoforge/build/libs/` and `fabric/build/libs/`. `common/` holds the loader-agnostic logic, `neoforge/` and `fabric/` the thin loader shells.
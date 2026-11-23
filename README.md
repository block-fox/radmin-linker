# Radmin Linker

![Radmin Linker](docs/radmin-linker-cover.png)

**English** · [简体中文](README.zh-CN.md) · [Tiếng Việt](README.vi.md) · [Русский](README.ru.md) · [Українська](README.uk.md) · [日本語](README.ja.md)

Radmin Linker is a client-side Fabric mod for connecting to and managing Radmin VPN from Minecraft. It supports Minecraft **26.3, 26.2, 1.21.11, and 1.21.1**. Choose the JAR for your game version from [GitHub Releases](https://github.com/block-fox/radmin-linker/releases). See the [changelog](CHANGELOG.md) for release notes.

## Installation and integration

- **Radmin Linker on its own** gives you tabs for networks, users, blocked users, and settings. Install Fabric API as well. The standard build uses the official Windows service. The `-core.jar` build in automatic mode uses the official service when it is available at startup on Windows and otherwise uses the embedded core. See the [embedded core guide (Chinese)](docs/core-integration.md).
- **LanWorlds on its own** adds room icons, MOTDs, addresses, player counts, latency, and block rules to the vanilla LAN list. See [LanWorlds](https://github.com/block-fox/LanWorlds).
- **Both mods together** add Radmin user, network, and connection information to matching LAN rooms, with shortcuts to user and network properties. Use builds for the same Minecraft version. The integration is available with Radmin Linker 1.0.1 and LanWorlds 1.0.2.

If you are upgrading from RadminLAN, remove the old JAR first. On startup, Radmin Linker reads `config/radminlan.json` if its new config does not exist, then saves the settings to `config/radminlinker.json`.

## Features

- Choose from eight views for networks and users, with sorting, filtering, and multi-select. Opening a network's member list applies its filter automatically.
- See the success and failure counts for batch operations and retry the failed items. Auto-refresh preserves the operation feedback.
- Edit your nickname and switch the VPN on or off from the top of the management screen. Turning it off asks for confirmation.
- Read networks, users, and measured latency from the Windows service through its control pipe. A configured CLI can provide a fallback for queries.
- Check online status at a glance. Hover over the status icon for details. With auto-refresh enabled, service updates for presence and latency reach the UI every two game ticks. Full queries refresh network and member details at a configurable interval of 1–60 seconds. Latency is unknown until a measurement arrives and expires after 60 seconds.
- Scroll through lists, settings, and property pages in smaller windows. Open settings through Mod Menu or the multiplayer screen.

## Development

Minecraft 26.2 and 26.3 share the root source tree and use JDK 25. Versions 1.21.11 and 1.21.1 use JDK 21 and share their backend and configuration code. Run the development client with the script for your target version.

```powershell
./scripts/run-26.3.ps1 -JavaHome '<JDK 25 path>' -Online
./scripts/run-26.2.ps1 -JavaHome '<JDK 25 path>' -Online
./scripts/run-1.21.11.ps1 -JavaHome '<JDK 21 path>' -Online
./scripts/run-1.21.1.ps1 -JavaHome '<JDK 21 path>' -Online
```

Use the corresponding `scripts/build-*.ps1` script to build. Artifacts go to `versions/<minecraft-version>/build/`. Close the development client before building or changing versions. Omit `-Online` when the dependencies are already cached. For a standard checkout, use public dependencies rather than the optional local runtime for 26.3.

```powershell
./gradlew.bat classes -Puse_local_runtime=false
```

Run the regression checks with the JDK for your target version. For 26.2, also pass the properties from `versions/26.2/gradle.properties` as `-P` arguments.

```powershell
./gradlew.bat -I tests/regression.init.gradle uiRegressionCheck --no-configuration-cache
./gradlew.bat -p versions/1.21.11 -I ../../tests/regression.init.gradle uiRegressionCheck --no-configuration-cache
./gradlew.bat -p versions/1.21.1 -I ../../tests/regression.init.gradle uiRegressionCheck --no-configuration-cache
```

The checks cover input, config migration, filtering, layout, partial batch failures, CLI fallback, IPv6 matching, and telemetry. With Radmin VPN installed, run `nativeTelemetrySmoke` to check the initial read and subsequent subscription updates. Further documentation is available in Chinese in the [telemetry notes](docs/telemetry-protocol.md), [LanWorlds integration guide](docs/lanworlds-integration.md), and [development guide](docs/development.md).

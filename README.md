# Coop Launcher

A fast, terminal-based game launcher for **macOS** and **Linux**, designed to leverage Steam's [**Remote Play Together**](https://store.steampowered.com/remoteplay#together) for games that don't support it.

This is the Scala Native port of the original [Coop Launcher](https://github.com/MBaliver/cooplauncher) for Windows — same idea, rebuilt as a single self-contained native binary with a TUI.

## How it works

Steam only extends Remote Play Together to games it launches itself. Coop Launcher lets you pick a rarely-used **donor game** from your library (e.g. RetroArch) — any game that supports Remote Play Together — and then launch itself *as* the donor through a one-line Steam launch option (`--wrap %command%`). No game files are modified and nothing is installed anywhere; games you start from Coop Launcher run inside the donor's process tree and inherit its Remote Play Together support.

## Features

- **Automatic Steam discovery** — scans your whole Steam library (all library folders, `appmanifest_*.acf` parsing), on both flat and directory cache layouts
- **Banners in your terminal** — artwork rendered through a fallback chain: `chafa` (if installed) → kitty / sixel / iTerm2 native image protocols → 24-bit Unicode half-blocks → letter placeholder. No dependencies required
- **Donor game** — pick any Remote Play Together game as the launch host; Coop Launcher keeps track of it without touching game files (see [How it works](#how-it-works))
- **Proton-aware** — games without a native executable (Windows titles) run through Proton directly as the wrapper's child, so they get the donor's Remote Play Together support too
- **Manual executable overrides** — point any game at a custom binary or launch script (`e`)
- **Self-contained** — a single ~6 MB binary with no runtime dependencies (uses only `curl`, `stty` and friends, present on every macOS/Linux system)

## Requirements

- macOS (Apple silicon) or Linux (x86_64 / arm64)
- Steam installed (override the location with `STEAM_ROOT` if needed)
- Any terminal; truecolor support recommended for the best banners

> On an Intel Mac, build from source (see below) — releases ship arm64 only.

## Install

One-liner (macOS arm64, Linux x64/arm64 — detects your platform, verifies the checksum, installs to `~/.local/bin`):

```bash
curl -fsSL https://raw.githubusercontent.com/baccata/cooplauncher/main/install.sh | bash
```

Custom install directory:

```bash
curl -fsSL https://raw.githubusercontent.com/baccata/cooplauncher/main/install.sh | INSTALL_DIR=/usr/local/bin bash
```

Or manually — download the archive for your platform from [releases](../../releases), extract, and make it executable:

```bash
tar -xzf cooplauncher-<platform>.tar.gz
chmod +x cooplauncher
./cooplauncher
```

> **macOS note:** binaries downloaded from the browser carry a quarantine flag. Remove it with
> `xattr -d com.apple.quarantine cooplauncher` (or right-click → Open the first time). The
> install script is not affected — `curl` downloads never get quarantined.

## Usage

Run without arguments for the interactive TUI:

| Key | Action |
| --- | --- |
| `↑` `↓` / `j` `k` | move selection |
| `enter` / `o` | launch selected game (through the donor) |
| `/` | search — type to filter, `enter` accepts, `esc` clears |
| `esc` | clear filter / cancel |
| `+` / `-` | banner size |
| `e` | change executable for selected game |
| `d` | donor game setup (pick a Remote Play Together host) |
| `r` | rescan Steam library |
| `?` | help & credits |
| `q` / `ctrl-c` | quit |

Headless mode is also available:

```bash
cooplauncher --list            # list discovered games
cooplauncher --launch 646570   # launch by appId or (partial) name, through the donor
cooplauncher --banner 646570   # print a game banner (handy for picking COOP_IMG)
cooplauncher --help
```

Game output from directly-launched native games is captured to a per-game log:
`~/Library/Logs/CoopLauncher/<appid>.log` on macOS, `~/.cache/cooplauncher/logs/` on Linux.
Games launched through Steam log to Steam itself.

### The donor game

1. Run the launcher and press `d`
2. Pick a game to use as the host (RetroArch is a common choice) and press `enter`
3. The donor screen shows a launch-options line — open that game's Steam
   properties (General → Launch Options) and paste it:

   ```
   /path/to/cooplauncher --wrap %command%
   ```

4. Launch the **donor** from your Steam library. Steam runs Coop Launcher as
   part of the donor's launch, so Remote Play Together becomes available.
   Pick the game you actually want to play — it starts inside the donor's
   process tree, so friends can join even if the game has no Remote Play
   Together support of its own.

Launching is always donor-routed: pressing `enter` on a game in the main
screen asks Steam to start the donor and hands over your pick, so a donor
must be configured first. You can also skip the main screen entirely and
launch the donor straight from Steam, then pick a game in its session.

Nothing is ever written into game files: the trick lives entirely in the
launch option, and removing it fully reverts the setup.

Notes:

- The donor list only offers games that support Remote Play Together,
  resolved from the Steam store and cached locally
  (`~/.cache/cooplauncher/rpt_cache.json`, macOS: `~/Library/Caches/CoopLauncher/`)
  to stay within API rate limits. Press `u` in the donor screen to clear the donor.
- Native games run as direct children of the wrapper. Windows-only games run
  through Proton directly (`proton waitforexitandrun`) as children of the
  wrapper too, so they gain the donor's Remote Play Together support just
  like native ones. Only when that isn't possible does Coop Launcher fall
  back to handing the game to Steam, which runs it outside the donor tree
  (no Remote Play Together for that session).

### Limitations and edge cases

The tool's one goal is to make Remote Play Together work for Steam games
that don't support it. The donor trick needs the *game* to run as a child
of the donor, and a few situations break that attribution. They are handled
with a clear message, not silently:

- **Windows (Proton) games.** Steam normally assembles the Proton
  environment itself; Coop Launcher reconstructs the essential parts:
  - the game's compat tool is read from its per-game Proton settings
    (`CompatToolMapping` in `localconfig.vdf`); with no per-game setting it
    falls back to Proton - Experimental, then to the newest installed build
    in the Steam library. Custom tools (e.g. GE-Proton) are only resolved
    when the mapping name matches a folder under `steamapps/compattools.d`
    or `steamapps/common`.
  - the wine prefix must exist: run the game through Steam once before
    using it with a donor (`steamapps/compatdata/<appid>`), otherwise
    launching is refused with a hint.
  - the wrapper launches `proton waitforexitandrun <game>.exe` directly,
    **without** Steam Linux Runtime's pressure-vessel container. Most games
    run fine; a game that needs container-provided libraries may fail to
    start under the wrapper — it will still start when launched by Steam
    directly (outside the donor tree).
  - the main `.exe` is picked by name similarity to the game's name among
    the game folder's top-level PE files, skipping installers and crash
    handlers. A game whose real executable is named unlike the game (e.g.
    always through a generic `Launcher.exe`) may pick the wrong file; or
    find nothing, in which case the launch falls back to Steam.
- **Native-executable overrides** (`e` in the TUI) are meant for native
  ELF binaries; pointing one at a Windows `.exe` cannot work, since the
  game must be exec'd directly as a child.
- **Steam launch options.** Launching through the donor requires the
  donor's Steam launch options to contain the `--wrap` wrapper line; the
  launch is refused with guidance otherwise.
  - The `w` action writes the *exact command currently running* (for a
    development jar that's `<java> -jar <jar> --wrap %command%`; for the
    packaged binary, just its path). If you later install the launcher
    somewhere else — e.g. the CI-built native binary — press `w` again from
    that binary so the launch option follows it. Also keep the launcher at
    a stable path, not a temporary build directory.
  - Write launch options **only while Steam is closed**: while Steam runs,
    it keeps launch options in memory — it won't see the change, may
    rewrite the file on exit (discarding it), and `steam -applaunch` then
    starts the donor *without* the wrapper. That failure looks like the
    donor (or nothing) starting and the pick never launching; the queued
    pick expires after 5 minutes.

### Environment variables

| Variable | Purpose |
| --- | --- |
| `STEAM_ROOT` | override the Steam installation path |
| `COOP_IMG` | force image rendering: `off` `blocks` `chafa` `kitty` `sixel` `iterm` |

## Building from source

Requirements: [scala-cli](https://scala-cli.virtuslab.org/) and clang.

```bash
scala-cli test .                      # run the test suite
scala-cli --power package . -o cooplauncher --native-mode release
```

## Credits

- Original Coop Launcher (Windows/WPF): **Matheus Baliver** — [GitHub](https://github.com/MBaliver)
- Scala Native port and maintenance: **[baccata](https://github.com/baccata)**

## License

Distributed under the GNU General Public License v3.0. See `LICENSE` for details.

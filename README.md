# Coop Launcher

A fast, terminal-based game launcher for **macOS** and **Linux**, designed to leverage Steam's [**Remote Play Together**](https://store.steampowered.com/remoteplay#together) for games that don't support it.

This is the Scala Native port of the original [Coop Launcher](https://github.com/MBaliver/cooplauncher) for Windows — same idea, rebuilt as a single self-contained native binary with a TUI.

## How it works

Steam only extends Remote Play Together to games it launches itself. Coop Launcher lets you pick a rarely-used **donor game** from your library (e.g. RetroArch) — any game that supports Remote Play Together — and then launch itself *as* the donor through a one-line Steam launch option (`--wrap %command%`). No game files are modified and nothing is installed anywhere; games you start from Coop Launcher run inside the donor's process tree and inherit its Remote Play Together support.

## Features

- **Automatic Steam discovery** — scans your whole Steam library (all library folders, `appmanifest_*.acf` parsing), on both flat and directory cache layouts
- **Banners in your terminal** — artwork rendered through a fallback chain: `chafa` (if installed) → kitty / sixel / iTerm2 native image protocols → 24-bit Unicode half-blocks → letter placeholder. No dependencies required
- **Donor game** — pick any Remote Play Together game as the launch host; Coop Launcher keeps track of it without touching game files (see [How it works](#how-it-works))
- **Proton-aware** — games without a native executable (Windows titles) are launched through Steam itself, so Proton, compat data and launch options keep working
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
| `enter` / `o` | launch selected game |
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
cooplauncher --launch 646570   # launch by appId or (partial) name
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

Nothing is ever written into game files: the trick lives entirely in the
launch option, and removing it fully reverts the setup.

Notes:

- The donor list only offers games that support Remote Play Together,
  resolved from the Steam store and cached locally
  (`~/.cache/cooplauncher/rpt_cache.json`, macOS: `~/Library/Caches/CoopLauncher/`)
  to stay within API rate limits. Press `u` in the donor screen to clear the donor.
- Native games run as direct children of the wrapper. Windows-only (Proton)
  games are started through Steam and are not attributed to the donor — the
  donor session stays open while they run.

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

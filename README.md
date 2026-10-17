# Coop Launcher

A fast, terminal-based game launcher for **macOS** and **Linux**, designed to leverage Steam's **Remote Play Together** for non-Steam games and emulators.

This is the Scala Native port of the original [Coop Launcher](https://github.com/MBaliver/cooplauncher) for Windows — same idea, rebuilt as a single self-contained native binary with a TUI.

## Features

- **Automatic Steam discovery** — scans your whole Steam library (all library folders, `appmanifest_*.acf` parsing), on both flat and directory cache layouts
- **Banners in your terminal** — artwork rendered through a fallback chain: `chafa` (if installed) → kitty / sixel / iTerm2 native image protocols → 24-bit Unicode half-blocks → letter placeholder. No dependencies required
- **Remote Play Together magic** — copies itself over the executable of a "donor" game (e.g. RetroArch), backing up the original, so Steam treats anything launched from here as that game
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
| type anything | filter the list |
| `esc` | clear filter |
| `+` / `-` | banner size |
| `e` | change executable for selected game |
| `d` | donor game setup (the Remote Play Together trick) |
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

Game output is captured to a per-game log:
`~/Library/Logs/CoopLauncher/<appid>.log` on macOS, `~/.cache/cooplauncher/logs/` on Linux.

### The donor trick

1. Run the launcher and press `d`
2. Pick a game you're willing to use as a host (RetroArch is a common choice)
3. Confirm — the launcher installs itself as that game's executable and exits
4. Launch that game **from Steam** from now on: Coop Launcher opens with full Remote Play Together support

The original binary is preserved as `<name>.bak` next to the installed copy — restore it any time to undo.

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

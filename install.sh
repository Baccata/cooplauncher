#!/usr/bin/env bash
set -euo pipefail

OWNER="${GITHUB_OWNER:-baccata}"
REPO="${GITHUB_REPO:-cooplauncher}"
INSTALL_DIR="${INSTALL_DIR:-$HOME/.local/bin}"

os="$(uname -s)"
arch="$(uname -m)"

case "$os/$arch" in
  Darwin/arm64)
    target="macos-arm64" ;;
  Linux/x86_64)
    target="linux-x64" ;;
  Linux/aarch64|Linux/arm64)
    target="linux-arm64" ;;
  Darwin/x86_64)
    echo "error: no prebuilt binaries for Intel Macs."
    echo "Build from source:  scala-cli --power package . -o cooplauncher --native-mode release"
    exit 1 ;;
  *)
    echo "error: unsupported platform: $os/$arch"
    exit 1 ;;
esac

base="https://github.com/$OWNER/$REPO/releases/latest/download"
archive="cooplauncher-$target.tar.gz"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "==> Downloading $archive"
curl -fsSL -o "$tmp/$archive" "$base/$archive" || {
  echo "error: download failed — is there a published release at github.com/$OWNER/$REPO?"
  exit 1
}

if command -v sha256sum >/dev/null 2>&1; then
  sum="sha256sum"
else
  sum="shasum -a 256"
fi
if curl -fsSL -o "$tmp/SHA256SUMS" "$base/SHA256SUMS"; then
  expected="$(grep " $archive\$" "$tmp/SHA256SUMS" || true)"
  if [ -n "$expected" ]; then
    echo "==> Verifying checksum"
    (cd "$tmp" && echo "$expected" | $sum -c -) || {
      echo "error: checksum mismatch — the download may be corrupted or tampered with"
      exit 1
    }
  fi
fi

echo "==> Installing"
tar -xzf "$tmp/$archive" -C "$tmp"
mkdir -p "$INSTALL_DIR"
mv -f "$tmp/cooplauncher" "$INSTALL_DIR/cooplauncher"
chmod +x "$INSTALL_DIR/cooplauncher"

case ":$PATH:" in
  *":$INSTALL_DIR:"*) ;;
  *)
    echo "note: $INSTALL_DIR is not on your PATH. Add it with:"
    echo "  echo 'export PATH=\"$INSTALL_DIR:\$PATH\"' >> ~/.profile"
    ;;
esac

echo "==> Installed cooplauncher ($target) to $INSTALL_DIR/cooplauncher"
echo "    Run 'cooplauncher' to start."

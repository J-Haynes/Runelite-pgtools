#!/bin/bash
# Installs "RL Account Selector" for the current user: a menu/dock entry and an
# `rl-account-selector` command. Both point back into this repo, so pulling new changes
# updates the app - re-run this only if the repo moves. ./install.sh --uninstall removes it.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
app="$here/rl-account-selector.py"
bin="$HOME/.local/bin/rl-account-selector"
# Named after the app id, so GNOME (Wayland) matches the window to this entry and its icon.
desktop="$HOME/.local/share/applications/dev.jackhaynes.RLAccountSelector.desktop"
# What earlier versions (named "RuneLite Accounts") installed
old=("$HOME/.local/bin/runelite-accounts"
	"$HOME/.local/share/applications/runelite-accounts.desktop"
	"$HOME/.local/share/applications/dev.jackhaynes.RuneLiteAccounts.desktop")
# GNOME Shell caches an icon by its path until you log out, so install each version of the icon
# under a new name (its content hash) - a changed icon then shows straight away.
icon_dir="$HOME/.local/share/icons/rl-account-selector"
icon="$icon_dir/icon-$(sha256sum "$here/icon.png" | cut -c1-12).png"

if [ "${1:-}" = "--uninstall" ]; then
	rm -f "$bin" "$desktop" "${old[@]}"
	rm -rf "$icon_dir"
	echo "Removed $bin and $desktop (settings in ~/.config/rl-account-selector kept)"
	exit 0
fi

chmod +x "$app"
mkdir -p "$(dirname "$bin")" "$(dirname "$desktop")"
ln -sf "$app" "$bin"
rm -f "${old[@]}"
mkdir -p "$icon_dir"
find "$icon_dir" -name 'icon-*.png' ! -path "$icon" -delete
cp "$here/icon.png" "$icon"
cat > "$desktop" <<EOF
[Desktop Entry]
Type=Application
Name=RL Account Selector
Comment=Pick a Jagex account, rebuild and launch the RuneLite PvP Tools dev client
Exec=$app
Icon=$icon
Terminal=false
Categories=Game;
StartupWMClass=rl-account-selector
EOF
command -v desktop-file-validate >/dev/null && desktop-file-validate "$desktop"
command -v update-desktop-database >/dev/null && update-desktop-database "$(dirname "$desktop")" || true
echo "Icon: $icon"
echo "Installed: search for \"RL Account Selector\" in Activities, or run rl-account-selector"

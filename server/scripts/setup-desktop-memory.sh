#!/usr/bin/env bash
# Configure a 16 GiB SSD swap file and a bounded zswap cache for a desktop hosting FRIDAY.
# Run with sudo. Does not restart FRIDAY or change the model configuration.
set -euo pipefail
[[ $EUID == 0 ]] || { echo 'Run this script with sudo.' >&2; exit 1; }
exec 9>/run/lock/friday-desktop-memory.lock
flock -n 9 || { echo 'Memory setup is already running.' >&2; exit 1; }

SWAP_FILE=/swap.img
SWAP_BYTES=$((16 * 1024 * 1024 * 1024))
[[ ! -L "$SWAP_FILE" ]] || { echo 'Refusing a symlink at /swap.img.' >&2; exit 1; }
[[ -e /sys/module/zswap/parameters/enabled ]] || { echo 'This kernel has no zswap support.' >&2; exit 1; }

# Reuse a working swap file on subsequent runs; never switch off active swap under memory pressure.
if ! swapon --show=NAME --noheadings | awk '{$1=$1; print}' | grep -Fxq "$SWAP_FILE"; then
    AVAILABLE=$(df --output=avail -B1 / | tail -1 | tr -d ' ')
    (( AVAILABLE > SWAP_BYTES + 2 * 1024 * 1024 * 1024 )) || {
        echo 'At least 18 GiB of free space on / is needed.' >&2; exit 1;
    }
    umask 077
    NEW_SWAP=$(mktemp /swap.friday.XXXXXX)
    trap 'rm -f "$NEW_SWAP"' EXIT
    echo 'Allocating a fully written 16 GiB swap file...'
    dd if=/dev/zero of="$NEW_SWAP" bs=4M count=4096 conv=fsync status=none
    chmod 600 "$NEW_SWAP"
    mkswap "$NEW_SWAP"
    mv -f "$NEW_SWAP" "$SWAP_FILE"
    trap - EXIT
    if ! awk '$1 == "/swap.img" && $3 == "swap" {found=1} END {exit !found}' /etc/fstab; then
        cp -a /etc/fstab /etc/fstab.before-friday-memory
        printf '\n/swap.img none swap sw 0 0\n' >> /etc/fstab
    fi
    systemctl daemon-reload
    systemctl reset-failed swap.img.swap || true
    systemctl start swap.img.swap
fi

# Apply at boot through systemd-tmpfiles, and now without a reboot.
# The compressed cache may grow to at most 5% of RAM; disk swap backs it.
CONFIG=/etc/tmpfiles.d/friday-zswap.conf
[[ ! -e "$CONFIG" ]] || cp -a "$CONFIG" "$CONFIG.before-friday-memory"
cat > "$CONFIG" <<'EOF'
w /sys/module/zswap/parameters/compressor - - - - zstd
w /sys/module/zswap/parameters/max_pool_percent - - - - 5
w /sys/module/zswap/parameters/enabled - - - - Y
EOF
systemd-tmpfiles --create "$CONFIG"
[[ $(cat /sys/module/zswap/parameters/enabled) == Y ]]
[[ $(cat /sys/module/zswap/parameters/compressor) == zstd ]]

CONFIG=/etc/sysctl.d/99-friday-memory.conf
[[ ! -e "$CONFIG" ]] || cp -a "$CONFIG" "$CONFIG.before-friday-memory"
cat > "$CONFIG" <<'EOF'
# Favor keeping active work in RAM; swap is a safety net for desktop spikes.
vm.swappiness = 20
EOF
sysctl -p "$CONFIG"
swapon --show
echo 'Swap and zswap are enabled now and configured to persist across boots.'

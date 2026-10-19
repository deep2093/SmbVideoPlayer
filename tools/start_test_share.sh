#!/usr/bin/env bash
set -euo pipefail
sudo apt-get update -qq
sudo apt-get install -y samba
sudo systemctl stop smbd nmbd || true
mkdir -p /tmp/videolibrary-fixtures/Movies /tmp/videolibrary-fixtures/Destination
python3 - <<'PY'
from pathlib import Path
p=Path('/tmp/videolibrary-fixtures/Movies/Tom Cruise - Fixture.mp4')
p.write_bytes(bytes(range(256))*8192)
PY
chmod -R a+rwX /tmp/videolibrary-fixtures
cat > /tmp/videolibrary-smb.conf <<EOF
[global]
server role = standalone server
map to guest = Bad User
server min protocol = SMB2
smb ports = 445
log file = /tmp/videolibrary-smb.log
pid directory = /tmp
lock directory = /tmp/videolibrary-smb-lock
[fixtures]
path = /tmp/videolibrary-fixtures
guest ok = yes
read only = no
force user = $(id -un)
EOF
sudo smbd --configfile=/tmp/videolibrary-smb.conf -D

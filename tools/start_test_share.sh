#!/usr/bin/env bash
set -euo pipefail
sudo apt-get update -qq
sudo apt-get install -y samba ffmpeg
sudo systemctl stop smbd nmbd || true
sudo useradd --no-create-home --shell /usr/sbin/nologin vlfixture
printf 'ci-fixture-only\nci-fixture-only\n' | sudo smbpasswd -s -a vlfixture
mkdir -p /tmp/videolibrary-fixtures/Movies/Nested/Deep /tmp/videolibrary-fixtures/Destination
python3 - <<'PY'
from pathlib import Path
p=Path('/tmp/videolibrary-fixtures/Movies/Tom Cruise - Fixture.mp4')
p.write_bytes(bytes(range(256))*8192)
PY
chmod -R a+rwX /tmp/videolibrary-fixtures
ffmpeg -v error -f lavfi -i testsrc2=size=320x180:rate=15 -f lavfi -i sine=frequency=440:sample_rate=44100 -t 30 -c:v wmv2 -b:v 300k -c:a wmav2 -b:a 96k -y /tmp/videolibrary-sample.wmv
chmod a+rw /tmp/videolibrary-sample.wmv
# Real WMV9/WMV3 bitstream: FFmpeg cannot encode WMV3 from the generated fixture.
# Keep it outside the share until its UI playback check.
# samples.ffmpeg.org sometimes refuses the TLS handshake (curl exit 35). Retry on any error, then fall back to plain
# http: the MD5 check below proves the file is the right one either way.
wmv9_path='V-codecs/WMV9/wmv-samples/sample-wmv9-main-MP%40LL.wmv'
curl --fail --retry 5 --retry-all-errors --retry-delay 5 --connect-timeout 20 --max-time 120 "https://samples.ffmpeg.org/$wmv9_path" -o /tmp/videolibrary-wmv9.wmv \
  || curl --fail --retry 5 --retry-all-errors --retry-delay 5 --connect-timeout 20 --max-time 120 "http://samples.ffmpeg.org/$wmv9_path" -o /tmp/videolibrary-wmv9.wmv
echo 'eedc185719bff2094df32642ec48d909  /tmp/videolibrary-wmv9.wmv' | md5sum --check
ffprobe -v error -select_streams v:0 -show_entries stream=codec_name -of csv=p=0 /tmp/videolibrary-wmv9.wmv | grep -x wmv3
chmod a+rw /tmp/videolibrary-wmv9.wmv
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
guest ok = no
valid users = vlfixture
read only = no
force user = $(id -un)
EOF
sudo smbd --configfile=/tmp/videolibrary-smb.conf -D

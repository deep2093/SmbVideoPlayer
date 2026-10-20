# Additional components

- WMV/ASF compatibility uses VideoLAN LibVLC Android 3.6.5, dynamically linked native libraries under LGPL 2.1 or later. The licence is included in app assets at `licenses/LibVLC-LGPL-2.1.txt`. Source: https://code.videolan.org/videolan/libvlcjni and https://code.videolan.org/videolan/vlc .
- Backdrop blur uses Haze 1.1.0 by Christopher Banes and contributors, Apache 2.0: https://github.com/chrisbanes/haze/tree/1.1.0 .
- Optional VPN support uses the official WireGuard Android tunnel library (`com.wireguard.android:tunnel`), Apache 2.0, Copyright WireGuard LLC: https://git.zx2c4.com/wireguard-android . "WireGuard" is a registered trademark of Jason A. Donenfeld.
- The floating navigation design was studied in Nuvio's official repository: https://github.com/NuvioMedia/NuvioMobile . The app uses an original navigation component and does not include Nuvio's GPL source or assets.

Original Media3 playback remains the default for queues without WMV/ASF. LibVLC is bundled for compatibility; native and codec support still require target-device testing.

SkyWing M1 measurements
=======================

This folder measures two SkyWing builds on one device, one after the other: the build
before the M1 speed work ("before") and the build after it ("after"). It is the M1
part of windows/docs/modernise/M1-RESULTS.md. Both builds come in this folder and run
from it on test data of their own, with a test library of 5,000 short clips. Nothing
is installed: the installed SkyWing, your library and your settings stay as they are,
and no Windows setting changes. It is a test tool for your own devices, not a SkyWing
release.

Each build starts three times: a first start that scans the test library and makes
thumbnails, an everyday start, and a start with the Adaptive Glow theme. SkyWing
opens, goes to Videos and scrolls by itself. The runner reads SkyWing's own measuring
log (times and counts only) and the CPU time and memory Windows reports for it.

Before you start
1. Copy this whole folder to the device's own drive, for example to C:\SkyWing-M1.
   Not to a USB stick, a memory card, a network share or a folder OneDrive syncs
   (Desktop and Documents often are). The drive must be NTFS, as C: is.
   Copy it as it came, before it has run. After a run it holds a "work" folder: the
   test library, 5,000 links to 50 clips that only work on that drive. A copy writes
   them out in full, about 23 GB. Leave "work", "data" and "results" out.
2. Plug in the charger and close SkyWing.
3. Leave the Windows power mode and the device's own power settings as you use them.

Run
1. Double-click Run M1.cmd. A black window shows what it is doing.
2. Leave the device alone: don't touch the screen, the keyboard, the mouse, the
   buttons or the sticks, and don't close the black window. It starts within about
   4 minutes: Windows doesn't let SkyWing come to the front while the device is used.
3. After about 15 minutes it beeps three times and says "Done". Press a key to close
   the window.

Bring back the results folder. results\<device name>\M1-table.md has the numbers,
runner-log.txt has everything the black window showed.

If it stops
The black window says why and what to fix. Fix it and double-click Run M1.cmd again:
it keeps the build it already measured and does only the rest. If the device was
touched, it measures that build once more by itself.

"Run M1.cmd trial" (from a command prompt in this folder) does a short check of the
runner instead. Its numbers are not measurements.

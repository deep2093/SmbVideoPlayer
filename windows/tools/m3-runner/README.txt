SkyWing M3 checks
=================

This folder checks two SkyWing builds on one device, one after the other: the build
before the M3 new look ("before") and the build with it ("after"). It is the
automatic part of M3's acceptance, windows/docs/modernise/M3-RUNNER.md. Both builds
come in this folder and run from it on test data of their own. Nothing is installed:
the installed SkyWing, your library and your settings stay as they are, and no
Windows setting changes. It is a test tool for your own devices, not a SkyWing
release.

What it checks, for each build
- numbers: M1's measurements again (start time, the scan, scrolling, memory), on a
  test library of 5,000 short clips.
- pages: a picture of every page at the design boards' sizes, put beside the boards
  in gallery.html; the window made narrower step by step, to see the navigation
  change shape and the list keep its place; and a check that a screen reader can
  name every button, switch and slider (access.md).
- window: the window's size and place at the first start, and that SkyWing
  remembers its size, maximised or not, and comes back on screen.
- settings: getting back to the Settings list with Settings, Esc, the mouse back
  button, the Back button, and another section.
The before build is pictured and measured the same way but not judged: it shows
what M3 changed.

SkyWing's window is moved and resized while it runs, and pictures are taken of it.

Before you start
1. Copy this whole folder to the device's own drive, for example to C:\SkyWing-M3.
   Not to a USB stick, a memory card, a network share or a folder OneDrive syncs
   (Desktop and Documents often are). The drive must be NTFS, as C: is.
   Copy it as it came, before it has run. After a run it holds a "work" folder of
   links to the test clips that only work on that drive. Leave "work", "data" and
   "results" out of a copy.
2. Plug in the charger and close SkyWing.
3. Leave the Windows power mode and the device's own power settings as you use them.

Run
1. Double-click Run M3.cmd. A black window shows what it is doing.
2. Leave the device alone: don't touch the screen, the keyboard, the mouse, the
   buttons or the sticks, and don't close the black window. Windows doesn't let
   SkyWing come to the front while the device is used.
3. After about 40 minutes it beeps three times and says "Done". Press a key to close
   the window.

Bring back the results folder. results\<device name>\M3-table.md has the verdicts,
gallery.html the pictures, runner-log.txt everything the black window showed.

The pictures show SkyWing as it ran here, with this folder's place on the drive in
some of them. Keep the results folder private; don't post it.

If it stops
The black window says why and what to fix. Fix it and double-click Run M3.cmd again.
"Run M3.cmd pages" runs one check again; "SKYWING_M3_ONLY=after" in the command
prompt first runs one build.

"Run M3.cmd trial" (from a command prompt in this folder) does a short check of the
runner instead. Its verdicts say nothing about SkyWing.

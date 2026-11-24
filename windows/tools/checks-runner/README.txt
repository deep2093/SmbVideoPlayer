SkyWing checks
==============

This folder checks one SkyWing build on one device with nobody at it: the checks the
later milestones of windows/docs/modernise/SkyWing-PC-modernise-plan.md ask for. The build comes in this
folder (app) and runs from it on test data of its own, with a test clip the runner
made. Nothing is installed: the installed SkyWing, your library and your settings stay
as they are, and no Windows setting changes. It is a test tool for your own devices,
not a SkyWing release.

The checks
- fullscreen: full screen in and out 20 times while the video plays.
- stress: 10 times: SkyWing starts, seeks and changes full screen 100 times, then
  closes. It must close by itself every time and leave no SkyWing process behind.
- soak: 2 hours in full screen with a seek every 10 minutes. The runner notes
  SkyWing's memory, handles and threads each minute. Only when you name it.
- scan "<folder>": walks a folder of videos the way SkyWing did before the M1 change
  and the way it does now, and compares how long each took and what each found. It
  only reads the folder and SkyWing doesn't start. Only when you name it.

Before you start
1. Copy this whole folder to the device's own drive, for example to C:\SkyWing-checks.
   Not to a USB stick, a memory card, a network share or a folder OneDrive syncs
   (Desktop and Documents often are). The drive must be NTFS, as C: is.
2. Plug in the charger and close SkyWing.
3. Leave the Windows power mode and the device's own power settings as you use them.

Run
1. Double-click Run checks.cmd. A black window shows what it is doing. It does the
   fullscreen and stress checks, about 35 minutes.
2. Leave the device alone: don't touch the screen, the keyboard, the mouse, the
   buttons or the sticks, and don't close the black window. You can watch: the
   picture (a fade, a flash, a black frame) is the one thing the runner can't check.
3. When it beeps three times and says "Done", press a key to close the window.

For the soak or the scan, open a command prompt in this folder and type
    "Run checks.cmd" soak
or
    "Run checks.cmd" scan "D:\Videos"
Name a folder of videos for the scan, without a backslash at the end.

Bring back the whole results folder. results\<device name>\<date and time>\checks.md
has the results, runner-log.txt everything the black window showed.

What the results say
- Pass: the check found nothing wrong.
- Fail: SkyWing did something wrong. The line says what and when.
- Look at it: nothing failed, but a number grew more than the first limits allow.
- Not measured: another program came to the front, so the check says nothing about
  SkyWing. Run it again.

"Run checks.cmd" trial does a short check of the runner instead. Its results say
nothing about SkyWing.

SkyWing M0 handheld runs
========================

This folder measures SkyWing 0.1.101 on a handheld: the eight runs in section 4 of
windows/docs/modernise/M0-RESULTS.md. It plays a 1080p clip and a 4K clip with motion
smoothing at 2x, in Fast and in Balanced quality, plugged in and then on battery, and
writes the results table. SkyWing runs on test data of its own, so your library and
settings stay as they are. The folder installs nothing and changes no Windows setting.
It is a test tool for your own devices, not a SkyWing release.

Once per handheld
1. Install SkyWing 0.1.101 if the handheld has another version.
2. Copy this whole folder to the handheld's own drive, for example to C:\SkyWing-M0.
   Not to a USB stick, a memory card, a network share or a folder OneDrive syncs
   (Desktop and Documents often are).
3. Copy one 1080p SDR video into clips\1080p: at least 8 minutes long, at a standard
   frame rate (23.976, 24, 25, 29.97 or 30 fps; SkyWing can't smooth other rates,
   such as a variable one), and the same file on both handhelds. Copy the laptop's
   4K SDR clip (about 10 GB) into clips\4K.
4. Charge the battery to 100 %, then leave the charger plugged in.
5. Close SkyWing, and turn battery saver off.

Run
1. Double-click Run M0.cmd. A black window shows what it is doing.
2. Leave the handheld alone: don't touch the screen, the buttons or the sticks, and
   don't close the black window. The first run starts within about 4 minutes;
   Windows doesn't let SkyWing come to the front while the handheld is being used.
3. After about 35 minutes it beeps, and beeps again every minute: unplug the charger
   without touching the screen, the buttons or the sticks. The battery runs start
   30 seconds later, or up to 4 minutes later if the handheld was touched.
4. After about 30 more minutes it beeps three times and says "All runs are done".
   Plug the charger back in and press a key to close the window.

Bring back the results folder. results\<handheld name>\M0-table.md has the rows for
section 4, runner-log.txt has everything the black window showed, and the run folders
have the playback reports and the details.

If it stops
The black window says why and what to fix. Fix it and double-click Run M0.cmd again:
it keeps the finished runs and does only the rest. If the fix isn't clear, bring back
the results folder: runner-log.txt has the same text.

If SkyWing can't keep up, it turns smoothing off by itself. That run is a result, not
a stop: its row says "Didn't hold 2x", and the runner goes on with the next run.

The second handheld
Copy the folder, results included, to the second handheld and do the same there. Each
handheld gets its own results folder.

"Run M0.cmd trial" (from a command prompt in this folder) does a short check of the
runner instead: the four plugged-in runs with 30-second windows. Its numbers are not
measurements.

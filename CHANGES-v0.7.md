# VideoLibrary v0.7 source update

## Added
- Folder-first navigation: **Folders** is the start page; **Videos** comes second.
- **Browse** tab: inspect directories in the configured SMB share. Open subfolders, navigate up, refresh.
- Rename an **original video file** directly in its video card. This changes the physical file on the SMB share, unlike renaming a virtual folder.
- In Browse, file and directory menus have **Rename**, **Copy**, **Cut/Move**, and **Delete**. Paste into the currently selected directory.
- Confirm before permanent deletion, including recursive directory deletion.
- Copy refuses to overwrite existing files. Interrupted copies can leave partially copied files. Cut uses SMB server-side rename/move within the same share.
- After successful remote edits, queue a library refresh. The refresh can take time and may be deferred if another scan is active.
- SMB discovery now uses Android's Wi-Fi/Ethernet network, interface prefix, default gateway, DHCP fallback, and saved SMB address. It binds port 445 probes to the local network when possible to avoid VPN routing problems. A diagnostic explains empty results.

## Caveats
- **Not locally compiled or device tested.** Build with the existing GitHub Actions workflow; failures require the first compiler error log.
- SMB host discovery is not share discovery; enter share name and credentials manually. Some servers block port 445 probes or isolate clients.
- File explorer operates on **one configured share**, not all network drives. Browse starts at share root.
- Remote operations require a write-enabled SMB login. Rename/move/delete can permanently modify your NAS or router USB storage. Always test on disposable files first.
- Recursive copying may take a long time and runs as a ViewModel operation; moving away from the app can interrupt it. A durable background transfer manager is future work.
- SMB file renames and moves can change file paths in the index; existing thumbnails and manual virtual-folder assignments may require re-indexing and reassignment. Do not rename large amounts of files at once until verified.
- File explorer is an initial implementation, not an Android system Files provider.

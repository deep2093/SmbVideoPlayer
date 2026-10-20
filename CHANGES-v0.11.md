# v0.11 Folder collections and reliability

- Pin any folder on the SMB share in Custom folders. Browse nested folders, select several, all siblings, or an entire tree. Include/exclude descendant videos. Selected locations participate in scans even outside the starting folder.
- Keep filename collections, manual virtual assignments and the fixed tablet sidebar. Preserve list positions when returning from a folder or player.
- Simplify Scan to a primary action plus a menu, expandable failures/errors, and consistent cards. Keep Settings within a readable width and remove duplicate notices.
- Isolate each server/account/root index and artwork. Adopt the existing upgraded library in place. Bind background queues and worker requests to a stable library identity.
- Journal transfer phases and server file identities, so interruption after remote publication can be reconciled before updating Room. Reject unknown or conflicting destinations. Bound linked-folder traversal and retry transient failures.
- Retain an existing index after an unexpectedly empty scan; require confirmation of that exact scan before removing missing entries. Automatic sync reports the retained state instead of claiming success.
- Make thumbnail network failures retryable, check encoded artwork publication, preserve old artwork on write failure, and invalidate every artwork cache on clear.
- Advance playback in bounded 100-item batches instead of materializing the whole library. Media3 and VLC continue at batch boundaries.
- Export and restore organization/history without passwords. Configure automatic virtual-folder grouping while preserving manual assignments.
- Split live browsing, artwork coordination, folder collection storage, backup and playback queue responsibilities out of LibraryViewModel.
- Export schema 5 and regenerate historical fixtures, including the two historical version-3 shapes. Exercise migrations without skipping missing schema tests.
- Add regression coverage for path-bounded collections, future scans, library isolation, queues, backup, real folder/back navigation and interrupted transfer recovery.
- Upgrade Kotlin/KSP, Media3 1.6.1, Compose BOM 2025.04.01, Room 2.7.2 and SMBJ 0.14.0 in separate commits for review.
- Check in the Gradle wrapper with distribution checksum verification. Rebuild the pinned legacy APK from source instead of relying on an expiring run artifact. Test API 29 and 35. Add profile-generation and startup-measurement tooling.

See the GitHub Actions run for validation status. Hardware 4K/WMV, Wi-Fi throughput, PiP and large-font tablet layouts still require the target device; emulator checks cannot establish those results.

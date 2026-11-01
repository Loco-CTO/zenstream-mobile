# Download notifications

## Ownership and compatibility

`DownloadExecution` owns the mandatory notification for each user-initiated transfer job (API 34+) or foreground service (API 24–33). `DownloadNotifications` renders the notification. Both execution paths use the same profile-scoped progress monitor, independently of a queue pass: downloading one large file must continue to update its notification.

API 36+ uses AndroidX `NotificationCompat.ProgressStyle` with a small filled dot tracker and a completion icon. Keep the tracker simple so it remains legible at the system template's small size. API 24–35 uses an explicit `setProgress` bar and expanded `BigTextStyle`. AndroidX's ProgressStyle fallback alone does not supply the legacy progress display. The existing Core dependency already includes these APIs; no dependency upgrade is necessary.

Use the low-importance Downloads channel, silent updates, one stable notification ID, private lock-screen visibility, immutable explicit action intents, and the existing Downloads deep link. Pause all and cancel remaining preserve the existing durable queue controls. Completion/stopping removes the execution notification. These are ordinary transfer notifications: do not request Live Update promotion for routine library downloads that do not require continuous attention.

## Progress semantics

- A transaction reads only the pending-work counts and at most two presentation rows. Joins retrieve media titles and series/episode or album context without parsing descriptors or loading the full queue/catalog.
- The percentage and bytes represent the currently transferring files, weighted by their sizes. Historical completed downloads and queued files are excluded. The header separately reports in-progress and queued counts.
- ETA describes **active downloads**, not the entire queue. Unknown-length transfers cannot produce a percentage or ETA. Preparation, verification/saving, queue wait and retry wait are explicit indeterminate phases.
- Each file has its own monotonic-time byte samples, with a ten-second window and three-second warmup. Resume offsets, new files, changed sizes and reset counters do not inflate throughput. After five seconds without byte movement, speed/ETA disappear. Verification/supplementary work is not included in the transfer ETA.
- Poll no faster than once per second and avoid unchanged posts. Cancel and join the monitor before its owner finishes. Repost after notifications become enabled even if progress has not changed. Replacing a legacy service start must not let its cancelled predecessor remove the new notification.
- Resolve the saved interface locale for service updates. English and Japanese resources include the permission/recovery flow and time estimates.

## Permission and recovery

`MainActivity` enables the central permission handler in `OfflineDownloadHost`. On Android 13+, show a one-time startup explanation of the download notification benefit. **Allow notifications** opens the system request; **Not now** preserves normal app use. Persist that the startup explanation was shown and whether a native request was attempted. Recreation must not stack prompts.

`ZenStreamLocale` must preserve `LocalActivity` and `LocalActivityResultRegistryOwner` when it replaces `LocalContext` with a configuration context. The replacement context alone cannot discover the activity that registers permission and picker results. Cover the localized host as well as the isolated dialog in device tests.

Download/enqueue and resume actions check both app-wide notification availability and the Downloads channel. If blocked, show a recovery dialog once per host session. The download can continue. When Android permits another request, the dialog offers **Allow notifications**. After permanent denial, on older Android, or when the channel is disabled, it offers **Notification settings**, targeting the app or channel screen as appropriate. Do not repeatedly launch a system permission prompt that Android will suppress.

Downloads settings always provides a notification status and settings entry. Re-read that status when returning from Android Settings. A foreground-service notification post losing permission must not crash or cancel the transfer. System/UIDT task-manager visibility is separate from notification-drawer visibility.

## Validation

Run the full offline check from [offline.md](offline.md), plus the focused notification JVM and instrumentation tests. Validate these device cases when the corresponding API targets are available:

| Case | Expected behavior |
| --- | --- |
| Fresh API 33+ launch | One startup explanation, optional system permission prompt |
| Denied permission; start/resume download | Download continues; recovery explains missing notification |
| Permanent denial | Recovery opens app notification settings |
| Downloads channel disabled | Recovery opens channel settings |
| Return after enabling notifications | Status updates; active execution reposts notification |
| One slow large file | Title, bytes and progress update during the transfer |
| Two transfers | Both titles, byte-weighted progress, aggregate speed and active ETA |
| Unknown size, preparation, verification, retry | Accurate phase and indeterminate bar |
| Pause/cancel/finish or replace execution | No stale monitor posts or orphaned ongoing notification |
| API 24–35 | Expanded text and classic progress bar |
| API 36+ | Native ProgressStyle rendered by System UI |

Screenshots generated by instrumentation demos use production composables and the production notification builder with explicit fixture media. They demonstrate rendering, not a completed server-backed media transfer. Keep demo artifacts local and outside tracked source.

## Research references

- [AndroidX ProgressStyle reference](https://developer.android.com/reference/androidx/core/app/NotificationCompat.ProgressStyle): API 36 rendering and fallback behavior.
- [Android progress-centric notifications](https://developer.android.com/develop/ui/compose/notifications/progress-centric): system-template anatomy, progress and concise status text.
- [Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update): separate promotion and time-sensitive usage criteria.
- [Android notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission): Android 13 runtime permission and denied foreground-service notification behavior.
- [Android runtime permission workflow](https://developer.android.com/training/permissions/requesting): rationale, denial, settings recovery and graceful degradation.
- [Android user-initiated transfers](https://developer.android.com/develop/background-work/background-tasks/uidt): mandatory job notification and execution lifecycle.
- [Chrome's central notification permission controller](https://chromium.googlesource.com/chromium/src/+/2149347a1f3c112d7d7306b55ba07bba3f361b19/chrome/browser/notifications/android/java/src/org/chromium/chrome/browser/notifications/permissions/NotificationPermissionController.java): startup/contextual entry points, rationale and request tracking.
- [Thunderbird's permission onboarding explanation](https://blog.thunderbird.net/2023/12/thunderbird-for-android-k-9-mail-november-december-2023-progress-report/): moving repeated requests into onboarding while preserving app use without permission.

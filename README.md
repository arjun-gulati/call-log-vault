# Call Log Vault

**Your phone is silently deleting your call history. This app stops that.**

Samsung Galaxy phones keep only the newest **2000 entries** in the call log. Every new call quietly deletes the oldest one. No warning, no setting to turn it off. One day you need a number from a few months ago and it is simply gone.

Call Log Vault fixes that one problem. It quietly copies every call into its own vault on your phone, before the limit eats it. The vault has **no limit** and never deletes anything.

Vibe coded by Arjun to solve his own problem, on his own S24 Ultra.

## The short version

- Copies every incoming, outgoing and missed call into a private on-device vault
- **No internet permission at all.** Not a promise, the permission does not exist in the app. Check it yourself: Settings, Apps, Call Log Vault, Permissions
- **No ads, no root, no account, no trackers, no data theft.** Fully offline, forever
- Wakes up only when your call log actually changes, so battery use is effectively zero
- Auto backup to a folder **you** pick, outside the app, so clearing app data or uninstalling can never delete your backup. You choose how often: after every call, every 6 hours, daily, or smart

- Two devices, one history: turn on Watch backup folder and the app imports new backups that appear in your backup folder, merges them without duplicates, and can optionally restore imported calls into your dialer. Your own folder sync tool does the carrying between devices, there is no server and no account
- Restore your full history back into the normal phone call log anytime, it shows up in any dialer app
- Search your entire history by name or number, filter by call type, see per-person timelines
- Light, Dark and Auto themes (Auto follows your phone's own light and dark mode), with a switch to make Dark true black AMOLED

- Manual delete when you want it: long press an entry in a person's timeline to remove it, or clear a whole number. The vault never deletes anything by itself, deleting is only ever your own tap

## Why trust it

- The app requests exactly 4 permissions: read call log, write call log (for restore), read contacts (to show names), and receive boot completed (so it restarts after a reboot). Nothing else. There is no internet permission to abuse
- Every backup is a plain ZIP containing CSV, JSON and XML files. Your data is readable by normal tools, never locked into a weird format
- Verify the download yourself. SHA-256 of the v1.1.0 APK:
  `b6d94d90f331c1e42a5989a4f51b5047084a7e5a1199dafcf343fdd7f426e32a`
- The full source code is in this repo (see below). Do not take anyone's word for what the app does, read it

## Install

1. Download the APK from the [Releases](../../releases) page
2. Open it and allow the install when Android asks
3. Open Call Log Vault and grant the call log permission. Your current call log is imported right away
4. Optional but recommended: open the drawer, tap Auto backup folder, and pick any folder you like. Your rolling backup will live there, outside the app

Step by step with more detail: [INSTALL.md](INSTALL.md)
Questions: [FAQ.md](FAQ.md)

## How it works

Android lets apps ask to be woken when the call log changes. Call Log Vault registers for exactly that, copies any new calls into its own database, and goes back to sleep. No polling, no background service sitting awake, no wakelocks. A once-a-day safety sync catches anything that might have slipped through.

Your phone's own call log will still trim at 2000, that behaviour is built into the system and cannot be changed without root. The difference is that now it no longer matters, because the vault keeps everything, and one tap restores as much as you want back into the dialer.

## Restoring

Restoring writes the vault's records back into the Android call log provider, skipping duplicates. Any dialer (Samsung Phone, Google Phone, others) then shows them like normal calls. If you restore more than 2000, the phone dialer will trim the oldest again over time, but the vault always keeps the complete history.

## Source code

The full Kotlin source lives in this repo: `app/` is the app, `tests/` is its JVM test suite, and [BUILDING.md](BUILDING.md) explains how to run the tests and build the APK yourself with your own signing key. No libraries, no Gradle, plain Android framework code.

Official releases are signed with the maintainer's key and published with SHA-256 hashes, so a self-built copy cannot update over an official install, and nobody else's build can pretend to be an update.

Contributions are welcome, open an issue or a pull request. Two house rules: the app keeps exactly its 4 permissions and never gains an internet permission, and contributed code must be readable and unobfuscated.

## License

MIT. See [LICENSE](LICENSE).

Imagined by Arjun.

The open source release.

The full Android source code of Call Log Vault is now published in this repository under the MIT license: the app in app/, its test suite in tests/, and BUILDING.md explains how to run the tests and build the APK yourself with your own signing key.

What changed in the app itself: development diagnostics that were added while hunting a scrolling problem have been removed (a frame timing meter, sniffer run counters, a few extra Status lines). Nothing about how the app works changed: same vault, same backup format, same restore. This release installs over v1.0.6 and keeps your existing vault.

Download call-log-vault-v1.0.7.apk below. SHA-256: 1b0b1b67a89f7e3b2d467ca062bb8d58e8dcfffc40a76572a3e9d29a5c71e020. Android 8.0 or newer.

Contributions are welcome. House rules: the app keeps exactly its 4 permissions and never gains an internet permission, and contributed code must be readable and unobfuscated.

Imagined by Arjun.

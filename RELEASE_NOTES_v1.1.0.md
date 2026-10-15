# Call Log Vault v1.1.0

The watch folder release. This completes GitHub issue #3, the request for two of your own phones to share one call history, with custom backup schedules (which landed in v1.0.8) and now the folder watching itself.

**How it works.** Pick the same auto backup folder on both phones, a folder your own sync tool keeps in step (Syncthing, Dropsync, a cloud drive folder, anything you already trust), and turn on **Watch backup folder** in the drawer on both. When a new backup arrives in the folder, the app imports its records, merges them into the vault without duplicates, and writes the merged history back to the folder for the other phone. A second switch, **Restore imported calls to phone log**, stays off unless you turn it on; with it on, imported calls also appear in your dialer.

**The rules it plays by.** No server, no account, no pairing: the app still has no internet permission at all, your folder is the entire transport. Imports only ever add records, deletions on one phone never delete anything on the other. Checks happen only when the app already wakes for other work (a backup run, opening the app), so there is no polling and nothing new drains the battery. A fingerprint of the backup file means the app never wastes time re-importing its own writes.

Same 4 permissions, same vault and backup format. This release installs over v1.0.9 and keeps your existing vault.

Download call-log-vault-v1.1.0.apk below. SHA-256: b6d94d90f331c1e42a5989a4f51b5047084a7e5a1199dafcf343fdd7f426e32a. Android 8.0 or newer.

Imagined by Arjun.

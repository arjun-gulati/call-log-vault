# FAQ

**Does Samsung really delete call logs after 2000 entries?**
Yes. The system call log is capped, and on Galaxy phones the practical limit people hit is the newest 2000 entries. As new calls arrive, the oldest are removed automatically. There is no built-in setting to raise or disable the cap.

**Is this only for Samsung phones?**
No, the app runs on any Android 8.0+ phone. Samsung users just feel the problem the most, because of the 2000 cap.

**Does it need root?**
No. No root, no ADB tricks, no special modes. It uses only normal Android permissions.

**Does it send my calls anywhere?**
No, and it cannot. The app has no internet permission, which you can verify yourself in Android settings. Everything stays on your phone, and backups go only to the folder you pick.

**What about battery?**
The app does not poll and does not run a background service. Android wakes it briefly when your call log changes, it copies what is new, and it sleeps again. In battery settings you should see it at or near zero.

**What happens when the phone's own log passes 2000 after I restore everything?**
The phone dialer trims the oldest again, that is system behaviour. The vault keeps the complete history regardless, and you can restore again anytime.

**I cleared the app data / uninstalled by mistake. Is my history gone?**
If you set an auto backup folder, no. Restore from `call-log-vault-auto.zip` in that folder and everything comes back. This is exactly why the backup lives outside the app.

**What format are backups in?**
A plain ZIP with calls.csv (open it in any spreadsheet), calls.json (full data), calls.xml (the format also used by SMS Backup and Restore), and a README. Restoring Call Log Vault's own ZIP is fully supported. Other apps reading the XML is a bonus, not a guarantee.

**Will this come to the Play Store?**
Maybe, one day. For now GitHub Releases is the home. Same APK, same signature, every time. If a file claiming to be Call Log Vault has a different SHA-256 than the one published here, it is not this app.

**How many calls can the vault hold?**
There is no cap in the app. It grows with your history.

**Can the app follow my phone's dark mode?**
Yes. In the drawer, set the theme to Auto. The app is light when your phone is in light mode and dark when the phone goes dark. A separate switch in the drawer, Use AMOLED for Dark, decides whether dark means the normal dark theme or true black AMOLED, for both the Dark theme and Auto's dark side.

**Can I delete entries from the vault?**
Yes, manually, and you choose how far it goes. Open a person's timeline and long press to select one or more entries (or use delete all on that screen for the whole number). The confirmation offers two choices: **Vault only**, which removes the entries from the vault and leaves your phone's call log untouched, or **Vault and phone log**, which also removes the calls from the phone's own call log so they disappear from the dialer. Neither can be undone, and the app itself never deletes anything automatically.

**If I delete a call, will a sync or an old backup bring it back?**
No. When you delete, the app saves a scrambled fingerprint of that call (a one-way hash; it cannot be turned back into a number or a date). Every sync, every backup import and the watch folder check that fingerprint first and refuse the call, even if it is still sitting in your phone's call log or inside an old backup file. One honest limit: a backup file you exported earlier still contains the record inside the file itself. Deleting protects the app, it does not reach into files you saved elsewhere. Deleting on one phone also does not delete on your other phone; each phone's deletions are its own.

**Can two of my phones share one call history?**
Yes. Install the app on both, pick the same auto backup folder on both (a folder your own sync tool keeps in step between the phones, for example Syncthing or Dropsync), and turn on Watch backup folder on both. Each phone imports new records that arrive in the folder, merges them into its vault without duplicates, and writes the merged history back. Imports only add records; a deletion on one phone never deletes anything on the other. There is no server and no account: the app has no internet permission, your folder and your sync tool do all the carrying. Checks happen when the app wakes for other work, like a backup run or opening the app, so a call made on one phone typically shows up on the other within minutes rather than instantly.

**How often does the auto backup run?**
You choose, in the drawer's Backup schedule setting: Smart (a new backup after a burst of calls, and at most once every 15 minutes otherwise), After every call, Every 6 hours, or Daily.

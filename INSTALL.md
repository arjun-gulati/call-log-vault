# Installing Call Log Vault

You need an Android phone on Android 8.0 or newer. It works on any brand, but it matters most on Samsung Galaxy phones, because those trim the call log at 2000 entries.

## Steps

1. Go to the Releases page of this repo and download `call-log-vault-v1.0.6.apk`
2. Optional, for the careful: verify the file's SHA-256 hash matches the one in `SHA256SUMS.txt`. Any file checker app can do this
3. Tap the downloaded APK. Android will ask whether to allow installs from your browser or file manager. Allow it for this one install
4. Tap Install
5. Open Call Log Vault. It will ask for three permissions:
   - Call log: required, this is the whole point of the app
   - Contacts: only used to show names next to numbers
   - The app has no internet permission and never will. You can confirm this in Settings, Apps, Call Log Vault, Permissions
6. The app imports your current call log immediately. From now on, new calls are copied automatically

## Set up the auto backup (recommended)

1. Open the drawer from the top left menu
2. Tap Auto backup folder and pick any folder, for example a folder called Call logs in your internal storage
3. Done. After new calls arrive, the app keeps a file called `call-log-vault-auto.zip` updated in that folder

The folder lives outside the app. Clearing the app's data or uninstalling the app does not delete that file. If you ever reinstall, restore from that ZIP and your full history comes back.

## Updating

Download the newer APK from Releases and install it over the old one. Your vault is preserved. Never uninstall first, there is no need.

## Uninstalling

Just uninstall normally. Your auto backup ZIP in your chosen folder stays where it is, because it was never inside the app.

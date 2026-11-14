# Call Log Vault v1.4.2

This release closes out GitHub issues #15 to #22, all filed by users of the app. Thank you for filing them. Version 1.4.1 was a test build that was never published, so everything since the last public release, v1.4.0, is in here.

Saved contacts now show up on old calls (issue #15). If a number calls you before you save it, it used to stay "Unknown" forever, even after you saved the contact. Now every sync checks your contacts again, so old entries pick up the name too. No calls are merged or lost, only the name is refreshed.

Side scroller no longer gets stuck (issue #16). After pressing the back to top button from far down the list, the pill sometimes stayed visible instead of fading away. It now fades on schedule every time, on both the Call log and People pages, and it also hides properly when you switch pages.

Date headers no longer flash when pressed (issue #17). The date lines between calls used to turn dark when you pressed or held them, even though they do nothing. They are now plainly not tappable, with no press effect at all.

Call type icons, and a tidier layout (issue #18). Each row in the Call log now shows a big icon for the type of call: incoming, outgoing, missed, rejected or blocked, each with its own colour, so you can see what kind of call it was at a glance. The People page keeps its name initials. The "Unknown" filter has moved from the Call log page to the People page, where it sits with All and Missed calls. People icons can now show the number of calls instead of the initial, with a new setting to turn that on or off. It is off by default, so initials stay unless you change it. The second line under a call no longer repeats the type in words, since the icon already says it, so it now shows just the number.

Search clears when you switch pages (issue #19). Your search text used to stick around when you moved between Call log and People. It now clears on every page switch.

Same empty search message on both pages (issue #20). An empty search used to say "Nothing matches this search." on one page and "No people match this search." on the other. Both pages now use the same pattern: "No calls match this search." and "No people match this search."

Search box behaves as you expect (issue #21). Pressing the X still clears the text and keeps the keyboard up, as before. But scrolling, tapping the scroller, the menu, the back to top button, a filter, or a call entry now closes the keyboard and removes the focus from the search box. Opening a call and coming back no longer puts the focus back in the box.

A QR code that scans (issue #22). The QR code on the support page was a stylised picture that some scanners could not read at all. It is now a crisp standard code that scans reliably.

What's New popup, rebuilt to be nicer to read. The popup that appears once after each update is now spaced out and colour coded: new things in green, improvements in blue, fixes in red, each with its own label. The words stay just as simple. It still appears once per version, and you can open it again anytime from "What's new" in the drawer.

Same 4 permissions, no internet, same backup format. This release installs over v1.4.0 and keeps your existing vault.

Download call-log-vault-v1.4.2.apk below. SHA-256: 920ca7b329e3925da856191ffda3da926482a9b12e2951ecd361a18dad720f92. Android 8.0 or newer.

Imagined by Arjun. Vibe coded with care.

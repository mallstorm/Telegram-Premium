# Telegram 11.3.x classic shell reference — 2026-11-23

## Exact artifact inspected
- Uploaded reference file: `base.apk` (71 MiB / 74,441,318 bytes).
- Android binary manifest declares `versionName=11.3.4`, `versionCode=53979`, package `org.telegram.messenges`; it is **not** the installable TelePremium package `com.telepremium.messenger`.
- Its DEX contains native drawer components `DrawerLayoutAdapter`, `DrawerProfileCell`, and `DrawerActionCell`. We keep Telegram 13.1.1 internals and implement a compatible visual shell rather than transplanting the old binaries.

## Visual comparison from user screenshots
The first four screenshots show the current custom classic shell, the following three show the 11.3.x reference.
- Reference collapsed drawer header ends roughly 40dp earlier; current shell's 205dp profile header places all menu entries too low. New header: 165dp plus status-bar inset, avatar and text shifted accordingly.
- Original 11.x native menu order: My Profile, Set Emoji Status, divider, New Group, Contacts, Calls, Saved Messages, Settings, divider, Invite Friends, Telegram Features. The old implementation omitted Emoji Status and exposed six TelePremium utilities mixed directly into native items. Extras are now grouped into a separately expandable TelePremium section.
- In 11.x screenshot, normal chat list has filter tabs directly below action bar, **no always-visible search pill or expanded modern stories strip**. Native full-screen Telegram search remains accessible from the action-bar magnifier.
- Original menu typography is lighter than the prior custom bold 55dp rows. Use native theme foreground and sans-serif-medium; preserve themed menu surfaces and bottom navigation bar inset.
- New 13.x skin and Telegram data/session/MTProto remain unchanged. One active Theme palette supplies both shells.

## Validation
Static `scripts/verify_classic_shell.py` checks menu/emoji/theme/search invariants. `Source Check` and `Build TelePremium APK` must both pass on this branch before distributing a new installer. Runtime testing still needed on Android 13 for dialogs layout, floating button positions, drawer bottom insets and search expand/collapse.

## Safety
Uploaded original APK is for historical UI comparison, not for reuse of its signature, app ID or protocol implementation. Do not install it over TelePremium.

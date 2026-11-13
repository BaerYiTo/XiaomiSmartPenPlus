# Changelog

## 2.1.0

- Merge the HyperOS and ColorOS pen-key handling paths and detect the ROM family at runtime.
- Add ColorOS stylus system settings integration and hardware status bridge.
- Keep the Miuix interface across systems, with the system settings entry shown only on ColorOS.
- Add screen-swipe actions and expanded ROM, routing, bridge, and input regression coverage.
- Set Android application ID to `com.inkdye.xiaomipen`, versionCode `22`.

## 1.2.1

- Add swipe-left and swipe-right synthetic touch actions, for novel readers that page horizontally.
- Release build: versionName `1.2.1`, versionCode `6`.

## 1.2.0

- Replace the interface with Compose Miuix, with dark mode and tablet layout.
- Add independent per-app pen actions, takeover switches and timing thresholds.
- Remove the old native-app exclusion list and migrate it to ordinary profiles.
- Keep gestures bound to their initial app and cancel pending actions on focus change.
- Retry configuration listeners during startup and refresh settings in the background.
- Change package and namespace to `xiaomismartpen.plus`, versionCode to `4`.
- Import a compatible `com.focuspenlite` snapshot once on first launch.
- Add an original adaptive pen-plus icon, including Android themed icon support.
- Publish source and third-party license notices under Apache-2.0.

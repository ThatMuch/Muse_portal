# Contributing

Contributions are welcome: bug reports, fixes, translations, features and test reports from other
Portal models.

## Getting started

1. Follow [Build and install](README.md#build-and-install).
2. Most logic lives in `core/`, which runs on the JVM without a device:

```bash
./gradlew :core:test
```

3. Check the Android app before opening a pull request:

```bash
./gradlew :app:assembleDebug :app:lintDebug
```

The debug build can be installed over the release build, as both use your local debug key.

## Ground rules

These protect the people who put this device in their living room. A change that breaks one of them
will not be merged.

- **No secrets** in code, tests, fixtures or logs. No tokens, keys or passwords.
- **Only the hosts the Muse app sends** are used to reach Muse. Nothing configurable in production.
- **No shell or file access** for the Muse. Anything that sends a picture or controls a device needs
  an explicit choice on the screen, and is off by default.
- **Logs carry no content**: no message text, transcript, caption or address of a person.
- **Leave the Portal alone**: no system app removed or disabled, no bootloader unlock.
- New behaviour in `core/` comes with tests. Protocol code is checked against the SDK's vectors.

## Code

- Kotlin, 4 spaces, lines up to about 120 characters.
- Comments explain why, not what. English is preferred for new comments.
- Keep ported files traceable: the header says which SDK file they come from.
- If you try something on a real device, say so in the pull request, and say what you could not try.

## Good first contributions

- **Add a language**: texts live next to the code as `tr("English", "French")`. Moving them to
  Android resources (English by default, French as a translation) would open the door to more.
- Report how it runs on **other Portal models** or Android versions.
- Make the layouts better in **portrait** orientation.
- Try the **Bluetooth**, **camera** and **feed** features with a real Muse and report what happens.
- Hang up a video call on request: describe the shape of the `hatch.voice.realtime_event` messages
  from the app's content-free logs.
- Make the video **background removal** cleaner at the edges.

## Pull requests

Keep each pull request to one topic, describe how you tested it, and open an issue first for large
changes. By contributing you agree that your work is released under the [Apache License 2.0](LICENSE).

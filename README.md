# E-Ink Radio

An unofficial Android client for Pandora, built for e-ink devices.

- Sign in with your own Pandora account (free or Premium); credentials are kept in encrypted preferences.
- Stations, now playing, like/dislike, play/pause, skip, media-session controls (lock screen, Bluetooth).
- E-ink mode: pure black on white, bordered buttons, no ripples or animations, grayscale art,
  paged station lists instead of scrolling, text size steps, and a slow-refresh elapsed-time readout.

It talks to Pandora's unofficial JSON v5 API (<http://6xq.net/playground/pandora-apidoc/json/>),
which is reverse-engineered and can break at any time. The client does not block ads or enforce
or bypass skip limits; it plays what the service returns and shows the service's errors.

## Building

Not yet built or run. Open the folder in Android Studio (it supplies JDK 17+ and the SDK), or
install the Android SDK and run `gradle wrapper` once, then `./gradlew assembleDebug`.

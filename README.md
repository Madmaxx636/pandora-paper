# Paper for Pandora

An unofficial Android client for Pandora, built for e-ink screens. The app appears on the home screen as
**Pandora Paper**.

- Sign in with your own Pandora account (free or Premium). The login is kept in encrypted preferences
  and restored on launch behind a short stepped logo intro.
- **Stations:** pinned section, search, A–Z sort, QuickMix and Thumbprint first. Touch and hold a station
  for its options: play, pin, rename, delete, its liked and disliked songs, and the artists, songs and
  genres it is built from (with search to add more). "New" creates a station from a search.
- **Playing:** cover art, progress, like/dislike, play/pause and skip, with History and Up next lists where
  any song can be liked or disliked.
- **Settings:** Appearance, audio quality, power saving, background playback, and the full list of songs
  you have liked and disliked.
- **Themes:** Color, White (black on white) and Black (white on black). The two monochrome themes use no
  greys or animations, grayscale art, paged lists and slow-refresh progress for e-ink panels.
- Media-session controls (lock screen, Bluetooth), sleep timer, low-battery pause, resume of the last
  station on launch, and a Quit button.

It talks to Pandora's unofficial JSON v5 API (<http://6xq.net/playground/pandora-apidoc/json/>), which is
reverse-engineered and can break at any time. The client does not block ads or enforce or bypass skip
limits; it plays what the service returns and shows the service's errors. It is meant for personal,
sideloaded use and is not affiliated with Pandora.

## Android Auto

The app is a media app for Android Auto: your stations (pinned first) appear in the car's media list, and
Like and Dislike buttons sit on the now-playing screen. Because the app is installed from an APK rather than
the Play Store, Android Auto hides it until you turn on developer mode: open Android Auto settings, tap the
version number about ten times, open the developer settings from the menu, and enable **Unknown sources**.
This has not yet been tried in a car or the Desktop Head Unit.

## Building

Needs JDK 17 and the Android SDK (platform 35). Open the folder in Android Studio, or install the SDK and
Gradle 8.9 and run `gradle :app:assembleDebug`. The APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

Built and tested during development on a real device with a live Pandora account.

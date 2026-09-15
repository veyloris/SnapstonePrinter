# SnapstonePrinter

An Android app that pulls a random (or named) *Magic: The Gathering* card from
[Scryfall](https://scryfall.com), renders it as a borderless, 384px-wide 1-bit dithered "proxy
slip", and hands it off to an external Bluetooth thermal printer app to print. Built to feed a
cheap receipt printer for casual kitchen-table Magic — conjure a card, print it, play it.

> [!IMPORTANT]
> **This app is almost entirely AI-written.** I'm not an Android developer — this whole project,
> architecture, implementation, debugging, and UI included, was built by directing
> [Claude Code](https://claude.com/claude-code) (Anthropic's coding agent) turn by turn, with me
> reviewing and steering rather than writing the code myself. Treat this as a vibe-coded personal
> project, not production software from an experienced team. If something looks over-engineered,
> under-engineered, or weird, that's why.

<p align="center">
  <img src="screenshots/snapstone-wielder.png" alt="Snapstone Wielder mode showing a generated proxy slip" width="45%">
  <img src="screenshots/momirvig.png" alt="MomirVig mode's converted-mana-cost picker" width="45%">
</p>

## What it does

- **Snapstone Wielder mode** — taps Scryfall's `cards/random` for a random nonland card (or an
  exact card by name), dithers the art, and composes a print-ready slip: name, mana cost, type
  line, art, and rules text, laid out to match how it'll actually come out of the printer.
- **MomirVig mode** — a nod to the [Momir Basic](https://magic.wizards.com/en/formats/momir-basic)
  format: pick a converted mana cost and it conjures a random creature card of that cost, the way
  Momir Vig's ability does.
- **Double-faced cards handled properly** — true two-sided cards (transform, modal DFC) print as
  two sequential slips; split/flip/adventure cards (which share one piece of art) print both
  halves on the same slip instead of duplicating art across two prints.
- **Tone controls** — contrast/brightness sliders re-dither the already-downloaded art in memory,
  no network round-trip.
- **Session history** — reprint anything pulled this session without re-fetching it.
- **Remembers your printer app** — picks a target once from the share sheet, then skips straight
  to it on future prints (with a visible way to change or reset it).

## Why a thermal printer

384 dots wide at 203dpi (57mm) is the printer's native width — the whole rendering pipeline
(auto-levels → Floyd-Steinberg dithering → 1-bit output) targets that exact size, not "scale to
fit." The app doesn't talk to the printer directly; it hands a finished PNG to whatever Bluetooth
printer app you already have installed via a standard share intent, so it works with basically any
cheap receipt-printer app.

## Requirements

- A modern Android phone (**minSdk 36** — this is deliberate, not an oversight).
- A Bluetooth thermal printer and an app for it that accepts a shared PNG (this project doesn't
  include a printer driver).
- No API key needed — Scryfall's API is public and keyless.

## Building

Install a JDK matching `toolchainVersion` in
[the daemon toolchain file](gradle/gradle-daemon-jvm.properties), and install Android SDK
Command-Line Tools. Set `JAVA_HOME` to that JDK and `ANDROID_HOME` to the SDK directory;
add the SDK's `cmdline-tools/latest/bin` directory to your `PATH`.

Install the platform and build-tools packages listed in the `Install Android platform and
build tools` step of [CI](.github/workflows/ci.yml), and accept their SDK licenses with
`sdkmanager --licenses`. Consult [the application build file](app/build.gradle.kts) for
compile, target, and minimum SDK settings. Keep SDK setup aligned with that file when
updating the toolchain.

Run the same checks locally as CI:

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Allow network access for Gradle dependency resolution. Use the debug APK artifact from a
successful CI run for installation; inspect its test and lint report artifact for diagnostics.
See the [validation appendix](docs/migrations/2026-09-15-milestone-1-appendix.md) for measured
local results and remaining hosted verification. Keep emulator tests and physical printer
checks as separate validation; this baseline runs JVM unit tests, Android lint, and debug assembly.

## Status

A hobby project under active, casual development — not published to the Play Store, no guarantees
about stability or fitness for anything in particular.

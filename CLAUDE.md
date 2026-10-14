# CLAUDE.md

Guidance for Claude Code in Wiggins, the Android HiveMind client for OpenVoiceOS. If
this checkout sits in the three-repo workspace, `../CLAUDE.md` covers what spans the
repos (hub grants, release order, approvals, the public-repo scrub); this file doesn't
repeat it.

This repo is public: use the placeholders the docs already use (`hub.example.com`,
`<tenant-id>`, the `hivemind.access` role), never real hosts, IPs, tenant or client IDs.

## Sources of truth

- `SPEC.md`: behaviour, decisions, milestones and the backlog. Tick backlog items, with
  the date, when they land; record new decisions there.
- `docs/hivemind-protocol.md`: the wire protocol as the hub implements it, with source
  references. §11.4 is `allowed_types`, §16 the Noise handshake.
- `ovos-skill-waggle/WAGGLE.md` (the Waggle repo): the phone-action protocol, frozen as v1.
- Test vectors, generated, never hand-edited:
  - `app/src/test/resources/hivemind-vectors/`: from `tools/vectors/` (`generate.py`
    for the password handshake, `noise_vectors.py` for Noise; each script says how to
    rerun it).
  - `app/src/test/resources/waggle/rule_cases.json`: an unchanged copy of
    `ovos-skill-waggle/waggle/testdata/rule_cases.json`. Update it by copying.
- `docs/m2-assistant-spike.md`, `docs/m3-pomerium-auth.md`,
  `docs/alarm-followup-investigation.md`: the research behind the assistant role,
  sign-in and session echo.

## Layout

- `:app`, package `com.mulesipstea.wiggins`:
  - `Assistant.kt`: the app-scoped owner of the connection, conversation, speech and
    turn state. Main-thread confined; hub callbacks hop onto its scope.
  - `hivemind/`: `HiveProtocol` (the transport-free state machine), `PasswordHandshake`
    and `FrameCipher` (protocol v2), `Noise` (v3), `HiveMindClient` (the OkHttp
    websocket), `SessionContext`, `HubEvents`.
  - `speech/`: hub STT capture (`Recorder`, `Endpointer`, `WebRtcDetector`,
    `Transcription`), hub TTS (`HubSpeaker`, `PcmPlayer`), device TTS (`Speaker`),
    `AudioOutput` (audio_output_start/end), `Wav`, `Prerequisites`.
  - `waggle/`: the protocol (`Messages`, `RuleMatcher`, `IntentBuilder`, `queries/`), and
    `ClockRequests`, the ask card's sentence for Clock intents ("Dismisses the alarm at
    6:30 AM"), read from the extras the way the stock Clock reads them.
    `actions/`: `PhoneActions` runs requests within the rules; Room holds the rules and
    action log.
  - `settings/`: DataStore settings; `SecretStore` (Tink, keyed in Android Keystore)
    encrypts the key, password, tokens and Noise keys.
  - `net/`: `EntraSignIn` (AppAuth) and `ClientCertificates` (mTLS).
  - `ui/`: Compose screens. `AssistPanel` is the `ACTION_ASSIST` panel, `MessagesScreen`
    the Hub messages log.
- `:vad`: android-vad's WebRTC detector, vendored and built from source with a pinned
  NDK. Upstream sources stay unchanged; `vad/README.md` lists what differs.
- `design/`: `wiggins.svg` is the source; `make_drawables.py` generates the launcher
  icon and drawables.
- `tools/`: `release.sh`, `vectors/`, `mtls/client-cert.sh`, and `car/` (a virtual
  Bluetooth car for the emulator, `fake_car.py`, and `ui.sh` to tap the screen by text).

## Build and test

```sh
export JAVA_HOME=~/Android/jdk ANDROID_HOME=~/Android/Sdk
./gradlew --offline testDebugUnitTest   # every unit test, Robolectric included
./gradlew --offline assembleDebug
./gradlew --offline assembleRelease     # R8; unsigned without keystore.properties
```

- `--offline` works once the Gradle cache is warm; drop it after changing dependencies.
  After adding one, build the release too: R8 runs only there, and CI releases with it.
- `-q` prints nothing on success. Results are in
  `app/build/test-results/testDebugUnitTest/*.xml`. Two skips are expected:
  `LiveHubTest` and one `AvatarAnimationTest` case.
- Room's schemas are exported to `app/schemas/` and committed. `ActionDatabaseMigrationTest`
  builds the old database from those files rather than with `room-testing`.
- `LiveHubTest` runs only with `WIGGINS_LIVE_HUB` and the other `WIGGINS_LIVE_*`
  variables (see its KDoc). Point it at a throwaway local hivemind-core
  (`docs/hivemind-protocol.md` §16.6), never a production hub. A local hub writes to
  the real `~/.config` unless every `XDG_*_HOME` is set.
- Screenshots for design review: `./gradlew :app:recordRoborazziDebug --tests
  '*ScreenshotTest*'` writes PNGs to `app/build/outputs/roborazzi`. Nothing compares
  them against goldens.
- CI (`.github/workflows/ci.yml`) runs `testDebugUnitTest assembleDebug` on pushes to
  `main` and on pull requests.

## Releasing

- The version lives only in `app/build.gradle.kts` (`versionName`, `versionCode`).
- `tools/release.sh <version> [--push]` refuses unless you're on `main` with a clean
  tree, `HEAD == origin/main` and the tag is new. It bumps `versionCode`, commits
  "Release <version>" and makes the annotated tag `v<version>` ("Wiggins <version>").
  A suffix (`0.4.0-rc2`) makes a pre-release.
- Fix commits not pushed yet: push `main` first, then run the script. If the push has to
  wait for the user, do the script's local steps by hand (the same `sed`, commit message
  and `git tag -a`) and hand over `git push origin main v<version>`.
- A pushed tag is never moved or reused; fix forward with the next rc.
- The tag starts `.github/workflows/release.yml`. It checks the tag against
  `versionName`, tests, builds with R8, signs with the Actions secrets, refuses any
  other signing certificate, and publishes `wiggins-<version>.apk` with its sha256.
  Nothing local needs the release key.

## Emulator

- An Android 16 AOSP image without Google services, 2 GB RAM. For the device-speech
  paths, install a TTS engine (eSpeak NG from F-Droid) and a recognizer (FUTO Voice
  Input's standalone APK).
- Mic: turn on "Virtual microphone uses host audio input" in Extended controls, or run
  `adb emu avd hostmicon`.
  - Without a real mic: create a PulseAudio null sink, move only the emulator's
    (`qemu-system-x86_64`) source-output to its monitor, and play synthesized phrases
    into the sink.
  - Keep a faint noise bed playing, because FUTO reports "no audio detected" on digital
    silence.
- Gotchas:
  - With Wi-Fi and simulated cellular both up, a socket opened on cellular dies about
    30 s later, when Wi-Fi becomes the default network. Wiggins reconnects.
  - Android stores the assist target as a component. After moving the `ACTION_ASSIST`
    activity, pick None as the assistant and then Wiggins again.
  - Clock intents need `com.android.alarm.permission.SET_ALARM`. Every default rule's
    target must be in the manifest's `<queries>`. Android drops a background activity
    launch without an error.
  - The hub pins the phone's Noise static key, which isn't backed up. After a reinstall
    or Clear data, run `hivemind-core reset-noise-pin <client id>` on the hub.
  - Started with `-no-snapshot-save`, the AVD boots from its quickboot snapshot, so
    installs, data and settings roll back at every boot.
  - Lock-screen tests: `locksettings set-pin 1234` (and `clear --old 1234` after),
    `svc power stayon true`. Screenshots of the PIN prompt come out black (a secure
    window), so check `dumpsys window` and SystemUI's log ("Keyguard Bouncer is
    showing") instead, and type the PIN promptly: the prompt times out and the screen
    sleeps. Android drops toasts from apps whose notifications are off, Wiggins included.
  - Its clock can jump after boot, and a Clock alarm set then may never ring. Set test
    alarms a minute or two ahead once the emulator has been up a while.
- The car path without a car: `tools/car/README.md`. `fake_car.py` pairs with the
  emulator over its virtual Bluetooth and presses the voice button; the SCO link it gets
  carries only silence, so audio still needs a real headset or car.
- End to end without the real hub: a throwaway hivemind-core 4.15 and `ovos-messagebus`
  (as in `docs/hivemind-protocol.md` §16.6, with every `XDG_*_HOME` set), a client
  granted the uplink types below, Wiggins pointed at `ws://10.0.2.2:<port>`, and Waggle's
  `waggle-fake-hub --script` on the same bus to send scripted requests. The fake hub
  follows the first session it sees, so start it, then reconnect Wiggins by letting it
  idle-disconnect (60 s in the background) rather than force-stopping it.

## Phone actions

- **Default rules reach existing installs only through a migration.** `onCreate` seeds
  `ActionDatabase.DEFAULT_RULES` on a new database. For a new default, bump the database
  version and add a migration that inserts it only where the user has no rule for that
  action (`MIGRATION_1_2`), commit the exported schema, and extend
  `ActionDatabaseMigrationTest`. Put the action in the manifest's `<queries>` too, or it
  answers `no_handler`.
- **The stock Clock (AOSP DeskClock, which GrapheneOS ships):**
  - Every AlarmClock intent goes to one single-instance activity. A second intent that
    arrives while the first is still starting is dropped, though Wiggins answers `ok`.
    Waggle leaves 1 s between Clock intents to one phone.
  - It reports results only to a voice interactor, so `ok` means only that the launch
    was accepted.
  - `DISMISS_TIMER` affects ringing timers only, and no intent cancels a running timer.
  - `DISMISS_ALARM` always opens the Clock's UI. It reaches only alarms due within 24 h.
    Its label search is a case-sensitive substring match. If several alarms match, it
    opens a picker that crashes on Android 16.
  - `SNOOZE_ALARM` ignores `SNOOZE_DURATION`.
- **Launches need a visible Wiggins screen.** `PhoneActions` answers `launch_failed`
  otherwise. The assist panel doesn't show over the lock screen. Invoked on a locked
  phone, `AssistActivity` asks for the unlock (`requestDismissKeyguard`) and listens
  afterwards; while it waits, being stopped behind the keyguard mustn't finish it. The
  Clock's invisible trampolines (`SNOOZE_ALARM`, `DISMISS_TIMER`) work under the keyguard.
- **Locked sessions refuse every request** (SPEC "Locked, in the car"). A car's
  voice button may run Wiggins over the lock screen when the user allows it; then
  `PhoneActions` answers every intent and query `blocked`, since WAGGLE.md v1 has no
  "locked" code. Arming a trusted car must never come from a late broadcast: the
  connection receiver is in the manifest, and Android's unlock broadcast isn't used.

## Contracts the hub relies on

- **Session echo.** Keep `context.session` from downlink messages for our session id,
  and send it back on every uplink. Override only lang, units, formats and the
  timezone; the timezone goes inside the hub's location, never in place of it. Follow-ups
  (`get_response`, converse) break without it.
- **`recognizer_loop:record_begin` / `record_end`** around every listen, in both STT
  modes. They restart a waiting `get_response`'s 20 s timeout.
- **`recognizer_loop:audio_output_start` / `audio_output_end`** (`AudioOutput`):
  - Send one start and one end per playback run, i.e. the speech queue going from idle
    to busy and back to idle.
  - Stop, barge-in, closing the panel and Clear conversation also send the end.
  - A `speak` that plays nothing gets an immediate end.
  - `speak(wait=True)` blocks the skill for up to 15 s waiting for that end, so never
    hold the end until `ovos.utterance.handled`.
- **`device_locked`** in every uplink's message context, true only in a locked session.
  Never omit it: the hub may treat a Wiggins message without it as locked.
- **`user_watching`** in every uplink's message context: true only from a visible Wiggins
  screen on an unlocked phone, not over a hands-free link. With true, the hub doesn't
  speak confirmations the screen shows (WAGGLE.md "Watching"), so a turn may end with
  `ovos.utterance.handled` and no `speak`.
- **Turn end.** Only `ovos.utterance.handled` ends a turn. The 15 s reply and 30 s
  thinking timeouts are fallbacks.
- **Speech twins.** `speak` / `ovos.utterance.speak` and `mycroft.mic.listen` /
  `ovos.mic.listen` mean the same thing. Drop a twin with the same payload that
  arrives within 2 s.
- **Handshake.** Use the password handshake whenever the hub offers it, and Noise only
  when that's all it offers: hivemind-core 4.7–4.15 can't send over 64 KB over Noise.
  Never run argon2id under `HiveMindClient`'s lock, which every send takes: without a
  stored PSK, `HiveProtocol` returns `Output.DerivePsk`, the client derives the key on
  a background thread, then calls `onPsk` under the lock.
- **Hub speech.**
  - STT audio is raw 16 kHz mono s16 PCM with no WAV header, plus `sample_rate` and
    `sample_width` (hivemind-audio-binary-protocol 2.2). A header would reach the STT
    engine as noise; plugin 2.1.x, which wanted it, is no longer supported.
  - Never send `lang: "auto"`: the hub's STT client would then send the audio to a
    public language-detection server.
- **Uplink types the hub must grant.** hivemind-core 4.15 grants none by default:
  - `recognizer_loop:utterance`, `record_begin`, `record_end`, `audio_output_start`,
    `audio_output_end`, `b64_transcribe`;
  - `speak:b64_audio`;
  - `waggle.capabilities`, `waggle.intent.response`, `waggle.query.response`.

  A new type means updating the README's `allow-msg` list and SPEC's "Hub-side
  permissions", and telling the hub's owner.

## Conventions

- **Stack** (SPEC "Stack"):
  - Kotlin and Compose. kotlinx.serialization is the only JSON library. Room and
    DataStore for storage, Tink for secrets.
  - Open-source dependencies only, with no Play Services or Firebase, so F-Droid stays
    possible.
  - minSdk 34.
  - Platform JCA for crypto. BouncyCastle only for argon2id and X25519.
- **Structure.** Protocol logic lives in transport-free classes that unit tests drive
  directly (`HiveProtocol`, `RuleMatcher`, `AudioOutput`, `HubSpeaker`), and the Android
  glue stays thin. New wire code is checked byte for byte against Python-generated
  vectors.
- **Comments.** KDoc on classes and on anything non-obvious. Say why, briefly; match
  the surrounding density.
- **Commits.** A short imperative subject and a prose body that explains why, with the
  Co-Authored-By trailer. The author is the noreply identity already in the repo's git
  config. History is linear: fast-forward merges, no merge commits.

# Wiggins

An Android [HiveMind](https://github.com/JarbasHiveMind) client for OpenVoiceOS: it sends your questions to a HiveMind hub, shows and speaks the replies, and runs phone actions the hub asks for, within limits you set on the phone. See [SPEC.md](SPEC.md).

Status: M1–M5 are done: text and voice, remote access, hub speech and phone actions. Wiggins speaks both HiveMind handshakes, the password one and Noise (protocol v3), which hivemind-core 5 requires. With [ovos-skill-waggle](https://github.com/greennosedmule/ovos-skill-waggle) on the hub, Wiggins runs the hub's Waggle requests (setting alarms and timers, stopping ringing timers, dismissing and snoozing alarms, opening apps, dialing, and reading upcoming events, contacts or app names) within an allowlist edited only on the phone: each rule runs, asks first, or blocks, queries are off until enabled, and every request is in an action log. Wiggins can be the default digital assistant: the assist gesture opens a panel over the current app and starts listening, replies are shown and spoken, and it listens again when the hub asks a follow-up question. On a locked phone it asks for the unlock first, and an optional tone (by default only on Bluetooth) says when it starts listening. With **Car and headset** on, a car's steering-wheel button or a headset's voice button opens Wiggins over the Bluetooth hands-free link (SCO), and it listens and speaks through the car or headset. **When the phone is locked** decides what that button does on a locked phone: ask to unlock (the default), work through a trusted car once the phone has been unlocked while connected to it, or always. A locked session shows nothing from the conversation, runs no phone actions, and marks every message to the hub with `device_locked`, so the hub can hold back personal tools. Speech-to-text and text-to-speech are each done by the hub, if it has speech services (HiveMind's `hivemind-audio-binary-protocol` plugin, 2.2 or later for transcription), or by the phone's own recognizer app and TTS engine. Typing always works. It reaches the hub on a LAN or VPN, with a client certificate, or by signing in through an identity-aware proxy. A setup screen checks the hub's speech, the recognizer, TTS engine and assistant role.

## Install

Signed APKs are on the [releases page](https://github.com/greennosedmule/wiggins/releases). To get updates, add `https://github.com/greennosedmule/wiggins` to [Obtainium](https://github.com/ImranR98/Obtainium). Every release is signed with the same key; its certificate's SHA-256 is:

```
FB:B1:B0:F2:66:DC:35:D4:C9:84:20:C8:74:4D:D5:46:91:A7:1D:B2:B9:47:39:0F:44:FE:08:5C:49:F3:C8:75
```

## Build

Needs JDK 17+ (21 tested), the Android SDK (compileSdk 37) and the NDK version pinned in `vad/build.gradle.kts`.

```sh
export JAVA_HOME=~/Android/jdk ANDROID_HOME=~/Android/Sdk
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Connect to a hub

Register the phone on the hub, then enter the URL, access key and password in Wiggins' settings:

```sh
hivemind-core add-client --name <phone>
hivemind-core allow-msg recognizer_loop:utterance <id>
hivemind-core allow-msg recognizer_loop:record_begin <id>
hivemind-core allow-msg recognizer_loop:record_end <id>
hivemind-core allow-msg recognizer_loop:audio_output_start <id>
hivemind-core allow-msg recognizer_loop:audio_output_end <id>
# For hub speech, one type per command:
hivemind-core allow-msg recognizer_loop:b64_transcribe <id>
hivemind-core allow-msg speak:b64_audio <id>
# For phone actions (Waggle):
hivemind-core allow-msg waggle.capabilities <id>
hivemind-core allow-msg waggle.intent.response <id>
hivemind-core allow-msg waggle.query.response <id>
# For the persona's tools on the Permissions screen:
hivemind-core allow-msg ovos.persona.list <id>
```

For hub speech, the hub needs `hivemind-audio-binary-protocol` 2.2 or later. Since 0.5.0 Wiggins sends transcription audio as raw 16 kHz PCM, which plugin 2.1.x can't read; on such a hub, set speech-to-text to the phone.

The URL is `ws://host:5678` on a LAN or VPN (HiveMind encrypts payloads either way) or `wss://` through a TLS proxy.

Wiggins uses the password handshake when the hub offers it, and the Noise handshake when that's all the hub offers (hivemind-core 5). The hub keeps the phone's Noise key after the first Noise connection, so after reinstalling Wiggins or clearing its data, run `hivemind-core reset-noise-pin <id>` on the hub.

## Layout

- `app/src/main/java/.../speech/`: speech. `Recorder` and `Endpointer` record an utterance and find its end, `Transcription` gets it transcribed by the hub (with early transcription), `HubSpeaker` and `PcmPlayer` play the hub's voice, and `Speaker` is the device TTS engine.
- `app/src/main/java/.../waggle/`: the Waggle protocol (request parsing, the rule matcher, intent building, and the three queries); `actions/` runs the hub's requests within the rules, with the rules and the action log in Room.
- `vad/`: the WebRTC voice activity detector from android-vad, vendored and built from source with a pinned NDK (see `vad/README.md`).
- `app/src/main/java/.../hivemind/`: the HiveMind protocol. `HiveProtocol` is the transport-free state machine, `PasswordHandshake` and `FrameCipher` are the password handshake's crypto, `Noise` is the Noise handshake and transport (protocol v3), and `HiveMindClient` is the OkHttp websocket.
- `docs/hivemind-protocol.md`: the wire protocol as spoken by the targeted hivemind-core, with sources.
- `tools/car/`: a virtual Bluetooth car for the emulator, to test the car's voice button and locked sessions without a car (see its README).
- `tools/vectors/`: regenerates the Python test vectors in `app/src/test/resources/hivemind-vectors/` from the hub image.
- `design/`: Wiggins himself. `wiggins.svg` is the source; `make_drawables.py` generates the launcher icon and in-app drawables.

## Releasing

`tools/release.sh 0.2.0 --push` sets the version in `app/build.gradle.kts` (bumping `versionCode`), commits, tags `v0.2.0` and pushes. The tag starts `.github/workflows/release.yml`, which checks the tag against the version, runs the tests, builds the release with R8, signs it, checks it's signed by the release key above, and publishes it with GitHub's generated notes. A version with a suffix (`0.2.0-beta1`) becomes a pre-release.

Signing uses repository secrets (`WIGGINS_KEYSTORE_BASE64`, `WIGGINS_KEYSTORE_PASSWORD`, `WIGGINS_KEY_ALIAS`, `WIGGINS_KEY_PASSWORD`). Without them, `./gradlew assembleRelease` builds an unsigned APK; locally, a gitignored `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) signs it.

## License

Apache-2.0. The Libre Baskerville font is under the SIL Open Font License (`licenses/LibreBaskerville-OFL.txt`). The vendored voice activity detector in `vad/` is under the MIT license (android-vad) and the BSD 3-Clause license (WebRTC).

Most of Wiggins' code and artwork was generated by AI (Claude) under human direction: the spec, design decisions, review and testing are human; most of the code is not. Purely AI-generated material may not be protected by copyright in some countries. The license is permissive partly for that reason, and so that OVOS and HiveMind projects can take any of it upstream.

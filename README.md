# Wiggins

An Android [HiveMind](https://github.com/JarbasHiveMind) client for OpenVoiceOS: it sends your questions to a HiveMind hub, shows and speaks the replies, and (from M4) runs phone actions the hub asks for, within limits you set on the phone. See [SPEC.md](SPEC.md).

Status: **M2, assistant**. Wiggins can be the default digital assistant: the assist gesture opens it and starts your speech recognizer (for example FUTO Voice Input), replies are shown and spoken with the system TTS engine, and it listens again when the hub asks a follow-up question. Typing always works. A setup screen checks for the recognizer, TTS engine, assistant role and hub.

## Build

Needs JDK 17+ (21 tested) and the Android SDK (compileSdk 37).

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
```

The URL is `ws://host:5678` on a LAN or VPN (HiveMind encrypts payloads either way) or `wss://` through a TLS proxy.

## Layout

- `app/src/main/java/.../hivemind/`: the HiveMind protocol. `HiveProtocol` is the transport-free state machine, `PasswordHandshake` and `FrameCipher` are the crypto, and `HiveMindClient` is the OkHttp websocket.
- `docs/hivemind-protocol.md`: the wire protocol as spoken by the targeted hivemind-core, with sources.
- `tools/vectors/`: regenerates the Python test vectors in `app/src/test/resources/hivemind-vectors/` from the hub image.

## License

Apache-2.0

# A car on the emulator

`fake_car.py` is a virtual Bluetooth hands-free unit for testing the "In the car" path
(SPEC) without a car. It runs [Bumble](https://github.com/google/bumble) as a second
device on the Android emulator's virtual Bluetooth (netsim), in HFP's hands-free role,
and presses the voice button (`AT+BVRA=1`) the way a steering-wheel button does.
`ui.sh` taps the emulator's screen by visible text, for pairing and similar steps
without a window.

## What it can and can't show

It shows the whole signalling path:
- a button press reaching Wiggins as `ACTION_VOICE_COMMAND`;
- Wiggins taking up the voice-recognition session;
- the SCO audio link opening and closing;
- sessions Wiggins starts itself (`+BVRA` to the car);
- behaviour on a locked phone.

Real audio still needs a real phone with a headset or a car:
- the emulator's SCO link carries only silence;
- a real car's timeouts, codecs and media handling aren't modelled.

## Setup

```sh
uv venv --python 3.12 /tmp/fake-car-venv
VIRTUAL_ENV=/tmp/fake-car-venv uv pip install -r tools/car/requirements.txt
```

Start the emulator first (its Bluetooth must be on), then the car. With no options it
uses the emulator's netsim:

```sh
/tmp/fake-car-venv/bin/python tools/car/fake_car.py --keys /tmp/fake-car-keys.json
```

Pair it like a real car:

```sh
adb shell am start -a android.settings.BLUETOOTH_PAIRING_SETTINGS
tools/car/ui.sh tap "Wiggins Test Car"
tools/car/ui.sh tap PAIR
```

## Driving a test

- **Presses:** the car presses the voice button 4 s after the phone connects
  (`--press-after`). `--repeat 45` presses again every 45 s, and `--no-press` never
  presses on its own. While it runs:
  - `kill -USR1 <pid>` presses the button;
  - `kill -USR2 <pid>` switches the car off (drops the connection) or back on
    (reconnects to the last phone), like turning the ignition.
  The car prints its pid at start. Use the signals rather than restarting the car,
  because a restarted car gets a new address and has to be paired again.
- **The log:** the car prints:
  - `AT+BVRA=1 -> OK` or an error, meaning Android didn't hand the session to an app
    in time;
  - `+BVRA from the phone`, for sessions Wiggins starts or ends;
  - the audio link going up and down.
- **Logcat:** the phone's side is in `HeadsetService`, for `startVoiceRecognition` and
  `stopVoiceRecognition`, and in `BluetoothHeadsetServiceJni`'s `AudioStateCallback`:
  0 is off, 2 is connected.
- **Pairing again:** netsim assigns the car's address, so a car run without its old
  `--keys` file can't connect to a phone that remembers it: Android reports an
  authentication failure. Forget the old car in the phone's Bluetooth settings first.
  `--connect <phone address>/P` reconnects a car whose keys are in `--keys`. The car
  prints its own address on start.

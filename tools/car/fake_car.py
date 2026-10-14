#!/usr/bin/env python3
"""A virtual Bluetooth hands-free unit (a "car") for testing Wiggins on the emulator.

It joins the Android emulator's virtual Bluetooth (netsim) as a second device that
speaks HFP in the hands-free role, the way a car kit or headset does, so the
"In the car" path (SPEC) can be exercised without a car:

  * it is discoverable; pair it from the emulator's Settings like a real car;
  * once the phone opens HFP, it presses the voice button (AT+BVRA=1) after
    --press-after seconds, and again every --repeat seconds if given;
  * it logs +BVRA from the phone (sessions the phone starts or ends) and accepts
    the SCO/eSCO audio link, counting the audio packets it receives;
  * while it runs, SIGUSR1 presses the voice button, and SIGUSR2 switches the car
    off (drops the connection) or back on (reconnects to the last phone).

The emulator carries the signalling and the SCO link but no real audio: the
packets that arrive are silence. Audio needs a real phone and headset or car.

Setup and use: see tools/car/README.md.
"""
import argparse
import asyncio
import logging
import os
import signal

from bumble import hci, hfp, rfcomm
from bumble.core import PhysicalTransport
from bumble.device import Device
from bumble.keys import JsonKeyStore
from bumble.transport import open_transport

# Class of device: Audio service, Audio/Video major class, hands-free minor class.
HANDS_FREE_CLASS = 0x200408


def log(message: str) -> None:
    print(f'[car] {message}', flush=True)


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    p.add_argument('--name', default='Wiggins Test Car', help='Bluetooth name shown on the phone')
    p.add_argument('--keys', default='fake_car_keys.json', help='where the pairing keys are kept between runs')
    p.add_argument('--press-after', type=float, default=4.0,
                   help='seconds after the HFP connection comes up before pressing the voice button')
    p.add_argument('--repeat', type=float, default=0.0,
                   help='press the voice button again every this many seconds (0: press once)')
    p.add_argument('--no-press', action='store_true', help='never press the voice button')
    p.add_argument('--connect', metavar='PHONE_ADDRESS',
                   help='connect to an already paired phone (e.g. 01:00:00:BB:BB:BB/P) instead of waiting for it')
    p.add_argument('--transport', default='android-netsim', help='Bumble transport (default: the emulator\'s netsim)')
    return p.parse_args()


async def main(args: argparse.Namespace) -> None:
    async with await open_transport(args.transport) as (source, sink):
        # Bumble needs an address to power on, but netsim assigns the one the phone sees.
        device = Device.with_hci(args.name, hci.Address('F0:F1:F2:F3:F4:F5'), source, sink)
        device.classic_enabled = True
        device.class_of_device = HANDS_FREE_CLASS
        device.keystore = JsonKeyStore('fake_car', args.keys)
        config = hfp.HfConfiguration(
            supported_hf_features=[
                hfp.HfFeature.VOICE_RECOGNITION_ACTIVATION,
                hfp.HfFeature.CLI_PRESENTATION_CAPABILITY,
                hfp.HfFeature.REMOTE_VOLUME_CONTROL,
                hfp.HfFeature.ESCO_S4_SETTINGS_SUPPORTED,
            ],
            supported_hf_indicators=[hfp.HfIndicator.BATTERY_LEVEL],
            supported_audio_codecs=[hfp.AudioCodec.CVSD],
        )

        state = {'hf': None, 'connection': None, 'phone': None}

        async def press(hf: hfp.HfProtocol) -> None:
            log('pressing the voice button: AT+BVRA=1')
            try:
                # Android answers OK only once an app takes the session up.
                await hf.execute_command('AT+BVRA=1', timeout=10)
                log('AT+BVRA=1 -> OK')
            except Exception as e:  # ERROR from the phone, or no answer
                log(f'AT+BVRA=1 -> {type(e).__name__}')

        def on_dlc(dlc: rfcomm.DLC) -> None:
            log('HFP channel open')
            hf = hfp.HfProtocol(dlc, config)
            state['hf'] = hf
            hf.on(hf.EVENT_VOICE_RECOGNITION, lambda state: log(f'+BVRA from the phone: {state.name}'))
            hf.on(hf.EVENT_AG_INDICATOR, lambda ind: log(f'phone indicator {ind.indicator.name}={ind.current_status}'))
            asyncio.create_task(drive(hf))

        async def drive(hf: hfp.HfProtocol) -> None:
            runner = asyncio.create_task(hf.run())
            for _ in range(100):
                if hf._slc_initialized:  # Bumble has no public flag for this
                    break
                await asyncio.sleep(0.1)
            log(f'service level connection up: {hf._slc_initialized}')
            if not args.no_press:
                await asyncio.sleep(args.press_after)
                while state['hf'] is hf:
                    await press(hf)
                    if args.repeat <= 0:
                        break
                    await asyncio.sleep(args.repeat)
            try:
                await runner
            except Exception:
                pass  # the connection went away

        def on_connection(connection) -> None:
            log(f'connected to {connection.peer_address}')
            state['connection'] = connection
            state['phone'] = connection.peer_address

            def on_disconnection(reason) -> None:
                log(f'disconnected ({reason})')
                state['hf'] = None
                state['connection'] = None

            connection.on('disconnection', on_disconnection)

            def on_sco_request(link_type: int) -> None:
                log(f'audio link requested (link type {link_type}); accepting')
                key = (hfp.DefaultCodecParameters.SCO_CVSD_D1
                       if link_type == hci.HCI_Connection_Complete_Event.LinkType.SCO
                       else hfp.DefaultCodecParameters.ESCO_CVSD_S3)
                params = hfp.ESCO_PARAMETERS[key]
                asyncio.create_task(device.send_command(
                    hci.HCI_Enhanced_Accept_Synchronous_Connection_Request_Command(
                        bd_addr=connection.peer_address, **params.asdict())))

            connection.on('sco_request', on_sco_request)

        def on_sco(link) -> None:
            log('audio link up')
            got = {'packets': 0, 'bytes': 0, 'sound': 0}

            def sink(packet) -> None:
                got['packets'] += 1
                got['bytes'] += len(packet.data)
                got['sound'] += sum(1 for b in packet.data if b not in (0x00, 0x55))

            link.sink = sink

            async def report() -> None:
                while link.handle in device.sco_links:
                    await asyncio.sleep(2)
                    log(f'audio received: {got["packets"]} packets, {got["bytes"]} bytes, '
                        f'{got["sound"]} non-silent bytes')
                log('audio link down')

            asyncio.create_task(report())

        server = rfcomm.Server(device)
        channel = server.listen(on_dlc)
        device.sdp_service_records = {1: hfp.make_hf_sdp_records(1, channel, config)}
        device.on('connection', on_connection)
        device.on('sco_connection', on_sco)
        device.on('sco_connection_failure', lambda status: log(f'audio link failed: {status}'))

        await device.power_on()
        await device.set_discoverable(True)
        await device.set_connectable(True)
        log(f'on, discoverable as "{args.name}" at {device.public_address}')

        async def connect(phone) -> None:
            connection = await device.connect(phone, transport=PhysicalTransport.BR_EDR)
            await connection.authenticate()
            await connection.encrypt()
            found = await hfp.find_ag_sdp_record(connection)
            if not found:
                raise SystemExit('the phone offers no HFP audio gateway')
            multiplexer = await rfcomm.Client(connection).start()
            on_dlc(await multiplexer.open_dlc(found[0]))

        async def switch() -> None:
            if state['connection'] is not None:
                log('switching off')
                await state['connection'].disconnect()
            elif state['phone'] is not None:
                log('switching on')
                await connect(state['phone'])
            else:
                log('no phone to reconnect to yet')

        def run_logged(coroutine) -> None:
            async def wrapped():
                try:
                    await coroutine
                except Exception as e:
                    log(f'failed: {e!r}')
            asyncio.create_task(wrapped())

        loop = asyncio.get_running_loop()
        loop.add_signal_handler(
            signal.SIGUSR1,
            lambda: run_logged(press(state['hf'])) if state['hf'] else log('no HFP connection to press on'),
        )
        loop.add_signal_handler(signal.SIGUSR2, lambda: run_logged(switch()))
        log(f'pid {os.getpid()}: kill -USR1 presses the voice button, kill -USR2 switches the car off or on')

        if args.connect:
            await connect(args.connect)

        await loop.create_future()


if __name__ == '__main__':
    logging.basicConfig(level=logging.WARNING)
    try:
        asyncio.run(main(parse_args()))
    except KeyboardInterrupt:
        pass

#!/usr/bin/env python3
"""Capture real websocket frames between hivemind-core and Python clients.

Runs INSIDE the hub image (exact versions), with a throwaway hub on 127.0.0.1:5678,
a 15-line fake OVOS messagebus on 127.0.0.1:8181/core and a fake ovos-core/skill
that answers every recognizer_loop:utterance the way ovos-core routes replies
(intent message via .reply(), speak via .forward(), then ovos.utterance.handled).
Nothing is deterministic here (real random IVs/nonces/RSA); the derived session
key is recorded so every frame can be decrypted in a test.

Output: app/src/test/resources/hivemind-vectors/live_capture.json

Run from the repo root (the container is removed afterwards):

  podman run --rm --user root -e HOME=/tmp/hmhome -v "$PWD":/w:Z -w /w \
      --entrypoint /home/hivemind/.venv/bin/python \
      docker.io/smartgic/hivemind-listener:stable-20260922 tools/vectors/live_capture.py
"""
import base64
import hashlib
import json
import os
import socket
import subprocess
import sys
import textwrap
import threading
import time

HOME = os.environ.get("HOME", "/tmp/hmhome")
os.makedirs(HOME, exist_ok=True)
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(REPO, "app", "src", "test", "resources", "hivemind-vectors", "live_capture.json")
HUB_LOG = os.path.join(HOME, "hub.log")

ACCESS_KEY = "live0123456789abcdef0123456789ab"
PASSWORD = "livepassword0123456789abcdef0123"
NAME = "wiggins-live"

SERVER_JSON = {
    "agent_protocol": {"module": "hivemind-ovos-agent-plugin",
                       "hivemind-ovos-agent-plugin": {"host": "127.0.0.1", "port": 8181}},
    "binary_protocol": {"module": None},
    "network_protocol": {"hivemind-websocket-plugin": {"host": "127.0.0.1", "port": 5678, "ssl": False}},
    "database": {"module": "hivemind-json-db-plugin",
                 "hivemind-json-db-plugin": {"name": "clients", "subfolder": "hivemind-core"}},
    "presence": {"enabled": False},
}

FAKE_BUS = textwrap.dedent("""
    from tornado import web, ioloop, websocket
    clients = set()
    class H(websocket.WebSocketHandler):
        def open(self): clients.add(self)
        def on_message(self, m):
            for c in list(clients): c.write_message(m)   # like ovos-messagebus: everyone, incl. sender
        def on_close(self): clients.discard(self)
        def check_origin(self, o): return True
    web.Application([("/core", H)]).listen(8181, "127.0.0.1")
    ioloop.IOLoop.current().start()
""")


def wait_port(port, timeout=60):
    end = time.time() + timeout
    while time.time() < end:
        try:
            socket.create_connection(("127.0.0.1", port), 1).close()
            return
        except OSError:
            time.sleep(0.3)
    raise RuntimeError(f"port {port} never opened")


def start_stack():
    cfg_dir = os.path.join(HOME, ".config", "hivemind-core")
    os.makedirs(cfg_dir, exist_ok=True)
    with open(os.path.join(cfg_dir, "server.json"), "w") as f:
        json.dump(SERVER_JSON, f)
    py = sys.executable
    bus = subprocess.Popen([py, "-c", FAKE_BUS])
    wait_port(8181)
    hmcore = os.path.join(os.path.dirname(py), "hivemind-core")
    out = subprocess.run([hmcore, "add-client", "--name", NAME, "--access-key", ACCESS_KEY,
                          "--password", PASSWORD], capture_output=True, text=True)
    add_client_stdout = out.stdout
    log = open(HUB_LOG, "w")
    hub = subprocess.Popen([hmcore, "listen"], stdout=log, stderr=subprocess.STDOUT)
    wait_port(5678)
    time.sleep(1.0)
    return bus, hub, add_client_stdout


def start_fake_core():
    from ovos_bus_client import MessageBusClient
    core = MessageBusClient(host="127.0.0.1", port=8181)
    core.run_in_thread()
    core.connected_event.wait(10)

    def on_utt(message):
        utt = message.data["utterances"][0]
        intent = message.reply("fake-skill.wiggins:WhatTime.intent", {"utterance": utt})
        core.emit(intent)
        core.emit(intent.forward("speak", {"utterance": "It's 3:14 PM.", "expect_response": False,
                                           "meta": {"skill": "fake-skill.wiggins", "dialog": "time.current"},
                                           "lang": message.data.get("lang", "en-US")}))
        core.emit(message.reply("ovos.utterance.handled", {"name": "WhatTime"}))

    core.on("recognizer_loop:utterance", on_utt)
    return core


def hub_log_mark():
    return os.path.getsize(HUB_LOG)


def hub_log_since(mark, levels=("WARNING", "ERROR", "Traceback", "Error", "Forwarding")):
    with open(HUB_LOG, errors="replace") as f:
        f.seek(mark)
        lines = f.read().splitlines()
    return [l for l in lines if any(x in l for x in levels)][:40]


# ---------------------------------------------------------------------------
# A) stock python client (hivemind_bus_client 0.4.4 HiveMessageBusClient)
# ---------------------------------------------------------------------------
def capture_stock_client():
    from ovos_bus_client.message import Message
    from ovos_utils.fakebus import FakeBus
    from hivemind_bus_client.client import HiveMessageBusClient
    from hivemind_bus_client.encryption import decrypt_from_json

    frames = []
    t0 = time.time()

    class Recording(HiveMessageBusClient):
        def create_client(self):
            app = super().create_client()
            orig_send = app.send

            def send(data, opcode=1):
                frames.append({"t": round(time.time() - t0, 3), "dir": "client->hub",
                               "opcode": "binary" if opcode == 2 else "text",
                               "wire": data if isinstance(data, str) else data.hex()})
                return orig_send(data, opcode)

            app.send = send
            return app

        def on_message(self, *args):
            m = args[-1]
            frames.append({"t": round(time.time() - t0, 3), "dir": "hub->client",
                           "opcode": "binary" if isinstance(m, bytes) else "text",
                           "wire": m if isinstance(m, str) else m.hex()})
            return super().on_message(*args)

    mark = hub_log_mark()
    c = Recording(key=ACCESS_KEY, password=PASSWORD, host="ws://127.0.0.1", port=5678,
                  useragent="PyStockClient", self_signed=True)
    got = threading.Event()
    replies = []

    def on_any(msg):
        replies.append(msg if isinstance(msg, str) else msg.serialize())

    c.on_mycroft("speak", lambda m: (replies.append(m.serialize()), got.set()))
    c.connect(bus=FakeBus(), site_id="phone")
    key = c.crypto_key
    cipher, encoding = str(getattr(c.cipher, "value", c.cipher)), str(getattr(c.json_encoding, "value", c.json_encoding))
    c.emit(Message("recognizer_loop:utterance", {"utterances": ["what time is it"], "lang": "en-US"}))
    got.wait(10)
    time.sleep(1.0)
    c.close()
    time.sleep(0.5)
    for fr in frames:
        if fr["opcode"] == "text" and '"ciphertext"' in fr["wire"]:
            fr["plaintext"] = decrypt_from_json(key, fr["wire"], cipher=cipher, encoding=encoding)
            fr["encrypted"] = True
        else:
            fr["plaintext"], fr["encrypted"] = fr["wire"], False
        try:
            p = json.loads(fr["plaintext"])
            fr["hive_msg_type"] = p["msg_type"]
            if p["msg_type"] == "bus":
                fr["bus_type"] = p["payload"]["type"]
        except Exception:
            pass
    return {"name": "stock hivemind_bus_client 0.4.4 HiveMessageBusClient (binarize default True, offers all "
                    "encodings/ciphers)",
            "negotiated": {"cipher": cipher, "encoding": encoding, "key_hex": key.hex() if isinstance(key, bytes) else key},
            "got_speak": got.is_set(), "frames": frames, "hub_log": hub_log_since(mark)}


# ---------------------------------------------------------------------------
# B) raw reference client = what Wiggins should do (recommended config)
# ---------------------------------------------------------------------------
class Raw:
    def __init__(self, useragent, key, path_prefix="/"):
        import websocket
        auth = base64.b64encode(f"{useragent}:{key}".encode()).decode()
        self.url = f"ws://127.0.0.1:5678{path_prefix}?authorization={auth}"
        self.frames = []
        self.t0 = time.time()
        self.ws = websocket.create_connection(self.url, timeout=5)
        self.ws.settimeout(5)

    def send(self, text):
        self.frames.append({"t": round(time.time() - self.t0, 3), "dir": "client->hub", "opcode": "text", "wire": text})
        self.ws.send(text)

    def recv(self, timeout=5):
        import websocket
        self.ws.settimeout(timeout)
        try:
            op, fr = self.ws.recv_data_frame(control_frame=True)
        except websocket.WebSocketTimeoutException:
            return None
        except Exception as e:
            self.frames.append({"t": round(time.time() - self.t0, 3), "dir": "hub->client", "event": repr(e)})
            return "EXC"
        name = {1: "text", 2: "binary", 8: "close", 9: "ping", 10: "pong"}.get(op, str(op))
        rec = {"t": round(time.time() - self.t0, 3), "dir": "hub->client", "opcode": name}
        if op == 8:
            data = fr.data
            rec["close_code"] = int.from_bytes(data[:2], "big") if len(data) >= 2 else None
            rec["close_reason"] = data[2:].decode(errors="replace")
        elif op == 1:
            rec["wire"] = fr.data.decode()
        else:
            rec["data_hex"] = fr.data.hex()
        self.frames.append(rec)
        return rec


def ref_handshake(r, password, ciphers, encodings, ping=False):
    from poorman_handshake.symmetric.utils import match_hsub
    hello = r.recv()
    shake_req = r.recv()
    iv = os.urandom(8)
    env = (iv + hashlib.sha256(iv + password.encode()).digest()).hex()[:48]
    r.send(json.dumps({"msg_type": "shake",
                       "payload": {"binarize": False, "encodings": encodings, "ciphers": ciphers,
                                   "envelope": env}}))
    resp = r.recv()
    p = json.loads(resp["wire"])["payload"]
    ok = match_hsub(p["envelope"], password)
    salt = bytes(a ^ b for a, b in zip(iv, bytes.fromhex(p["envelope"][:16])))
    key = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 100000, 32)
    return {"client_iv_hex": iv.hex(), "server_envelope_verified": ok, "key": key,
            "cipher": p["cipher"], "encoding": p["encoding"]}


def capture_reference_client():
    from hivemind_bus_client.encryption import encrypt_as_json, decrypt_from_json
    mark = hub_log_mark()
    r = Raw("Wiggins", ACCESS_KEY)
    hs = ref_handshake(r, PASSWORD, ["CHACHA20-POLY1305"], ["JSON-B64"])
    key, cipher, encoding = hs["key"], hs["cipher"], hs["encoding"]
    session = {"session_id": "0d3c7a8e-2a4b-4f59-9d0e-5b8c1f2a3e4d", "lang": "en-US", "site_id": "phone",
               "location": {"timezone": {"code": "America/New_York", "name": "Eastern Standard Time",
                                         "offset": -18000000, "dstOffset": 3600000}},
               "system_unit": "imperial", "time_format": "half", "date_format": "MDY"}

    def seal(obj):
        return encrypt_as_json(key, json.dumps(obj), cipher=cipher, encoding=encoding)

    # minimal envelopes: only msg_type + payload (other keys optional)
    r.send(seal({"msg_type": "hello", "payload": {"session": session, "site_id": "phone"}}))
    # websocket-level ping: tornado answers with pong automatically
    r.ws.ping(b"wiggins")
    r.recv(2)
    r.send(seal({"msg_type": "bus", "payload": {
        "type": "recognizer_loop:utterance",
        "data": {"utterances": ["what time is it"], "lang": "en-US"},
        "context": {"source": "Wiggins", "destination": "HiveMind", "session": session}}}))
    for _ in range(10):
        if r.recv(3) is None:
            break
    # a BUS type the client is not allowed to send: silently dropped
    r.send(seal({"msg_type": "bus", "payload": {"type": "speak", "data": {"utterance": "nope"},
                                                "context": {"session": session}}}))
    dropped = r.recv(2) is None
    # unencrypted BUS after handshake: hub only warns ("Message was unencrypted") and processes it
    r.send(json.dumps({"msg_type": "bus", "payload": {
        "type": "recognizer_loop:utterance", "data": {"utterances": ["plaintext test"], "lang": "en-US"},
        "context": {"session": session}}}))
    plain_replies = 0
    for _ in range(5):
        x = r.recv(3)
        if x is None:
            break
        plain_replies += 1
    r.ws.close()
    for fr in r.frames:
        w = fr.get("wire")
        if w is None:
            continue
        if '"ciphertext"' in w:
            fr["plaintext"] = decrypt_from_json(key, w, cipher=cipher, encoding=encoding)
            fr["encrypted"] = True
        else:
            fr["plaintext"], fr["encrypted"] = w, False
        p = json.loads(fr["plaintext"])
        fr["hive_msg_type"] = p.get("msg_type")
        if p.get("msg_type") == "bus":
            fr["bus_type"] = p["payload"]["type"]
    return {"name": "reference client (recommended config: CHACHA20-POLY1305, JSON-B64, binarize off, "
                    "minimal envelopes with only msg_type+payload)",
            "url": r.url, "client_iv_hex": hs["client_iv_hex"],
            "server_envelope_verified": hs["server_envelope_verified"],
            "negotiated": {"cipher": cipher, "encoding": encoding, "key_hex": key.hex()},
            "unauthorized_speak_dropped_silently": dropped,
            "unencrypted_bus_after_handshake_replies": plain_replies,
            "frames": r.frames, "hub_log": hub_log_since(mark)}


def capture_errors():
    from hivemind_bus_client.encryption import encrypt_as_json
    out = []
    # 1. wrong access key
    mark = hub_log_mark()
    r = Raw("Wiggins", "wrong-key")
    for _ in range(3):
        if r.recv(3) in (None, "EXC"):
            break
    out.append({"name": "invalid access key", "url": r.url, "frames": r.frames, "hub_log": hub_log_since(mark)})
    # 2. wrong password: hub answers the handshake anyway; client's verify fails; if the client still
    #    sends encrypted data, the hub fails to decrypt and aborts the TCP connection.
    mark = hub_log_mark()
    r = Raw("Wiggins", ACCESS_KEY)
    hs = ref_handshake(r, "wrong-password", ["CHACHA20-POLY1305"], ["JSON-B64"])
    r.send(encrypt_as_json(hs["key"], json.dumps({"msg_type": "hello", "payload": {}}),
                           cipher=hs["cipher"], encoding=hs["encoding"]))
    for _ in range(3):
        if r.recv(3) in (None, "EXC"):
            break
    out.append({"name": "wrong password", "server_envelope_verified": hs["server_envelope_verified"],
                "frames": r.frames, "hub_log": hub_log_since(mark)})
    # 3. no authorization parameter at all
    mark = hub_log_mark()
    try:
        import websocket
        ws = websocket.create_connection("ws://127.0.0.1:5678/", timeout=5)
        try:
            op, fr = ws.recv_data_frame(control_frame=True)
            res = {"opcode": op, "data_hex": fr.data.hex()}
        except Exception as e:
            res = {"exception": repr(e)}
    except Exception as e:
        res = {"connect_exception": repr(e)}
    out.append({"name": "missing authorization query parameter", "result": res, "hub_log": hub_log_since(mark)})
    # 4. percent-encoded authorization (what a URL builder may produce): breaks auth when b64 has + / =
    # useragent chosen so the base64 contains '+', '/' and '=' padding (non-ASCII is UTF-8 encoded)
    from urllib.parse import quote
    import websocket
    ua = "Wigginsx>\u00ff\u00ff"
    auth = base64.b64encode(f"{ua}:{ACCESS_KEY}".encode()).decode()
    assert "+" in auth and "/" in auth and auth.endswith("="), auth
    for label, value in (("raw (correct)", auth), ("percent-encoded (WRONG)", quote(auth, safe=""))):
        mark = hub_log_mark()
        try:
            ws = websocket.create_connection(f"ws://127.0.0.1:5678/?authorization={value}", timeout=5)
            try:
                op, fr = ws.recv_data_frame(control_frame=True)
                res = {"opcode": op, "data_prefix": fr.data[:60].decode(errors="replace"),
                       "close_code": int.from_bytes(fr.data[:2], "big") if op == 8 and len(fr.data) >= 2 else None}
            except Exception as e:
                res = {"exception": repr(e)}
            ws.close()
        except Exception as e:
            res = {"connect_exception": repr(e)}
        out.append({"name": f"authorization with + / = sent {label}", "useragent": ua, "raw": auth,
                    "sent": value, "result": res, "hub_log": hub_log_since(mark)})
    return out


def main():
    bus, hub, add_client_stdout = start_stack()
    try:
        core = start_fake_core()
        result = {
            "_meta": {
                "description": "Real frames captured against a throwaway hivemind-core started from the hub image "
                               "(websocket plugin on 127.0.0.1:5678, JSON client DB, fake OVOS bus + fake "
                               "ovos-core). Randomness is real; negotiated.key_hex decrypts the frames. "
                               "'t' is seconds since the connection was opened.",
                "generator": "tools/vectors/live_capture.py",
                "hub_image": "docker.io/smartgic/hivemind-listener:stable-20260922",
                "add_client_output": add_client_stdout,
            },
            "reference_client": capture_reference_client(),
            "stock_python_client": capture_stock_client(),
            "errors": capture_errors(),
        }
        core.close()
    finally:
        hub.terminate()
        bus.terminate()
        hub.wait(10)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print("wrote", os.path.relpath(OUT, REPO))


if __name__ == "__main__":
    main()

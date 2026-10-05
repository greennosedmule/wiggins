#!/usr/bin/env python3
"""Generate HiveMind wire-protocol test vectors for Wiggins (Kotlin client).

The vectors are produced by the *same Python library versions* that run in the
deployed hub image docker.io/smartgic/hivemind-listener:stable-20260922
(hivemind-core 3.4.0, hivemind_bus_client 0.4.4, poorman-handshake 1.0.1,
hivemind-websocket-protocol 0.0.3, ovos_bus_client 1.3.7, pycryptodomex 3.23.0,
z85base91 0.0.5, pybase64 1.5.0). requirements.txt next to this file is the
full `pip freeze` of that image.

Output: app/src/test/resources/hivemind-vectors/*.json (relative to repo root).

Rerun (from the repo root), either way gives byte-identical output:

  # A) inside the hub image itself (exact versions, nothing to install)
  podman run --rm --user root -v "$PWD":/w:Z -w /w \
      --entrypoint /home/hivemind/.venv/bin/python \
      docker.io/smartgic/hivemind-listener:stable-20260922 tools/vectors/generate.py

  # B) uv venv pinned to the image's freeze (Python 3.13)
  uv venv --python 3.13 /tmp/hm-vectors-venv
  VIRTUAL_ENV=/tmp/hm-vectors-venv uv pip install -r tools/vectors/requirements.txt
  /tmp/hm-vectors-venv/bin/python tools/vectors/generate.py

Determinism (the libraries use randomness in three places; all are patched):
  * PasswordHandShake IVs: poorman_handshake.symmetric.generate_iv (the name the
    PasswordHandShake class calls) is replaced by a function that pops the next
    IV from IV_QUEUE, which each vector sets explicitly.
  * AEAD nonces: hivemind_bus_client.encryption.encrypt_AES and
    encrypt_ChaCha20_Poly1305 are wrapped so that `nonce=` is always passed.
    The nonce is taken from NONCE_QUEUE if a vector set one, otherwise from a
    counter: SHA-256(b"wiggins-nonce" || uint32_be(n))[:size] (n = 0, 1, ...).
    encrypt_bin looks these functions up as module globals at call time, so the
    real hub code (hivemind_core.protocol) uses the patched versions too.
  * The hub's RSA identity key (only shown in the server HELLO "pubkey") is
    generated with RSA.generate(2048, randfunc=<SHA-256 counter DRBG>).
  * XDG_* dirs point into a temp dir so nothing in $HOME is read or written and
    the hub's server.json / client DB start from library defaults.

Nothing here talks to the network: the "full session" vectors drive the real
hivemind-core / hivemind-websocket-protocol handler code in-process with a fake
websocket, and a minimal reference client (written from docs/hivemind-protocol.md)
on the other side.
"""
import os
import sys
import tempfile

# --- isolate from the user's config before importing any ovos/hivemind module ---
_TMP = tempfile.mkdtemp(prefix="hm-vectors-")
for _k in ("XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME", "XDG_STATE_HOME"):
    os.environ[_k] = os.path.join(_TMP, _k.lower())
    os.makedirs(os.environ[_k], exist_ok=True)
os.environ["OVOS_DEFAULT_LOG_LEVEL"] = "ERROR"

import base64  # noqa: E402
import hashlib  # noqa: E402
import json  # noqa: E402
from importlib.metadata import version  # noqa: E402
from types import SimpleNamespace  # noqa: E402

from ovos_utils.log import LOG  # noqa: E402

LOG.set_level("ERROR")

from Cryptodome.PublicKey import RSA  # noqa: E402
from ovos_bus_client.message import Message  # noqa: E402
from ovos_utils.fakebus import FakeBus  # noqa: E402

import poorman_handshake.symmetric as pm_sym  # noqa: E402
from poorman_handshake import PasswordHandShake  # noqa: E402
from poorman_handshake.symmetric.utils import create_hsub, match_hsub  # noqa: E402

import hivemind_bus_client.encryption as enc  # noqa: E402
from hivemind_bus_client.client import HiveMessageBusClient  # noqa: E402
from hivemind_bus_client.message import HiveMessage, HiveMessageType  # noqa: E402

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(REPO, "app", "src", "test", "resources", "hivemind-vectors")

PACKAGES = ["hivemind-core", "hivemind_bus_client", "hivemind-websocket-protocol",
            "poorman-handshake", "ovos_bus_client", "ovos-utils", "pycryptodomex",
            "z85base91", "pybase64", "tornado"]

# ----------------------------------------------------------------------------
# determinism patches
# ----------------------------------------------------------------------------
IV_QUEUE = []
NONCE_QUEUE = []
_nonce_ctr = [0]


def _next_iv(key_length=8):
    assert IV_QUEUE, "a vector forgot to queue a PasswordHandShake IV"
    iv = IV_QUEUE.pop(0)
    assert len(iv) == key_length
    return iv


pm_sym.generate_iv = _next_iv  # PasswordHandShake.generate_handshake calls this name


def _next_nonce(size):
    if NONCE_QUEUE:
        n = NONCE_QUEUE.pop(0)
        assert len(n) == size, (len(n), size)
        return n
    n = hashlib.sha256(b"wiggins-nonce" + _nonce_ctr[0].to_bytes(4, "big")).digest()[:size]
    _nonce_ctr[0] += 1
    return n


_orig_aes = enc.encrypt_AES
_orig_chacha = enc.encrypt_ChaCha20_Poly1305


def _aes(key, text, nonce=None, mode=enc.AES.MODE_GCM):
    return _orig_aes(key, text, nonce=nonce or _next_nonce(enc.AES_NONCE_SIZE), mode=mode)


def _chacha(key, text, nonce=None):
    return _orig_chacha(key, text, nonce=nonce or _next_nonce(enc.CHACHA20_NONCE_SIZE))


enc.encrypt_AES = _aes
enc.encrypt_ChaCha20_Poly1305 = _chacha


class _DRBG:
    """SHA-256 counter stream, used only to make the test RSA key reproducible."""

    def __init__(self, seed: bytes):
        self.seed, self.ctr, self.buf = seed, 0, b""

    def __call__(self, n):
        while len(self.buf) < n:
            self.buf += hashlib.sha256(self.seed + self.ctr.to_bytes(8, "big")).digest()
            self.ctr += 1
        out, self.buf = self.buf[:n], self.buf[n:]
        return out


def meta(description, **extra):
    m = {"description": description,
         "generator": "tools/vectors/generate.py",
         "hub_image": "docker.io/smartgic/hivemind-listener:stable-20260922",
         "versions": {p: version(p) for p in PACKAGES}}
    m.update(extra)
    return m


def write(name, obj):
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print("wrote", os.path.relpath(path, REPO))


# ----------------------------------------------------------------------------
# 1. websocket URL / authorization
# ----------------------------------------------------------------------------
def auth_vectors():
    from hivemind_websocket_protocol import HiveMindTornadoWebSocket
    cases = []
    inputs = [
        ("Wiggins", "4f0c1d2e3b4a59687766554433221100", "192.0.2.10", 5678, False),
        ("HiveMessageBusClientV0.0.1", "0123456789abcdef0123456789abcdef", "127.0.0.1", 5678, False),
        # chosen so the base64 contains '+', '/' and '=' padding: must be sent raw
        ("Wiggins/Pixel 8", "fb?>~ff", "hivemind.example.org", 443, True),
    ]
    for ua, key, host, port, tls in inputs:
        auth = base64.b64encode(f"{ua}:{key}".encode("utf-8")).decode("ascii")
        url = HiveMessageBusClient.build_url(key=key, host=host, port=port, useragent=ua, ssl=tls)
        # what the hub sees as request.uri (websocket-client sends path "/" + query)
        uri = "/?authorization=" + auth
        dec = HiveMindTornadoWebSocket.decode_auth(uri.split("/?authorization=")[-1])
        assert dec == (ua, key)
        cases.append({"useragent": ua, "access_key": key, "host": host, "port": port, "tls": tls,
                      "authorization": auth,
                      "url": url,
                      "request_target": uri,
                      "has_plus_or_slash": ("+" in auth) or ("/" in auth)})
    assert any(c["has_plus_or_slash"] for c in cases)
    write("auth.json", {
        "_meta": meta(
            "authorization query parameter. authorization = base64_std_padded(utf8(useragent + ':' + access_key)). "
            "url is exactly what hivemind_bus_client 0.4.4 HiveMessageBusClient.build_url returns; the HTTP "
            "request-target the hub must receive is '/?authorization=<authorization>' with NO percent-encoding "
            "(hub does uri.split('/?authorization=')[-1] then base64-decodes, no URL-decoding). "
            "useragent and access_key must not contain ':'."),
        "cases": cases})


# ----------------------------------------------------------------------------
# 2. password handshake
# ----------------------------------------------------------------------------
def pswd_case(name, password, client_iv, server_iv, server_password=None):
    server_password = password if server_password is None else server_password
    client = PasswordHandShake(password)
    server = PasswordHandShake(server_password)

    IV_QUEUE[:] = [client_iv]
    client_env = client.generate_handshake()          # sent by client in HANDSHAKE.envelope
    IV_QUEUE[:] = [server_iv]
    server_env = server.generate_handshake()          # sent by hub in HANDSHAKE.envelope
    server.receive_handshake(client_env)              # hub does NOT verify the client envelope
    client_verified = client.verify(server_env)       # python client: receive_and_verify
    hub_would_verify = server.verify(client_env)      # (hub never calls this, informative only)
    client.receive_handshake(server_env)

    salt = bytes(a ^ b for a, b in zip(client_iv, server_iv))
    assert salt == client.salt == server.salt
    ck, sk = client.secret, server.secret
    assert ck == hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 100000, 32)
    full_hash = hashlib.sha256(client_iv + password.encode("utf-8")).hexdigest()
    case = {
        "name": name,
        "password": password,
        "client_iv_hex": client_iv.hex(),
        "server_iv_hex": server_iv.hex(),
        "client_envelope": client_env,
        "server_envelope": server_env,
        "client_envelope_sha256_full_hex": full_hash,
        "salt_hex": salt.hex(),
        "pbkdf2": {"prf": "HMAC-SHA256", "iterations": 100000, "dklen": 32},
        "client_key_hex": ck.hex(),
        "server_key_hex": sk.hex(),
        "keys_match": ck == sk,
        "client_verifies_server_envelope": client_verified,
        "server_envelope_matches_password": hub_would_verify,
    }
    if server_password != password:
        case["server_password"] = server_password
    return case


def handshake_vectors():
    cases = [
        pswd_case("typical add-client password (32 hex chars)",
                  "5d41402abc4b2a76b9719d911017c592",
                  bytes.fromhex("0001020304050607"), bytes.fromhex("f0e1d2c3b4a59687")),
        pswd_case("ascii password",
                  "correct horse battery staple",
                  bytes.fromhex("1122334455667788"), bytes.fromhex("8877665544332211")),
        pswd_case("non-ascii password (UTF-8 bytes are hashed)",
                  "pässwörd-🐝",
                  bytes.fromhex("deadbeefcafebabe"), bytes.fromhex("0123456789abcdef")),
        pswd_case("identical IVs -> all-zero salt (still valid)",
                  "5d41402abc4b2a76b9719d911017c592",
                  bytes.fromhex("a5a5a5a5a5a5a5a5"), bytes.fromhex("a5a5a5a5a5a5a5a5")),
        pswd_case("password MISMATCH (hub record has a different password): envelope fails verification and keys differ",
                  "5d41402abc4b2a76b9719d911017c592",
                  bytes.fromhex("0001020304050607"), bytes.fromhex("f0e1d2c3b4a59687"),
                  server_password="not-the-same-password"),
    ]
    # extra: hsub matching rules
    hsub_checks = []
    for hs, pw in [(cases[0]["server_envelope"], cases[0]["password"]),
                   (cases[0]["server_envelope"].upper(), cases[0]["password"]),
                   (cases[0]["server_envelope"][:47], cases[0]["password"]),
                   (create_hsub(cases[0]["password"], bytes(8), 80), cases[0]["password"])]:
        hsub_checks.append({"hsub": hs, "password": pw, "match_hsub": match_hsub(hs, pw)})
    write("password_handshake.json", {
        "_meta": meta(
            "poorman_handshake 1.0.1 PasswordHandShake. envelope = lowercase_hex(iv8 || SHA256(iv8 || utf8(password)))[:48] "
            "(16 hex chars of IV + first 32 hex chars = 16 bytes of the digest). salt = client_iv XOR server_iv (8 bytes). "
            "key = PBKDF2-HMAC-SHA256(utf8(password), salt, 100000 iterations, 32 bytes). "
            "Client must verify the server envelope (match_hsub) before deriving; the hub never verifies the client's, "
            "so a wrong password only shows up as AEAD failures afterwards."),
        "cases": cases,
        "hsub_match_checks": hsub_checks})
    return cases[0]


# ----------------------------------------------------------------------------
# 3. AEAD encryption / decryption
# ----------------------------------------------------------------------------
UTTERANCE_PLAINTEXT = None  # filled in messages section, reused here


def enc_case(name, key, cipher, encoding, plaintext, nonce):
    NONCE_QUEUE[:] = [nonce]
    wire = enc.encrypt_as_json(key, plaintext, cipher=cipher, encoding=encoding)
    assert not NONCE_QUEUE
    obj = json.loads(wire)
    dec = enc.get_decoder(encoding)
    ct, tag, n = dec(obj["ciphertext"]), dec(obj["tag"]), dec(obj["nonce"])
    assert n == nonce
    assert enc.decrypt_from_json(key, wire, cipher=cipher, encoding=encoding) == plaintext
    NONCE_QUEUE[:] = [nonce]
    binary = enc.encrypt_bin(key, plaintext, cipher=cipher)
    assert binary == n + ct + tag
    kb = key if isinstance(key, bytes) else key.encode("utf-8")
    return {"name": name, "cipher": cipher, "encoding": encoding,
            "key_hex": kb.hex(), "key_len": len(kb),
            "key_as_given": key if isinstance(key, str) else None,
            "nonce_hex": nonce.hex(), "plaintext": plaintext,
            "plaintext_utf8_len": len(plaintext.encode("utf-8")),
            "ciphertext_hex": ct.hex(), "tag_hex": tag.hex(),
            "wire": wire,
            "binary_frame_hex": binary.hex()}


def encryption_vectors(derived_key):
    psk = "a1b2c3d4e5f60718"  # legacy pre-shared crypto_key: 16 ASCII chars used as raw UTF-8 bytes
    short = "hello wörld 🐝"
    aes_nonce = bytes.fromhex("000102030405060708090a0b0c0d0e0f")
    cc_nonce = bytes.fromhex("a0a1a2a3a4a5a6a7a8a9aaab")
    cases = []
    broken = []
    for encoding in [e.value for e in enc.SupportedEncodings]:
        if encoding == "JSON-Z85P":
            # z85base91 0.0.5: Z85P.decode(Z85P.encode(x)) raises "Invalid Z85 character", so the
            # library cannot decrypt its own JSON-Z85P output. Never offer JSON-Z85P.
            NONCE_QUEUE[:] = [cc_nonce]
            w = enc.encrypt_as_json(derived_key, short, cipher="CHACHA20-POLY1305", encoding=encoding)
            try:
                enc.decrypt_from_json(derived_key, w, cipher="CHACHA20-POLY1305", encoding=encoding)
                rt = "ok"
            except Exception as e:
                rt = f"{type(e).__name__}: {e}"
            broken.append({"encoding": encoding, "wire": w, "python_roundtrip": rt})
            continue
        cases.append(enc_case(f"chacha20 {encoding} short", derived_key, "CHACHA20-POLY1305", encoding, short, cc_nonce))
        cases.append(enc_case(f"aes-256-gcm {encoding} short", derived_key, "AES-GCM", encoding, short, aes_nonce))
    cases.append(enc_case("chacha20 JSON-B64 utterance HiveMessage", derived_key, "CHACHA20-POLY1305",
                          "JSON-B64", UTTERANCE_PLAINTEXT, cc_nonce))
    cases.append(enc_case("aes-256-gcm JSON-HEX utterance HiveMessage", derived_key, "AES-GCM",
                          "JSON-HEX", UTTERANCE_PLAINTEXT, aes_nonce))
    cases.append(enc_case("aes-256-gcm JSON-B64 empty plaintext", derived_key, "AES-GCM", "JSON-B64", "", aes_nonce))
    cases.append(enc_case("LEGACY pre-shared 16-char crypto_key -> AES-128-GCM, JSON-HEX", psk, "AES-GCM",
                          "JSON-HEX", short, aes_nonce))

    # decrypt-only vectors
    dcases = []
    base = enc_case("x", derived_key, "CHACHA20-POLY1305", "JSON-B64", short, cc_nonce)
    b = json.loads(base["wire"])
    # 1) tag appended to ciphertext, no "tag" field (accepted: "web crypto compatibility")
    ct_tag = base64.b64encode(bytes.fromhex(base["ciphertext_hex"] + base["tag_hex"])).decode()
    w = json.dumps({"ciphertext": ct_tag, "nonce": b["nonce"]})
    assert enc.decrypt_from_json(derived_key, w, cipher="CHACHA20-POLY1305", encoding="JSON-B64") == short
    dcases.append({"name": "no tag field: ciphertext = ct||tag", "cipher": "CHACHA20-POLY1305",
                   "encoding": "JSON-B64", "key_hex": derived_key.hex(), "wire": w,
                   "expect": "ok", "plaintext": short})
    # 2) flipped tag bit
    badtag = bytearray(bytes.fromhex(base["tag_hex"]))
    badtag[0] ^= 1
    w = json.dumps({"ciphertext": b["ciphertext"], "tag": base64.b64encode(bytes(badtag)).decode(), "nonce": b["nonce"]})
    try:
        enc.decrypt_from_json(derived_key, w, cipher="CHACHA20-POLY1305", encoding="JSON-B64")
        raise AssertionError("tampered tag accepted")
    except enc.DecryptionKeyError:
        pass
    dcases.append({"name": "tampered tag", "cipher": "CHACHA20-POLY1305", "encoding": "JSON-B64",
                   "key_hex": derived_key.hex(), "wire": w, "expect": "auth_failure"})
    # 3) AES-GCM with a 12-byte nonce in the JSON: hub concatenates nonce||ct||tag and then takes the
    #    first 16 bytes as the nonce, so this FAILS. AES-GCM nonces must be 16 bytes for this hub.
    NONCE_QUEUE[:] = [aes_nonce]
    from Cryptodome.Cipher import AES
    c = AES.new(derived_key, AES.MODE_GCM, nonce=aes_nonce[:12])
    ct, tag = c.encrypt_and_digest(short.encode())
    NONCE_QUEUE.clear()
    w = json.dumps({"ciphertext": base64.b64encode(ct).decode(), "tag": base64.b64encode(tag).decode(),
                    "nonce": base64.b64encode(aes_nonce[:12]).decode()})
    try:
        enc.decrypt_from_json(derived_key, w, cipher="AES-GCM", encoding="JSON-B64")
        ok = True
    except enc.DecryptionKeyError:
        ok = False
    assert not ok
    dcases.append({"name": "AES-GCM with 12-byte nonce is REJECTED by this hub (must be 16)",
                   "cipher": "AES-GCM", "encoding": "JSON-B64", "key_hex": derived_key.hex(),
                   "wire": w, "expect": "auth_failure"})
    # 4) uppercase hex is accepted by the hub's decoder (binascii.unhexlify)
    hx = enc_case("y", derived_key, "AES-GCM", "JSON-HEX", short, aes_nonce)
    o = json.loads(hx["wire"])
    w = json.dumps({k: v.upper() for k, v in o.items()})
    assert enc.decrypt_from_json(derived_key, w, cipher="AES-GCM", encoding="JSON-HEX") == short
    dcases.append({"name": "uppercase hex accepted", "cipher": "AES-GCM", "encoding": "JSON-HEX",
                   "key_hex": derived_key.hex(), "wire": w, "expect": "ok", "plaintext": short})

    write("encryption.json", {
        "_meta": meta(
            "hivemind_bus_client 0.4.4 encrypt_as_json / decrypt_from_json. wire = json.dumps({ciphertext, tag, nonce}) "
            "each field = encoding(bytes). AES-GCM: 16-byte nonce, 16-byte tag, key 16/24/32 bytes. "
            "CHACHA20-POLY1305 (RFC 8439): 12-byte nonce, 16-byte tag, 32-byte key. No AAD. "
            "binary_frame_hex = nonce||ciphertext||tag (what encrypt_bin returns; only used with binarize). "
            "plaintext is UTF-8 encoded before encryption. Key derived in password_handshake.json case 0 unless "
            "key_as_given is set (legacy pre-shared crypto_key string, UTF-8 bytes used directly)."),
        "encrypt_cases": cases,
        "decrypt_cases": dcases,
        "broken_encodings": broken})


# ----------------------------------------------------------------------------
# 4 + 5. message envelopes and a full in-process session against real hub code
# ----------------------------------------------------------------------------
ACCESS_KEY = "4f0c1d2e3b4a59687766554433221100"
PASSWORD = "5d41402abc4b2a76b9719d911017c592"
PSK = "a1b2c3d4e5f60718"
USERAGENT = "Wiggins"
CLIENT_NAME = "wiggins-pixel"
SESSION_ID = "6b1e3c1f-6a8f-4d7e-9a43-0c2c5b1e7f10"
SITE_ID = "phone"


def wiggins_session():
    """Session dict the Kotlin client should send (HELLO and every BUS context)."""
    return {
        "session_id": SESSION_ID,
        "lang": "en-US",
        "site_id": SITE_ID,
        "location": {
            "timezone": {"code": "America/New_York", "name": "Eastern Standard Time",
                         "offset": -18000000, "dstOffset": 3600000},
        },
        "system_unit": "imperial",
        "time_format": "half",
        "date_format": "MDY",
    }


def utterance_message(text="what time is it"):
    return HiveMessage(HiveMessageType.BUS, payload=Message(
        "recognizer_loop:utterance",
        {"utterances": [text], "lang": "en-US"},
        {"source": USERAGENT, "destination": "HiveMind", "platform": USERAGENT,
         "session": wiggins_session()}))


def build_hub():
    """Real HiveMindListenerProtocol + OVOS agent on a FakeBus, with a fake skill."""
    from hivemind_bus_client.identity import NodeIdentity
    from hivemind_core.database import ClientDatabase
    from hivemind_core.protocol import HiveMindListenerProtocol
    from ovos_bus_client.hpm import OVOSProtocol

    pem = os.path.join(_TMP, "hub_identity.pem")
    if not os.path.exists(pem):
        key = RSA.generate(2048, randfunc=_DRBG(b"wiggins-test-hub-rsa"))
        with open(pem, "wb") as f:
            f.write(key.export_key("PEM"))
    identity = NodeIdentity()
    identity.private_key = pem

    class InProcOVOSProtocol(OVOSProtocol):
        def __post_init__(self):  # don't connect to a real messagebus
            self.register_bus_handlers()

    bus = FakeBus(session_id="hub-fakebus")
    agent = InProcOVOSProtocol(bus=bus, config={})
    with ClientDatabase() as db:  # same as `hivemind-core add-client` (commits on exit)
        if not db.get_client_by_api_key(ACCESS_KEY):
            db.add_client(CLIENT_NAME, ACCESS_KEY, crypto_key=PSK, password=PASSWORD)
    db = ClientDatabase()
    HiveMindListenerProtocol.clients.clear()
    hm = HiveMindListenerProtocol(agent_protocol=agent, identity=identity, db=db)

    bus_log = []

    def fake_core(message):
        # mimic ovos-core: intent dispatch is message.reply(...), skills speak via .forward(...)
        bus_log.append(message.serialize())
        if message.msg_type != "recognizer_loop:utterance":
            return
        utt = message.data["utterances"][0]
        intent = message.reply("fake-skill.wiggins:WhatTime.intent", {"utterance": utt})
        bus.emit(intent)
        bus.emit(intent.forward("speak", {"utterance": "It's 3:14 PM.", "expect_response": False,
                                          "meta": {"skill": "fake-skill.wiggins", "dialog": "time.current"},
                                          "lang": message.data.get("lang", "en-US")}))
        bus.emit(message.reply("ovos.utterance.handled", {"name": "WhatTime"}))

    bus.on("recognizer_loop:utterance", fake_core)
    return hm, bus_log


class FakeWS:
    """Stands in for tornado's handler so the REAL HiveMindTornadoWebSocket.open/on_message run."""
    from hivemind_websocket_protocol import HiveMindTornadoWebSocket as _H
    decode_auth = staticmethod(_H.decode_auth)
    open = _H.open
    on_message = _H.on_message

    def __init__(self, uri, hm, sink):
        self.request = SimpleNamespace(uri=uri)
        self.hm_protocol = hm
        self.loop = SimpleNamespace(install=lambda: None)
        self.sink = sink
        self.closed = False

    def write_message(self, payload, is_bin=False):
        self.sink(payload, is_bin)

    def close(self, *a):
        self.closed = True


class RefClient:
    """Minimal client written from docs/hivemind-protocol.md (what Wiggins should do)."""

    def __init__(self, ciphers, encodings, client_iv):
        self.ciphers, self.encodings, self.client_iv = ciphers, encodings, client_iv
        self.key = None
        self.cipher = self.encoding = None

    def handshake_payload(self):
        hs = hashlib.sha256(self.client_iv + PASSWORD.encode()).digest()
        envelope = (self.client_iv + hs).hex()[:48]
        return {"binarize": False, "encodings": self.encodings, "ciphers": self.ciphers, "envelope": envelope}

    def on_server_handshake(self, payload):
        env = payload["envelope"]
        assert match_hsub(env, PASSWORD), "server envelope does not match our password"
        salt = bytes(a ^ b for a, b in zip(self.client_iv, bytes.fromhex(env[:16])))
        self.key = hashlib.pbkdf2_hmac("sha256", PASSWORD.encode(), salt, 100000, 32)
        self.cipher, self.encoding = payload["cipher"], payload["encoding"]

    def seal(self, hive_json):
        return enc.encrypt_as_json(self.key, hive_json, cipher=self.cipher, encoding=self.encoding)

    def open(self, wire):
        if self.key and "ciphertext" in json.loads(wire):
            return enc.decrypt_from_json(self.key, wire, cipher=self.cipher, encoding=self.encoding)
        return wire


def run_session(name, ciphers, encodings, client_iv, server_iv):
    hm, bus_log = build_hub()
    frames = []

    def s2c(payload, is_bin):
        frames.append({"dir": "hub->client", "opcode": "binary" if is_bin else "text", "wire": payload})

    auth = base64.b64encode(f"{USERAGENT}:{ACCESS_KEY}".encode()).decode()
    ws = FakeWS("/?authorization=" + auth, hm, s2c)
    ws.open()  # hub sends HELLO + HANDSHAKE
    assert not ws.closed
    cl = RefClient(ciphers, encodings, client_iv)

    def c2s(hive: HiveMessage, encrypt: bool):
        plain = hive.serialize()
        wire = cl.seal(plain) if encrypt else plain
        frames.append({"dir": "client->hub", "opcode": "text", "wire": wire})
        ws.on_message(wire)

    # client reacts to the hub's HANDSHAKE request
    c2s(HiveMessage(HiveMessageType.HANDSHAKE, cl.handshake_payload()), encrypt=False)
    assert not IV_QUEUE, "hub did not consume the queued server IV"
    return hm, ws, cl, frames, c2s, bus_log


def full_session(name, ciphers, encodings, client_iv, server_iv):
    IV_QUEUE[:] = [server_iv]  # consumed by the hub's PasswordHandShake.generate_handshake
    hm, ws, cl, frames, c2s, bus_log = run_session(name, ciphers, encodings, client_iv, server_iv)
    shake = json.loads(frames[-1]["wire"])
    assert shake["msg_type"] == "shake" and "envelope" in shake["payload"], frames[-1]
    cl.on_server_handshake(shake["payload"])
    c2s(HiveMessage(HiveMessageType.HELLO, {"session": wiggins_session(), "site_id": SITE_ID}), encrypt=True)
    c2s(utterance_message(), encrypt=True)
    # annotate with plaintexts
    for fr in frames:
        if fr["opcode"] == "text":
            fr["plaintext"] = cl.open(fr["wire"]) if fr["dir"] == "hub->client" else None
            fr["encrypted"] = "ciphertext" in json.loads(fr["wire"])
    # client->hub plaintexts: decrypt with the session key too
    for fr in frames:
        if fr["dir"] == "client->hub":
            fr["plaintext"] = cl.open(fr["wire"])
        fr["hive_msg_type"] = json.loads(fr["plaintext"])["msg_type"]
        p = json.loads(fr["plaintext"])["payload"]
        if fr["hive_msg_type"] == "bus":
            fr["bus_type"] = p["type"]
    peer = next(iter(hm.clients))
    return {
        "name": name,
        "inputs": {"useragent": USERAGENT, "access_key": ACCESS_KEY, "password": PASSWORD,
                   "legacy_crypto_key_in_db": PSK, "client_db_name": CLIENT_NAME,
                   "client_iv_hex": client_iv.hex(), "server_iv_hex": server_iv.hex(),
                   "client_offers": {"ciphers": ciphers, "encodings": encodings, "binarize": False}},
        "negotiated": {"cipher": cl.cipher, "encoding": cl.encoding, "key_hex": cl.key.hex()},
        "peer": peer,
        "nonce_rule": "nonce_n = SHA256(b'wiggins-nonce' || uint32_be(n))[:size], n counts every encryption in "
                      "this file in order (both directions share the counter)",
        "frames": frames,
        "messages_hub_injected_into_ovos_bus": bus_log,
    }


def bad_cases():
    out = []
    # invalid access key -> no HELLO, handler.close()
    hm, _ = build_hub()
    sent = []
    auth = base64.b64encode(f"{USERAGENT}:nope".encode()).decode()
    ws = FakeWS("/?authorization=" + auth, hm, lambda p, b: sent.append(p))
    ws.open()
    out.append({"name": "invalid access key", "frames_from_hub": sent, "hub_closed_socket": ws.closed,
                "note": "tornado close() with no code: client sees a close frame with no status (1005)"})
    # unauthorized bus message type is dropped silently
    IV_QUEUE[:] = [bytes.fromhex("f0e1d2c3b4a59687")]
    hm, ws, cl, frames, c2s, bus_log = run_session("x", ["CHACHA20-POLY1305"], ["JSON-B64"],
                                                   bytes.fromhex("0001020304050607"), None)
    cl.on_server_handshake(json.loads(frames[-1]["wire"])["payload"])
    c2s(HiveMessage(HiveMessageType.HELLO, {"session": wiggins_session(), "site_id": SITE_ID}), encrypt=True)
    n_before = len(bus_log)
    c2s(HiveMessage(HiveMessageType.BUS, payload=Message("speak", {"utterance": "not allowed"},
                                                        {"session": wiggins_session()})), encrypt=True)
    out.append({"name": "BUS message whose type is not in allowed_types",
                "sent_plaintext": frames[-1] and cl.open(frames[-1]["wire"]),
                "forwarded_to_ovos": len(bus_log) > n_before, "reply_frames": 0,
                "note": "hub logs a warning and drops it; nothing is sent back"})
    # no cipher overlap -> hub disconnects
    IV_QUEUE[:] = [bytes.fromhex("f0e1d2c3b4a59687")]
    hm, _ = build_hub()
    sent = []
    ws = FakeWS("/?authorization=" + base64.b64encode(f"{USERAGENT}:{ACCESS_KEY}".encode()).decode(), hm,
                lambda p, b: sent.append(p))
    ws.open()
    cl = RefClient(["XCHACHA"], ["JSON-B64"], bytes.fromhex("0001020304050607"))
    try:
        ws.on_message(json.dumps(HiveMessage(HiveMessageType.HANDSHAKE, cl.handshake_payload()).as_dict))
        res = "no exception"
    except Exception as e:  # _norm_cipher raises InvalidCipher for unknown names -> tornado aborts socket
        res = f"{type(e).__name__}: {e}"
    IV_QUEUE.clear()
    out.append({"name": "unknown cipher name in client HANDSHAKE", "hub_result": res,
                "hub_closed_socket": ws.closed,
                "note": "unknown names raise in _norm_cipher/_norm_encoding -> exception in on_message -> "
                        "tornado aborts the TCP connection (no close frame, client sees 1006). Known-but-not-"
                        "allowed names lead to client.disconnect() (close frame, no status)."})
    return out


def message_vectors_and_sessions():
    global UTTERANCE_PLAINTEXT
    UTTERANCE_PLAINTEXT = utterance_message().serialize()

    s1 = full_session("recommended: CHACHA20-POLY1305 + JSON-B64, binarize off",
                      ["CHACHA20-POLY1305"], ["JSON-B64"],
                      bytes.fromhex("0001020304050607"), bytes.fromhex("f0e1d2c3b4a59687"))
    s2 = full_session("alternative: AES-GCM (256-bit, 16-byte nonce) + JSON-HEX",
                      ["AES-GCM"], ["JSON-HEX"],
                      bytes.fromhex("1122334455667788"), bytes.fromhex("8877665544332211"))
    s3 = full_session("preference order: client offers [AES-GCM, CHACHA20-POLY1305] x [JSON-HEX, JSON-B64] -> "
                      "hub picks the client's first allowed of each",
                      ["AES-GCM", "CHACHA20-POLY1305"], ["JSON-HEX", "JSON-B64"],
                      bytes.fromhex("0001020304050607"), bytes.fromhex("f0e1d2c3b4a59687"))
    assert s3["negotiated"]["cipher"] == "AES-GCM" and s3["negotiated"]["encoding"] == "JSON-HEX"

    def pick(sess, direction, mt, bt=None):
        for fr in sess["frames"]:
            if fr["dir"] == direction and fr["hive_msg_type"] == mt and (bt is None or fr.get("bus_type") == bt):
                return fr["plaintext"]
        raise KeyError((direction, mt, bt))

    msgs = {
        "server_hello": pick(s1, "hub->client", "hello"),
        "server_handshake_request": pick(s1, "hub->client", "shake"),
        "client_handshake": pick(s1, "client->hub", "shake"),
        "server_handshake_response": [f["plaintext"] for f in s1["frames"]
                                      if f["dir"] == "hub->client" and f["hive_msg_type"] == "shake"][1],
        "client_hello": pick(s1, "client->hub", "hello"),
        "client_utterance": pick(s1, "client->hub", "bus", "recognizer_loop:utterance"),
        "server_speak": pick(s1, "hub->client", "bus", "speak"),
        "server_utterance_handled": pick(s1, "hub->client", "bus", "ovos.utterance.handled"),
    }
    write("messages.json", {
        "_meta": meta(
            "Plaintext HiveMessage JSON exactly as the Python code serializes it (json.dumps, ensure_ascii=False, "
            "', ' and ': ' separators). Key order/whitespace are NOT significant to the hub (json.loads); Kotlin "
            "tests should compare parsed JSON. HELLO/HANDSHAKE are sent unencrypted; everything else encrypted. "
            "server_* are produced by the real hivemind-core 3.4.0 handler code (session 'recommended')."),
        "messages": msgs,
        "parsed": {k: json.loads(v) for k, v in msgs.items()}})
    return [s1, s2, s3]


def main():
    os.makedirs(OUT, exist_ok=True)
    auth_vectors()
    hs0 = handshake_vectors()
    sessions = message_vectors_and_sessions()
    encryption_vectors(bytes.fromhex(hs0["client_key_hex"]))
    write("full_session.json", {
        "_meta": meta(
            "Complete deterministic connection (handshake + HELLO + one utterance + replies) between the real hub "
            "code (hivemind-websocket-protocol open/on_message -> hivemind-core 3.4.0 HiveMindListenerProtocol -> "
            "ovos_bus_client.hpm.OVOSProtocol on a FakeBus with a fake ovos-core/skill) and a reference client. "
            "Each frame: wire = exact websocket text frame; plaintext = decrypted HiveMessage JSON. "
            "A Kotlin test can replay the hub->client frames into the client and check that, given the same IV and "
            "nonces, the client produces byte-identical client->hub wire frames IF it serializes the plaintext "
            "identically; otherwise compare decrypt(wire) as parsed JSON."),
        "sessions": sessions})
    write("errors.json", {"_meta": meta("Hub behaviour on bad input (in-process, real handler code)."),
                          "cases": bad_cases()})


if __name__ == "__main__":
    main()

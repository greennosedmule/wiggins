#!/usr/bin/env python3
"""Generate HiveMind protocol v3 (Noise) test vectors for Wiggins.

The hub side runs the code hivemind-core 4.15 and 5.x run for it:
hivemind_bus_client.noise (build_prologue, start_noise_handshake, NoiseTransport)
over poorman_handshake.noise and noiseprotocol. The client side is the Python
client's own initiator, with the payload Wiggins sends, so every byte Wiggins
produces can be compared. Versions: requirements-noise.txt, a `uv pip freeze`
of the venv below (hivemind-bus-client 1.2.11a1, poorman-handshake 2.0.3a1).

Output: app/src/test/resources/hivemind-vectors/noise.json (relative to repo root).

Rerun (from the repo root); output is byte-identical:

  uv venv --python 3.13 /tmp/hm-noise-venv
  VIRTUAL_ENV=/tmp/hm-noise-venv uv pip install -r tools/vectors/requirements-noise.txt
  /tmp/hm-noise-venv/bin/python -I tools/vectors/noise_vectors.py

Chunking: hivemind_bus_client.noise.CHUNK_SIZE is patched from 65000 to
TEST_CHUNK_SIZE, so the multi-frame cases stay small; Wiggins' NoiseTransport
takes the chunk size as a parameter for the same test.

Determinism: static keys are fixed key files, and noiseprotocol's ephemeral key
generation is patched to hand out fixed keys in order (client first, then hub).
argon2id is deterministic. The hub's HELLO and HANDSHAKE payloads are built the
way hivemind-core 5.3.3a1 and 4.15.0a1 build them (handle_new_client), with the
same enum types, and serialized with HiveMessage as the hub does.
"""
import hashlib
import json
import os
import sys
import tempfile
from enum import IntEnum

_TMP = tempfile.mkdtemp(prefix="hm-noise-vectors-")
for _k in ("XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME", "XDG_STATE_HOME"):
    os.environ[_k] = os.path.join(_TMP, _k.lower())
    os.makedirs(os.environ[_k], exist_ok=True)

from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey  # noqa: E402
from importlib.metadata import version  # noqa: E402
from noise.backends.default import diffie_hellmans  # noqa: E402

import hivemind_bus_client.noise as hm_noise  # noqa: E402
from hivemind_bus_client.encryption import SupportedCiphers, SupportedEncodings  # noqa: E402
from hivemind_bus_client.message import HiveMessage, HiveMessageType  # noqa: E402
from hivemind_bus_client.noise import (NOISE_PATTERNS, NOISE_PATTERN_KK, NOISE_SUITES,  # noqa: E402
                                       NoiseTransport, build_prologue, canonical_json,
                                       derive_psk, start_noise_handshake)

TEST_CHUNK_SIZE = 1000
hm_noise.CHUNK_SIZE = TEST_CHUNK_SIZE  # send_message reads it at call time

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(REPO, "app", "src", "test", "resources", "hivemind-vectors", "noise.json")


class ProtocolVersion(IntEnum):  # hivemind_core.protocol.ProtocolVersion
    ZERO = 0
    ONE = 1
    TWO = 2
    THREE = 3


def key(label: str) -> bytes:
    """A fixed X25519 private key."""
    return hashlib.sha256(b"wiggins-noise-" + label.encode()).digest()


EPHEMERALS = []
_real_generate = diffie_hellmans.ED25519.generate_keypair


def _fixed_generate(self):
    if not EPHEMERALS:
        raise RuntimeError("no fixed ephemeral key queued")
    return self.klass.from_private_bytes(EPHEMERALS.pop(0))


diffie_hellmans.ED25519.generate_keypair = _fixed_generate


def key_file(name: str, private: bytes) -> str:
    path = os.path.join(_TMP, f"{name}.key")
    with open(path, "w") as f:
        f.write(private.hex())
    return path


def public(private: bytes) -> str:
    return X25519PrivateKey.from_private_bytes(private).public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw).hex()


RSA_PUBKEY = ("-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwiggins"
              "\ntest\"key\\with\tescapes\n-----END PUBLIC KEY-----\n")


def hub_payloads(shape: str, node_id: str, peer: str):
    """The hub's HELLO and HANDSHAKE payloads, as handle_new_client builds them."""
    hello = {"pubkey": RSA_PUBKEY, "peer": peer, "node_id": node_id}
    encodings = list(SupportedEncodings)
    ciphers = [SupportedCiphers.AES_GCM, SupportedCiphers.CHACHA20_POLY1305]
    patterns = [p for p in NOISE_PATTERNS if p != NOISE_PATTERN_KK]  # no key pinned yet
    if shape == "5.x":
        shake = {
            "max_protocol_version": ProtocolVersion.THREE,
            "binarize": False,
            "encodings": encodings,
            "ciphers": ciphers,
            "noise": {"patterns": patterns, "suites": list(NOISE_SUITES)},
        }
    else:  # 4.15: both handshakes
        shake = {
            "handshake": True,
            "min_protocol_version": ProtocolVersion.ONE,
            "max_protocol_version": ProtocolVersion.THREE,
            "binarize": False,
            "preshared_key": False,
            "password": True,
            "crypto_required": True,
            "encodings": encodings,
            "ciphers": ciphers,
            "noise": {"patterns": patterns, "suites": list(NOISE_SUITES)},
        }
    return hello, shake


def wire(msg_type, payload) -> str:
    return HiveMessage(msg_type, payload=payload).serialize()


def bus(msg_type: str, data: dict, session_id: str) -> str:
    return json.dumps({"msg_type": "bus", "payload": {
        "type": msg_type, "data": data,
        "context": {"session": {"session_id": session_id, "lang": "en-US"}}}})


def case(name: str, shape: str, password: str, node_id: str, peer: str):
    client_static, hub_static = key(name + "-client-static"), key(name + "-hub-static")
    client_eph, hub_eph = key(name + "-client-ephemeral"), key(name + "-hub-ephemeral")
    hello, shake = hub_payloads(shape, node_id, peer)
    prologue = build_prologue(hello, shake, "Noise_XXpsk2_25519_ChaChaPoly_SHA256")
    psk = derive_psk(password, node_id=node_id)

    EPHEMERALS[:] = [client_eph, hub_eph]
    client = start_noise_handshake(True, "XXpsk2", "25519_ChaChaPoly_SHA256", None, node_id, prologue,
                                   key_path=key_file(name + "-client", client_static), psk=psk)
    hub = start_noise_handshake(False, "XXpsk2", "25519_ChaChaPoly_SHA256", None, node_id, prologue,
                                key_path=key_file(name + "-hub", hub_static), psk=psk)

    # What Wiggins puts in message 1 (the Python client lists every encoding).
    node_payload = canonical_json({"binarize": False, "encodings": ["JSON-B64"]})
    msg1 = client.write_message(node_payload)
    assert hub.read_message(msg1) == node_payload
    hub_payload = json.dumps({"encoding": "JSON-B64"}).encode()  # json.loads(...)["encodings"][0]
    msg2 = hub.write_message(hub_payload)
    assert client.read_message(msg2) == hub_payload
    msg3 = client.write_message(b"")
    hub.read_message(msg3)
    assert client.handshake_finished and hub.handshake_finished
    assert hub.remote_pubkey.hex() == public(client_static)

    c2s_key = client.noise.noise_protocol.cipher_state_encrypt.k
    s2c_key = client.noise.noise_protocol.cipher_state_decrypt.k
    client_t, hub_t = NoiseTransport(client), NoiseTransport(hub)

    session_id = "8e8d8a9e-0000-4000-8000-000000000001"
    # Client to hub: Wiggins' HELLO (one frame) and a transcription too big for one Noise message.
    c2s = []
    client_hello = json.dumps({"msg_type": "hello", "payload": {"session": {"session_id": session_id}, "site_id": "phone"}})
    big_audio = bus("recognizer_loop:b64_transcribe", {"audio": "A" * 1_500, "lang": "en-US"}, session_id)
    for text in (client_hello, big_audio):
        frames = []
        client_t.send_message(text, frames.append)
        received = [hub_t.decrypt_frame(f) for f in frames]
        assert received[-1] == text and all(r is None for r in received[:-1])
        c2s.append({"plaintext": text, "frames": [f.hex() for f in frames]})

    # Hub to client: a speak, and hub speech audio chunked as 5.x sends it.
    s2c = []
    speak = bus("speak", {"utterance": "It's 6:30 — café time.", "expect_response": False}, session_id)
    tts = bus("speak:b64_audio.response", {"utterance": "Hello.", "wiggins_id": "s1", "audio": "UklGR" + "B" * 2_500}, session_id)
    for text in (speak, tts):
        frames = []
        hub_t.send_message(text, frames.append)
        received = [client_t.decrypt_frame(f) for f in frames]
        assert received[-1] == text
        s2c.append({"plaintext": text, "frames": [f.hex() for f in frames]})

    return {
        "name": name,
        "hub_shape": shape,
        "password": password,
        "node_id": node_id,
        "client_static_hex": client_static.hex(),
        "client_ephemeral_hex": client_eph.hex(),
        "client_static_public_hex": public(client_static),
        "hub_static_public_hex": public(hub_static),
        "psk_hex": psk.hex(),
        "hello_wire": wire(HiveMessageType.HELLO, hello),
        "shake_wire": wire(HiveMessageType.HANDSHAKE, shake),
        "prologue_hex": prologue.hex(),
        "node_payload": node_payload.decode(),
        "msg1_hex": msg1.hex(),
        "msg2_hex": msg2.hex(),
        "hub_payload": hub_payload.decode(),
        "msg3_hex": msg3.hex(),
        "c2s_key_hex": c2s_key.hex(),
        "s2c_key_hex": s2c_key.hex(),
        "client_to_hub": c2s,
        "hub_to_client": s2c,
    }


def wrong_password_case():
    """The hub derives its PSK from a different password: message 2 must not authenticate."""
    name = "wrong-password"
    hello, shake = hub_payloads("5.x", "hub-node", "Wiggins::1")
    prologue = build_prologue(hello, shake, "Noise_XXpsk2_25519_ChaChaPoly_SHA256")
    EPHEMERALS[:] = [key(name + "-client-ephemeral"), key(name + "-hub-ephemeral")]
    client = start_noise_handshake(True, "XXpsk2", "25519_ChaChaPoly_SHA256", None, "hub-node", prologue,
                                   key_path=key_file(name + "-client", key(name + "-client-static")),
                                   psk=derive_psk("right horse battery staple", node_id="hub-node"))
    hub = start_noise_handshake(False, "XXpsk2", "25519_ChaChaPoly_SHA256", None, "hub-node", prologue,
                                key_path=key_file(name + "-hub", key(name + "-hub-static")),
                                psk=derive_psk("wrong horse battery staple", node_id="hub-node"))
    msg1 = client.write_message(canonical_json({"binarize": False, "encodings": ["JSON-B64"]}))
    hub.read_message(msg1)  # message 1 has no PSK yet, so the hub can't tell
    msg2 = hub.write_message(b'{"encoding": "JSON-B64"}')
    try:
        client.read_message(msg2)
        raise AssertionError("message 2 authenticated with the wrong PSK")
    except AssertionError:
        raise
    except Exception:
        pass
    return {
        "password": "right horse battery staple",
        "node_id": "hub-node",
        "client_static_hex": key(name + "-client-static").hex(),
        "client_ephemeral_hex": key(name + "-client-ephemeral").hex(),
        "hello_wire": wire(HiveMessageType.HELLO, hello),
        "shake_wire": wire(HiveMessageType.HANDSHAKE, shake),
        "msg2_hex": msg2.hex(),
    }


def main():
    cases = [
        case("hub-5x", "5.x", "correct horse battery staple", "HiveMind-5f1c", "Wiggins::0a1b2c"),
        case("hub-415", "4.15", "pässwörd ✓", "nödé-ünicode ✓", "Wiggins::d3e4f5"),
    ]
    out = {
        "_meta": {
            "description": "HiveMind protocol v3: Noise_XXpsk2_25519_ChaChaPoly_SHA256 with the PSK from argon2id, "
                           "prologue from the hub's HELLO and HANDSHAKE payloads, and the transport framing of "
                           "hivemind_bus_client.noise. See docs/hivemind-protocol.md §16.",
            "generator": "tools/vectors/noise_vectors.py",
            "versions": {p: version(p) for p in ("hivemind-bus-client", "poorman-handshake", "noiseprotocol",
                                                 "argon2-cffi", "cryptography")},
            "python": sys.version.split()[0],
        },
        "chunk_size": TEST_CHUNK_SIZE,
        "cases": cases,
        "wrong_password": wrong_password_case(),
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()

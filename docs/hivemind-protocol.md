# HiveMind wire protocol (as spoken by the Wiggins target hub)

This document describes, at byte level where it matters, what a HiveMind client
must do to talk to the hub Wiggins targets. It was written by reading the
source installed in the hub image and confirmed by running that code (see the
test vectors section). You should be able to implement the Kotlin client from
this document alone.

## 0. Target and versions

Hub: `docker.io/smartgic/hivemind-listener:stable-20260922` (index digest
`sha256:efbfedf866ef19f08e028837801274384f69d94a219dae4a5e8feb74cdc5fa5a`,
amd64 manifest `sha256:fe84b561…6c30e`), which ships hivemind-core 3.4.0.
The examples assume a hub reachable at `ws://hub.example.com:5678` with a
minimal server.json that leaves the protocol options at their defaults, for
example:

```json
{
  "agent_protocol": {"module": "hivemind-ovos-agent-plugin",
                     "hivemind-ovos-agent-plugin": {"host": "ovos-messagebus", "port": 8181}},
  "network_protocol": {"hivemind-websocket-plugin": {"host": "0.0.0.0", "port": 5678, "ssl": false}},
  "binary_protocol": {"module": null},
  "database": {"module": "hivemind-redis-db-plugin",
               "hivemind-redis-db-plugin": {"name": "clients", "host": "redis", "port": 6379}},
  "presence": {"enabled": false}
}
```

| Package (pip name) | Version | Role |
|---|---|---|
| hivemind-core | 3.4.0 | listener protocol, handshake, routing |
| hivemind-websocket-protocol | 0.0.3 | the websocket server (`hivemind-websocket-plugin`) |
| hivemind_bus_client | 0.4.4 | HiveMessage, encryption, the Python client |
| hivemind-plugin-manager | 0.5.0 | plugin base classes, client DB model |
| hivemind-redis-database | 0.0.3 | client DB in the example config |
| hivemind-http-protocol | 0.0.1 | installed, not enabled in the example config |
| hivemind-audio-binary-protocol | 2.1.3 | installed, not enabled (`binary_protocol.module: null`) |
| poorman-handshake | 1.0.1 | PasswordHandShake / RSA HandShake |
| ovos_bus_client | 1.3.7 | OVOS `Message`/`Session`, and the agent plugin `hivemind-ovos-agent-plugin` (`ovos_bus_client/hpm.py`) |
| ovos-utils | 0.8.5 | |
| ovos-config | 1.2.2 | hub-side defaults for missing Session fields |
| pycryptodomex | 3.23.0 | AES-GCM, ChaCha20-Poly1305, RSA |
| cryptography | 50.0.1 | installed, not used by the protocol path |
| z85base91 | 0.0.5 | B91 / Z85B / Z85P encodings |
| pybase64 | 1.5.0 | base64 |
| tornado | 6.5.10 | websocket server |
| websocket-client | 1.9.2 | used by the Python client |

There is no separate `hivemind-ovos-agent-plugin` distribution. The plugin of
that name is the entry point `hivemind-ovos-agent-plugin =
ovos_bus_client.hpm:OVOSProtocol` inside ovos_bus_client 1.3.7.
`tools/vectors/requirements.txt` is the image's full `pip freeze`.

Citations below use `package/file.py:Function (line)` against these versions.
To read the source, copy it out of the image with
`podman create` + `podman cp <ctr>:/home/hivemind/.venv/lib/python3.13/site-packages`.

## 1. Recommended client configuration

| Item | Choice |
|---|---|
| Transport | One websocket, **text frames only**. `ws://` on the LAN. `wss://` only through a TLS-terminating proxy, because the hub has `ssl: false`. |
| URL | `ws(s)://<host>:<port>/?authorization=<base64std("useragent:access_key")>`, with the base64 sent raw (no percent-encoding) and as the only query parameter. |
| Handshake | **Password handshake** (PBKDF2-HMAC-SHA256, 100 000 iterations, 32-byte key). Do not send `pubkey` in HANDSHAKE. |
| Cipher | **`CHACHA20-POLY1305`**: 32-byte key, 12-byte random nonce, 16-byte tag (RFC 8439, no AAD). `AES-GCM` with a **16-byte** nonce is the fallback. |
| Encoding | **`JSON-B64`**: standard base64 alphabet with `=` padding. `JSON-HEX` is the fallback. Never offer `JSON-Z85P`, because it is broken (§9.4). |
| Binarize | **Off**. Send `"binarize": false`. The hub's server.json does not enable it, so the hub reports `false` anyway. |
| What to offer | `{"binarize": false, "ciphers": ["CHACHA20-POLY1305"], "encodings": ["JSON-B64"], "envelope": "<48 hex>"}`. Offer only what you implement; the hub picks the first entry of your list that it allows. |
| Envelopes you send | Only `msg_type` and `payload` are needed. Never add keys the hub doesn't know (§3.3). |

This configuration was run end to end against a throwaway hub built from the
same image (`live_capture.json` → `reference_client`). It was also run
in-process against the real hub code (`full_session.json`, session 0), and the
derived key, ChaCha20 and AES-GCM-16 outputs were cross-checked against JDK 21
SunJCE.

## 2. Transport and authentication

### 2.1 Server side

`hivemind_websocket_protocol/__init__.py:HiveMindWebsocketProtocol.run (45-76)`
creates a tornado `web.Application([("/", HiveMindTornadoWebSocket)])` with no
settings.

- The only route is the path **`/`**. Any other path is a plain HTTP 404, so a
  reverse proxy that serves the hub under a path prefix must rewrite it to `/`.
- `ssl` comes from config. In the example config it is `false`, so the hub serves plain `ws`.
  With `ssl: true` the hub generates a self-signed RSA-2048/SHA-1 certificate
  (`create_self_signed_cert`).
- No websocket subprotocol is used. `check_origin` returns `True`, so any
  `Origin` is accepted. No HTTP headers are read, so an `Authorization: Bearer …`
  header meant for an auth proxy is ignored by the hub and does no harm.
- tornado defaults apply: no server pings (`websocket_ping_interval` unset,
  `tornado/websocket.py:ping_interval (283-295)`), no idle timeout, and a
  maximum message size of 10 MiB.

### 2.2 Credentials in the URL

`HiveMindTornadoWebSocket.open (168-231)` does:

```python
auth = self.request.uri.split("/?authorization=")[-1]      # raw request-target, NOT url-decoded
useragent, key = pybase64.b64decode(auth).decode("utf-8").split(":")   # decode_auth (135-149)
```

So the client must send:

```
GET /?authorization=<B> HTTP/1.1
B = base64_standard_with_padding( UTF8( useragent + ":" + access_key ) )
```

- **Do not percent-encode `B`.** `+`, `/` and `=` must go on the wire as-is.
  `pybase64.b64decode` (non-validating) silently discards `%` and keeps the hex
  digits, which corrupts the value. This was confirmed live: a percent-encoded
  value with `+ / =` made the hub abort the connection with a `UnicodeDecodeError`
  (`live_capture.json` → `errors[4]`), while the raw value worked
  (`errors[3]`). In OkHttp, build the URL string yourself or use
  `HttpUrl.Builder.addEncodedQueryParameter`. `addQueryParameter` encodes
  `+` and `=`.
- `authorization` must be the **last and only** query parameter, because
  everything after `/?authorization=` is base64-decoded.
- `useragent` and `access_key` must not contain `:`, or `split(":")` raises.
  Non-ASCII useragents are fine (UTF-8).
- The access key is what `hivemind-core add-client` prints as "Access Key"
  (32 hex chars). The "Password" is used for the handshake (§5). The
  "Encryption Key" is the legacy pre-shared key (§7).
- The Python client builds `f'{scheme}://{host}:{port}?authorization={b64}'`
  (`hivemind_bus_client/client.py:build_url (242-247)`), which websocket-client
  sends as request-target `/?authorization=…`. Vectors are in `auth.json`.
- The useragent becomes part of the client's **peer id** (§11.2).

### 2.3 What happens on connect

After looking up the access key (`db.get_client_by_api_key`), the handler copies
the client record: `crypto_key` (legacy key), `password`, `allowed_types`,
blacklists and `is_admin`. If `password` is set it creates
`PasswordHandShake(password)`. Then it calls
`HiveMindListenerProtocol.handle_new_client`
(`hivemind_core/protocol.py:218-288`), which immediately sends **HELLO** and then
**HANDSHAKE** (§4). The client speaks only after receiving these.

## 3. Framing: the HiveMessage envelope

### 3.1 Frames

With binarize off, every frame in both directions is a **websocket text frame**
holding UTF-8 JSON. It is one of:

- a plaintext **HiveMessage** object (§3.2), used only for HELLO/HANDSHAKE
  before the key exists, or
- an **encryption wrapper** `{"ciphertext": …, "tag": …, "nonce": …}` (§9) whose
  plaintext is a HiveMessage JSON string.

Each side tells them apart with a plain substring test:

- Hub: `hivemind_core/protocol.py:HiveMindClientConnection.decode (147-167)`:
  `if crypto_key: if "ciphertext" in payload: decrypt …`.
- Python client: `client.py:on_message (269-307)`: the same test.

### 3.2 HiveMessage JSON

The Python side serializes with `hivemind_bus_client/message.py:HiveMessage.as_dict (171-191)`:

```json
{"msg_type": "bus",
 "payload": { ... },
 "metadata": {},
 "route": [],
 "node": null,
 "target_site_id": null,
 "target_pubkey": null,
 "source_peer": null}
```

`msg_type` values (`message.py:HiveMessageType (9-29)`):

| value | meaning | relevant to Wiggins |
|---|---|---|
| `shake` | HANDSHAKE | yes |
| `hello` | HELLO | yes |
| `bus` | BUS: payload is an OVOS Message | yes |
| `shared_bus` | passive bus mirroring (client→hub) | no |
| `broadcast`, `propagate`, `escalate` | routing; payload is a nested HiveMessage | no (clients that aren't admins can't broadcast) |
| `intercom` | RSA-encrypted peer-to-peer | no |
| `query`, `cascade`, `ping`, `rendezvous`, `3rdparty` | reserved or user-defined; the hub ignores them (`handle_unknown_message` is a no-op) | no |
| `bin` | binary payload, only with binarize | no |

Note that `ping` is not a keepalive. The hub ignores it. Use websocket ping
frames (§13).

For a **BUS** message, `payload` is an OVOS Message as a JSON object, not a
string (`message.py:HiveMessage.__init__ (75-78)`):

```json
{"type": "recognizer_loop:utterance", "data": {...}, "context": {...}}
```

### 3.3 How the hub parses a frame (important)

`decode()` ends with `return HiveMessage(**json.loads(payload))`. The JSON keys
are passed **as Python keyword arguments**. Therefore:

- Allowed keys: `msg_type`, `payload`, `node`, `source_peer`, `route`,
  `target_peers`, `target_site_id`, `target_pubkey`, `bin_type`, `metadata`.
  **Any other key raises `TypeError`, tornado aborts the socket, and the client
  sees code 1006.**
- `msg_type` must be one of the values above, or the hub raises `ValueError`.
- `route`, if present, must be a list of objects. Send `[]` or omit it.
- Only `msg_type` and `payload` are needed. The live reference client sent
  `{"msg_type": …, "payload": …}` only, and it worked.
- Key order and whitespace don't matter. The hub `json.loads` the frame, and
  Kotlin's compact JSON is fine.

The hub overwrites `source_peer` and appends route hops on receipt
(`handle_message (375-412)`: `update_source_peer`, `update_hop_data`), so the
client doesn't need to set them.

## 4. Connection sequence

The example values come from `full_session.json` session 0 (hub client record:
access key `4f0c…1100`, password `5d41402abc4b2a76b9719d911017c592`, plus a
legacy crypto_key, because `add-client` always creates one).

```
client                                            hub
  |---- HTTP upgrade  GET /?authorization=B ------->|  open(): look up key
  |<--- text: HELLO (plaintext) --------------------|  handle_new_client
  |<--- text: HANDSHAKE request (plaintext) --------|
  |---- text: HANDSHAKE {envelope,…} (plaintext) -->|  handle_handshake_message: derive key
  |<--- text: HANDSHAKE {envelope,cipher,encoding} -|  (plaintext; key is now active on hub)
  |  client verifies envelope, derives same key     |
  |==== everything below is encrypted ==============|
  |---- HELLO {session, site_id} ------------------>|  handle_hello_message: registers peer
  |---- BUS recognizer_loop:utterance ------------->|  authorize → inject into OVOS bus
  |<--- BUS <intent msg>, speak, ovos.utterance.handled, …   (anything OVOS routes to this peer)
```

### 4.1 Hub → client: HELLO (plaintext)

From `handle_new_client (252-262)`:

```json
{"msg_type": "hello",
 "payload": {"pubkey": "-----BEGIN PUBLIC KEY-----\n…\n-----END PUBLIC KEY-----",
             "peer": "Wiggins::1::wiggins-pixel::default",
             "node_id": "master:0.0.0.0"},
 "metadata": {}, "route": [], "node": null, "target_site_id": null, "target_pubkey": null, "source_peer": null}
```

`pubkey` is the hub's RSA identity key (PEM). It is only needed for the RSA
handshake (§6). `peer` still ends in `::default` because the client hasn't sent
its session yet. Wiggins can log this frame and ignore it.

### 4.2 Hub → client: HANDSHAKE request (plaintext)

From `handle_new_client (245-286)`, with the example server.json, which leaves
`binarize`, `allowed_ciphers` and `allowed_encodings` at the defaults in
`hivemind_core/config.py:_DEFAULT (7-44)`:

```json
{"msg_type": "shake",
 "payload": {"handshake": false,
             "min_protocol_version": 0,
             "max_protocol_version": 1,
             "binarize": false,
             "preshared_key": true,
             "password": true,
             "crypto_required": true,
             "encodings": ["JSON-B64", "JSON-URLSAFE-B64", "JSON-B91", "JSON-Z85B", "JSON-Z85P", "JSON-B32", "JSON-HEX"],
             "ciphers": ["CHACHA20-POLY1305", "AES-GCM"]},
 "metadata": {}, "route": [], "node": null, "target_site_id": null, "target_pubkey": null, "source_peer": null}
```

The fields:

- `handshake` = `not client.crypto_key and handshake_enabled`. It is
  **`false` for every client made with `hivemind-core add-client`**, because that
  command always stores a random 16-hex-char `crypto_key`
  (`hivemind_core/scripts.py:add_client (77-115)`). The hub doesn't force a
  handshake on those clients. The Python client ignores this flag and
  handshakes whenever `password` is true (`hivemind_bus_client/protocol.py:handle_handshake (181-214)`),
  and Wiggins should do the same.
- `password: true` means the client record has a password, so the password
  handshake is available.
- `preshared_key: true` means the legacy path (§7) would also work.
- The protocol version fields are informative only. The client sends no
  version.
- `encodings` and `ciphers` are the hub's allowed sets, in the hub's order. The
  **client's** order decides the choice (§8).

### 4.3 Client → hub: HANDSHAKE (plaintext)

```json
{"msg_type": "shake",
 "payload": {"binarize": false,
             "encodings": ["JSON-B64"],
             "ciphers": ["CHACHA20-POLY1305"],
             "envelope": "0001020304050607619f12ff38280e41a8631f0a71429581"}}
```

Send it **unencrypted**. The hub accepts plaintext even when it holds a legacy
key: it only logs "Message was unencrypted". **Do not include `pubkey`**: if
`pubkey` is present the hub takes the RSA path and ignores `envelope`
(`handle_handshake_message (462)`).

### 4.4 Hub → client: HANDSHAKE response (plaintext)

From `handle_handshake_message (475-528)`:

```json
{"msg_type": "shake",
 "payload": {"envelope": "f0e1d2c3b4a59687fa02bfcbf24b32e3d4a15ea9a22684bc",
             "encoding": "JSON-B64",
             "cipher": "CHACHA20-POLY1305"},
 "metadata": {}, "route": [], "node": null, "target_site_id": null, "target_pubkey": null, "source_peer": null}
```

HANDSHAKE and HELLO are always sent in plaintext by the hub
(`HiveMindClientConnection.send (122-125)` skips encryption for them). Once the
hub has sent this, it uses the new key for everything it sends and receives.
The client must then:

1. verify `envelope` against its password (§5.3), and abort if it doesn't match;
2. derive the key (§5.2);
3. adopt `cipher` and `encoding` from this message, not from its own offer;
4. send HELLO, encrypted.

### 4.5 Client → hub: HELLO (encrypted)

```json
{"msg_type": "hello",
 "payload": {"session": { …see §11… }, "site_id": "phone"}}
```

`handle_hello_message (530-548)` does the following:

- `client.sess = Session.deserialize(payload["session"])`;
- sets `site_id`;
- stores `pubkey` if present (the hub only logs "client did NOT send public
  key" without it, which is harmless);
- if `session_id != "default"`, registers the connection in `hm.clients` under
  its peer id. **Until it is registered, no OVOS replies can be routed to it**
  (§12).

The hub sends nothing in reply to HELLO.

### 4.6 Then: utterances (§11) and downlink (§12)

## 5. Password handshake (byte level)

Source: `poorman_handshake/symmetric/__init__.py:PasswordHandShake (7-36)` and
`poorman_handshake/symmetric/utils.py (42-91)`.

### 5.1 Envelope ("hsub")

```
iv       = 8 random bytes                                    (generate_iv)
digest   = SHA-256( iv || UTF8(password) )                   (32 bytes)
envelope = lowercase_hex( iv || digest )[0:48]               (create_hsub, hsublen=48)
         = 16 hex chars of iv  +  first 32 hex chars (16 bytes) of digest
```

Each side generates its own `iv` and sends its own envelope. The hub
generates a fresh `iv` for every handshake.

### 5.2 Key derivation

```
client_iv = hex_decode(client_envelope[0:16])
server_iv = hex_decode(server_envelope[0:16])
salt      = client_iv XOR server_iv                         (8 bytes, byte-wise)
key       = PBKDF2-HMAC-SHA256( P = UTF8(password), S = salt, c = 100000, dkLen = 32 )
```

(`receive_handshake` computes the salt from its own IV and the peer's envelope.
`secret` calls `hashlib.pbkdf2_hmac('sha256', …, 100000)` with the default
dkLen of 32.)

The password is used as the exact string that `add-client` printed. Its UTF-8
bytes are the PBKDF2 password. With JCA, `PBKDF2WithHmacSHA256` with
`PBEKeySpec(password.toCharArray(), salt, 100000, 256)` gives the same result
(checked on JDK 21). Doing PBKDF2 by hand with `Mac("HmacSHA256")` over the UTF-8
bytes avoids any provider differences in char→byte conversion. It takes 100k
HMAC iterations, so run it **off the main thread** (expect about 50–300 ms on a
phone).

### 5.3 Verification

`match_hsub(hsub, password)` (`utils.py:61-78`):

- requires `48 <= len(hsub) <= 80`;
- recomputes `create_hsub(password, iv=hex_decode(hsub[0:16]), hsublen=len(hsub))`;
- compares strings. The comparison is case-sensitive, so the hub's lowercase hex
  must be compared as-is.

- The **client** should verify the hub's envelope. The Python client calls
  `receive_and_verify`. On mismatch it never sets the salt, and deriving the key
  then crashes.
- The **hub never verifies the client's envelope**. It calls
  `receive_handshake`, and the `receive_and_verify` call is commented out
  (`protocol.py:500-508`). A wrong password is only detected when the first
  encrypted frame fails to decrypt. The hub then raises `DecryptionKeyError` in
  `on_message`, and tornado **aborts the TCP connection** without a close frame,
  so the client sees 1006. This is confirmed in `live_capture.json` →
  `errors[1]`. Note that with a wrong password the hub still answers the
  HANDSHAKE, and the client's verification fails first, so Wiggins can report
  "wrong password" before sending anything encrypted.

### 5.4 Security note

The envelope is `iv || truncated SHA-256(iv||password)` in the clear, so a
passive observer can run an offline dictionary attack on the password. This is
harmless with `add-client`'s random 128-bit hex passwords. It is a reason not
to let users choose short passwords, and to keep `wss` through the proxy off the
LAN. There is no server authentication beyond knowledge of the password.

## 6. RSA ("pubkey") handshake: alternative, not recommended

When the client HANDSHAKE has `"pubkey": <PEM RSA public key>`
(`handle_handshake_message (462-465)`), the hub does the following, using
`poorman_handshake/asymmetric/__init__.py:HandShake.generate_handshake (94-108)`:

```
secret    = 32 random bytes                           → hub's crypto_key
ct        = RSA-OAEP(client_pub, secret)               (PKCS1_OAEP default: SHA-1, MGF1-SHA-1, empty label)
sig       = RSA-PSS-sign(hub_priv, ct)                 (SHA-256, MGF1-SHA-256, salt 32 bytes)
envelope  = lowercase_hex( sig || ct )                 (2048-bit keys: 256 + 256 bytes = 1024 hex chars)
```

The response is `{"envelope", "encoding", "cipher"}`, but **no negotiation
happens on this path**. `cipher` and `encoding` stay at the connection defaults
`AES-GCM` / `JSON-HEX` (`HiveMindClientConnection (91-92)`).

To use it, the client verifies `sig` against the HELLO `pubkey`, then
OAEP-decrypts `ct` and uses `secret` directly. The stock Python client is
broken here: `HandShake.receive_handshake` XORs the decrypted secret into
`self.secret`, which is `None` on the client, and raises `TypeError` (verified).
`HalfHandShake` semantics, where secret = decrypted, are what work. The path
doesn't use the password at all. Only the access key authenticates the client.
Wiggins should not use it.

## 7. Legacy pre-shared key (protocol "V0"), not recommended

Every `add-client` record also has `crypto_key` = 16 random hex characters
("Encryption Key", marked deprecated). The hub starts each connection with that
key active (`open()` sets `client.crypto_key = user.crypto_key`) and with
`cipher=AES-GCM` and `encoding=JSON-HEX`. A client could skip the handshake and
encrypt with:

- the key = the **16 ASCII characters as UTF-8 bytes** (not hex-decoded), giving
  AES-128-GCM;
- a 16-byte nonce and JSON-HEX.

The last case in `encryption.json` (`"LEGACY …"`) is a vector for this.
ChaCha20 cannot be used with this key, because it requires 32 bytes. Once a
password handshake completes, the derived key replaces the legacy key for that
connection. Don't build on this path: it's deprecated, and the key is
long-lived.

## 8. Cipher and encoding negotiation

From `handle_handshake_message (476-498)`:

```python
encodings = [norm(e) for e in client.encodings or ["JSON-HEX"]]   # raises InvalidEncoding on unknown names
ciphers   = [norm(c) for c in client.ciphers   or ["AES-GCM"]]    # raises InvalidCipher  on unknown names
encodings = [e for e in encodings if e in server_allowed_encodings]
ciphers   = [c for c in ciphers   if c in server_allowed_ciphers]
if not ciphers or not encodings: client.disconnect(); return      # close frame, no status (1005)
client.cipher, client.encoding = ciphers[0], encodings[0]         # CLIENT's first allowed choice
client.binarize = payload.get("binarize", False)
```

- Names are case-sensitive and must be exact: `AES-GCM`, `CHACHA20-POLY1305`,
  `JSON-B64`, `JSON-URLSAFE-B64`, `JSON-B32`, `JSON-HEX`, `JSON-B91`,
  `JSON-Z85B`, `JSON-Z85P`.
- An **unknown** name, such as a typo, raises inside `on_message`, and tornado
  aborts the TCP connection (1006; `errors.json` case 3).
- If `encodings` or `ciphers` is omitted, the hub uses `JSON-HEX` and `AES-GCM`.
- With the example config the hub's allowed sets are the defaults: ciphers
  `["CHACHA20-POLY1305", "AES-GCM"]` and all seven encodings. They can be
  restricted with `allowed_ciphers` and `allowed_encodings` in server.json.
- The stock Python client offers `list(SupportedEncodings)` =
  `[B91, Z85B, Z85P, B64, URLSAFE-B64, B32, HEX]` and `[AES-GCM, CHACHA20]`
  (AES first if the CPU has AES-NI), so it ends up with **AES-GCM + JSON-B91**
  (seen in the live capture). That doesn't matter to Wiggins: only the
  negotiated pair is ever used on a given connection.

## 9. Payload encryption (byte level)

Source: `hivemind_bus_client/encryption.py`.

### 9.1 Which frames are encrypted

Once a key exists, **every** frame in both directions is encrypted except
`hello` and `shake` sent by the hub (`HiveMindClientConnection.send (122-143)`).
The Python client encrypts everything once it has a key, including its HELLO
(`client.py:emit (336-411)`). Wiggins should:

- send HANDSHAKE in plaintext and everything after it encrypted;
- accept plaintext `hello`/`shake` frames at any time.

The hub also accepts unencrypted BUS frames after the handshake, with only a
log warning (confirmed live). Don't rely on that.

### 9.2 Encrypt

From `encrypt_as_json (182-243)`, `encrypt_bin (295-331)`, `encrypt_AES (379-403)`
and `encrypt_ChaCha20_Poly1305 (437-463)`:

```
P  = UTF8( HiveMessage JSON string )
CHACHA20-POLY1305:  nonce = 12 random bytes;  (C, T) = ChaCha20-Poly1305_seal(key32, nonce, P, aad = empty)
AES-GCM:            nonce = 16 random bytes;  (C, T) = AES-GCM_seal(key, nonce, P, aad = empty), T = 16 bytes
                    key length 16/24/32 → AES-128/192/256 (password path: 32 → AES-256)
wire = JSON text  {"ciphertext": E(C), "tag": E(T), "nonce": E(nonce)}
```

Here `E` is the negotiated encoding, producing an ASCII string. The Python wire
is exactly `json.dumps({...})`, with key order ciphertext, tag, nonce and the
`", "` / `": "` separators. The hub doesn't care about key order or whitespace.

| encoding | E(bytes) | decoder on hub |
|---|---|---|
| `JSON-B64` | RFC 4648 standard alphabet, `=` padded (`pybase64.b64encode`) | `pybase64.b64decode`, non-validating |
| `JSON-URLSAFE-B64` | URL-safe alphabet, padded | `urlsafe_b64decode` |
| `JSON-B32` | RFC 4648 base32, padded | `base64.b32decode` |
| `JSON-HEX` | lowercase hex (`binascii.hexlify`) | `unhexlify`, which accepts upper case |
| `JSON-B91`, `JSON-Z85B` | z85base91 custom codecs | works, but don't implement |
| `JSON-Z85P` | z85base91 | **broken** (§9.4) |

Notes for JCA:

- **ChaCha20-Poly1305:** `Cipher.getInstance("ChaCha20-Poly1305")` on the JDK,
  or `"ChaCha20/Poly1305/NoPadding"` on Android/Conscrypt (API 28+), with
  `IvParameterSpec(nonce12)`. JCA outputs `C || T`. Split off the last 16 bytes
  as the tag.
- **AES-GCM:** `"AES/GCM/NoPadding"` with `GCMParameterSpec(128, nonce16)`.
  JCA outputs `C || T`.
- **AES-GCM nonces must be 16 bytes.** The hub decrypts by concatenating
  `nonce || C || T` and taking the first 16 bytes as the nonce
  (`decrypt_from_json (284-287)` → `decrypt_bin (353-356)`). A standard 12-byte
  GCM nonce therefore fails authentication (`encryption.json` →
  `decrypt_cases[2]`). SunJCE accepts 16-byte GCM IVs (verified). Android
  Conscrypt/BoringSSL is believed to as well, **but this has not been tested on a
  device**. This is the main reason ChaCha20 is the recommendation.
- Use a fresh random nonce for every frame (`SecureRandom`). The library never
  reuses or counts nonces, and the key changes on every connection.

### 9.3 Decrypt

From `decrypt_from_json (246-292)`:

```
C = D(ciphertext); N = D(nonce)
T = D(tag)            if "tag" present
  else C, T = C[:-16], C[-16:]          ("web crypto compatibility": tag appended to ciphertext)
P = open(key, N, C, T); plaintext = UTF8-decode(P) → HiveMessage JSON
```

An authentication failure on the hub ends the connection (§13). On the client,
drop the connection and redo the handshake. A tag failure means the wrong key
or tampering.

### 9.4 JSON-Z85P is broken in this version

In z85base91 0.0.5, `Z85P.decode(Z85P.encode(x))` raises
`ValueError: Invalid Z85 character`, so the hub cannot decrypt its own
JSON-Z85P output. It is still in the hub's default allowed list. Never offer it
(`encryption.json` → `broken_encodings`).

### 9.5 Binary frames

`encrypt_bin` output is `nonce || C || T` and is sent as a websocket **binary**
frame. It is used only when `binarize` is on, or for `bin` messages. With
binarize off the hub never sends binary frames to a JSON client. If one arrives,
log it and ignore it.

## 10. Binarization (do not enable)

`hivemind_bus_client/serialization.py:get_bitstring` defines a bit-packed format:

- a leading 1 bit;
- a "versioned" bit;
- a 5-bit type;
- a "compressed" bit (zlib);
- an 8-bit metadata length, then the metadata;
- for `bin`, a 4-bit binary type;
- the payload, left-padded to whole bytes.

It is encrypted with `encrypt_bin` and sent as a binary frame. The hub only uses
it for a client that sent `"binarize": true`
(`HiveMindClientConnection.send (126-133)`). Server config `binarize` (default
`false`, and the example config doesn't set it) is only advertised: the hub doesn't enforce
it against what the client asks for. Send `"binarize": false` and ignore this
format.

## 11. Session, HELLO and utterances

### 11.1 The Session object

An OVOS Session is a JSON object (`ovos_bus_client/session.py:Session.serialize (414-…)`).
The hub rebuilds it with `Session.deserialize`, and **every missing field is
filled from the hub container's own OVOS config**, not from ovos-core's. A hub
container that mounts no mycroft.conf uses the library defaults:

- `lang` `en-us`
- `location` = Lawrence, KS / `America/Chicago`
- `time_format` `half`, `date_format` `MDY`, `system_unit` `metric`
- `pipeline` = `["stop_high","converse","ocp_high","padatious_high","adapt_high","ocp_medium","fallback_high","stop_medium","adapt_medium","adapt_low","common_qa","fallback_medium","fallback_low"]`
- `blacklisted_skills` = `["skill-ovos-stop.openvoiceos"]`

Some of these are falsy defaults: an empty list or string in the client's
session still gets replaced. The hub then **overwrites `context.session` on
every injected message** with its stored copy (`_update_blacklist (770-793)`).
That stored copy is simply the session the client sent last, plus the
client's blacklists (`handle_bus_message (550-573)` replaces it wholesale).

**Hand the session back.** OVOS keeps per-session state, such as a skill
waiting in `get_response` (`utterance_states`) and `active_skills`, only
inside `context.session` (ovos-core `converse_service.py:217,284-290,364-370`).
Neither the hub nor ovos-core keeps a copy for a non-`default` session. So a
client must store the latest `context.session` that comes down for its
`session_id` and resend it with the next message, overriding only its own
fields (lang, location, units, formats, `site_id`). The Python client does
this (`hivemind_bus_client/protocol.py:226`). A client that builds a fresh
session each time breaks follow-up answers: the answer is matched as a new
intent while the skill times out (`docs/alarm-followup-investigation.md`).
Wiggins does this in `HiveProtocol.sessionJson`.

So Wiggins should send, in HELLO and in each BUS context:

```json
{"session_id": "<uuid4, new per connection, never \"default\">",
 "lang": "en-US",
 "site_id": "phone",
 "location": {"timezone": {"code": "Europe/London", "name": "Greenwich Mean Time",
                           "offset": 0, "dstOffset": 3600000}},
 "system_unit": "metric",
 "time_format": "full",
 "date_format": "DMY"}
```

`location` follows OVOS's `mycroft.conf` `location` schema. `city` and
`coordinate` can be added if wanted. `offset` and `dstOffset` are in
milliseconds. Optionally also send `pipeline` if the operator's ovos-core uses a
non-default intent pipeline. Otherwise the hub's default list above is what
ovos-core will use for this session. Whether that list matches the ovos-core
release in use is **not verified** here.

### 11.2 Peer id

The peer id is:

```
peer = f"{useragent}::{client_id}::{db_name}::{session_id}"
```

for example `Wiggins::1::wiggins-pixel::6b1e3c1f-…`. It is built from
`HiveMindClientConnection.peer (97-101)`, with the name set in `open()` (201).
OVOS replies are addressed to this exact string (§12). It only gets its final
value when HELLO (or the first BUS message) provides a non-`default`
`session_id`.

**Keep the same `session_id` for the whole connection.** `handle_bus_message (550-573)`
only updates the stored session when the incoming `session_id` equals the
stored one. Lang or timezone changes are therefore picked up from each BUS
message's `context.session` as long as the id matches.

### 11.3 Sending an utterance

Send this encrypted:

```json
{"msg_type": "bus",
 "payload": {"type": "recognizer_loop:utterance",
             "data": {"utterances": ["what time is it"], "lang": "en-US"},
             "context": {"source": "Wiggins", "destination": "HiveMind", "platform": "Wiggins",
                         "session": { …same session as HELLO… }}}}
```

The Python client sets `context` to source=useragent, platform=useragent,
destination=`"HiveMind"` and `session.session_id`/`site_id`
(`client.py:emit (367-379)`).

On the hub, `handle_inject_agent_msg (795-825)` does the following:

- If the type is not in the client's `allowed_types`, the message is **dropped
  silently**. The hub only logs "sent an unauthorized bus message" and sends
  nothing back (confirmed live).
- `context.session` is replaced by the hub's copy of the session, plus the
  blacklists.
- `context.destination` is set to `"skills"` only if absent. `"HiveMind"` is
  kept.
- `context.source` and `context.peer` are both set to the peer id.
- The message is emitted on the OVOS bus.

### 11.4 allowed_types

`allowed_types` is what the client may **send**. It is taken from the DB record
at connect time, so a change made with `allow-msg` needs a reconnect.

The default for a new record is
`hivemind_plugin_manager/database.py:Client.__post_init__`:

- `recognizer_loop:utterance`
- `recognizer_loop:record_begin`, `recognizer_loop:record_end`
- `recognizer_loop:audio_output_start`, `recognizer_loop:audio_output_end`
- `recognizer_loop:b64_transcribe`
- `speak:b64_audio`
- `ovos.common_play.SEI.get.response`

`recognizer_loop:utterance` is always added. `hivemind-core allow-msg <type> <id>`
appends a type. The Waggle types for M5 must be added this way.

What the client **receives** is filtered by `message_blacklist`, which drops
outgoing types silently, not by `allowed_types`.

### 11.5 Hub speech (M4)

With the hub's `hivemind-audio-binary-protocol` plugin enabled, a client can have
the hub transcribe and synthesize speech using ordinary encrypted `bus` messages
(binarize stays off). The plugin's handlers subscribe on the hub's own OVOS bus
connection: the request goes onto the bus, the handler answers with
`message.reply(...)`, and the answer is routed back like any other reply (§12).
Wiggins' code: `HiveProtocol.transcribe`/`synthesize` and the two response cases
in `onBus`.

**Speech to text.** Client → hub:

```json
{"type": "recognizer_loop:b64_transcribe",
 "data": {"audio": "<base64 WAV>", "lang": "en-US", "sample_rate": 16000, "sample_width": 2},
 "context": {"…": "…", "session": {"…": "…"}, "wiggins_id": "<random id>"}}
```

Hub → client: `recognizer_loop:b64_transcribe.response` with
`data.transcriptions`, a list of `[text, confidence]`. A failed STT answers
`[[null, 1.0]]`. The data doesn't echo the request, but `reply` copies the
request's context, so `context.wiggins_id` matches it.

- Send 16 kHz mono 16-bit **WAV**. Plugin 2.1.x reads the base64 as an audio
  file (and the hub image has no ffmpeg, so only WAV works); the 2.2 alphas read
  raw 16 kHz s16 PCM and check `sample_rate`/`sample_width`, for which the
  44-byte header is about 1 ms of noise.
- Never send `lang: "auto"`: the hub's STT client then asks a public language
  detection server, and the audio leaves the hub.

**Text to speech.** Client → hub: `speak:b64_audio` with
`{"utterance": "…", "lang": "en-US", "wiggins_id": "<random id>"}`. Hub → client:
`speak:b64_audio.response`, whose data is the request's data plus `audio`, a
base64 WAV file at the engine's own rate and format (Kokoro: 24 kHz mono 16-bit;
Piper: 22050 Hz). A failed TTS sends **no** reply, so the client needs a timeout.

Both types are in stable's default `allowed_types` (§11.4); on hubs that grant
nothing by default, add them with `allow-msg`, one type per command.

## 12. Downlink: what the hub sends after the handshake

The hub sends nothing on its own after HELLO. No pings, no status messages.
Everything else comes from `ovos_bus_client/hpm.py:OVOSProtocol.handle_internal_mycroft (79-102)`:
every OVOS bus message whose `context.destination` (a string or a list) contains
this client's peer id is wrapped and sent:

```json
{"msg_type": "bus",
 "payload": {"type": "speak",
             "data": {"utterance": "It's 3:14 PM.", "expect_response": false,
                      "meta": {"skill": "fake-skill.wiggins", "dialog": "time.current"}, "lang": "en-US"},
             "context": {"source": "hive",
                         "destination": "Wiggins::1::wiggins-pixel::6b1e3c1f-6a8f-4d7e-9a43-0c2c5b1e7f10",
                         "platform": "Wiggins",
                         "session": { …full session as the hub/ovos-core sees it… },
                         "peer": "Wiggins::1::wiggins-pixel::6b1e3c1f-…"}},
 "metadata": {}, "route": [], "node": null, "target_site_id": null, "target_pubkey": null,
 "source_peer": "Wiggins::1::wiggins-pixel::6b1e3c1f-…"}
```

- `context.source` is always rewritten to `"hive"`.
- `destination` can be a string or a list.
- Routing works because ovos-core answers with `message.reply(...)`, which swaps
  source and destination so that destination becomes our peer id. Skills
  `.forward(...)` that context.
- So the client receives **every** message produced while handling its
  utterance, not just `speak`. The captures (made with a fake ovos-core that
  imitates this routing) show three: the intent message itself
  (`<skill_id>:<IntentName>`), `speak`, and `ovos.utterance.handled`.
- A real ovos-core will also send other types, for example
  `mycroft.skill.handler.start`/`complete`, `complete_intent_failure`,
  `mycroft.audio.play_sound`, OCP and GUI messages. These were **not captured
  here**. SPEC M1's "log every type" is the right way to find them.
- **Handling replies:** take `payload.type == "speak"` and read
  `data.utterance`. `data.expect_response == true` means the skill wants a
  follow-up utterance.
- **End of turn:** `ovos.utterance.handled` marks the end of handling for an
  utterance.

Messages are only routed to peers registered in `hm.clients`, which requires
HELLO with a real `session_id` first (§4.5). The Python client's internal
processing also renames `context.destination` to `source` before emitting
locally (`protocol.py:handle_bus (216-231)`). That is internal only, and
Wiggins doesn't need it.

## 13. Keepalive, close and error behavior

| Situation | Hub behavior | Client sees | Source / evidence |
|---|---|---|---|
| WS ping frame from client | tornado replies with pong (same payload) | pong | `tornado/websocket.py (1256-1262)`; live: `reference_client` pong frame |
| Idle connection | no server pings, no idle timeout | nothing (proxies may cut it, so ping from the client, e.g. OkHttp `pingInterval`) | `ping_interval` unset |
| HiveMessage `ping` type | ignored (no reply) | nothing | `handle_unknown_message` |
| Invalid access key | logs "invalid api key", emits `hive.client.connection.error` on OVOS bus, `self.close()` with no code. **No HELLO.** | close frame with **no status code** (1005) | `open (195-199)`; live `errors[0]` |
| Missing or undecodable `authorization`, or `:` in it | exception in `open()`, tornado `_abort()` | TCP drop, **1006**, no HELLO | `tornado/websocket.py (964-971)`; live `errors[2]`, `errors[4]` |
| Wrong password | hub still completes the handshake. The client's envelope check fails. If the client sends encrypted data anyway, `DecryptionKeyError` leads to abort. | 1006 after first encrypted frame | live `errors[1]` |
| Unknown cipher or encoding name | `InvalidCipher`/`InvalidEncoding`, then abort | 1006 | `errors.json` case 3 |
| Known but disallowed cipher/encoding only | `client.disconnect()` | close frame, no status (1005) | `handle_handshake_message (489-493)` |
| HANDSHAKE with neither `pubkey` nor (`envelope` and a password on the record) | `client.disconnect()` | 1005 | `(519-522)` |
| Bad JSON, unknown envelope key, bad `msg_type`, or decrypt failure in any frame | exception in `on_message`, then `_abort()` | 1006 | `_run_callback (650-669)` |
| BUS type not in `allowed_types` | logged and dropped; connection stays open | nothing | `handle_inject_agent_msg (805-807)`; live |
| No crypto key, handshake disabled, crypto required | `handle_invalid_protocol_version`, close | 1005 | `open (217-229)` (not reachable with the example config) |

There is no error message at the HiveMind level for any of these. The client
has to infer the cause:

- close before HELLO means bad credentials or URL;
- an envelope mismatch means a wrong password;
- a drop right after the client's first encrypted frame means a key mismatch.

Reconnect with backoff. Every reconnect does a new handshake, with new IVs and
a new key.

## 14. Test vectors (`app/src/test/resources/hivemind-vectors/`)

All files carry `_meta` with the generator and package versions.

| File | Contents | How to use |
|---|---|---|
| `auth.json` | useragent, key → `authorization`, full URL, request-target. One case contains `+ / =`. | Exact string equality. |
| `password_handshake.json` | password, both IVs → both envelopes, salt, the 32-byte key. Includes a wrong-password case where the keys differ and verification is false, a non-ASCII password and an all-zero salt. `hsub_match_checks` covers uppercase (no match), length 47 (no match) and length 80 (match). | Exact equality. Case 0's key is used by `encryption.json` and `full_session.json` session 0. |
| `encryption.json` | `encrypt_cases`: every cipher × encoding (Z85P excluded) with a fixed key and nonce, giving ciphertext, tag and the exact `wire`. Also a full utterance plaintext, an empty plaintext and the legacy AES-128 key. `decrypt_cases`: tag appended to the ciphertext (ok), tampered tag (fail), AES-GCM with a 12-byte nonce (**fail**), uppercase hex (ok). `broken_encodings`: Z85P. | Encrypt with the given nonce and compare `ciphertext_hex`/`tag_hex`. Comparing `wire` byte for byte also requires Python's `json.dumps` spacing and key order (`{"ciphertext": "…", "tag": "…", "nonce": "…"}`). |
| `messages.json` | Plaintext HiveMessage JSON for: server HELLO, server HANDSHAKE request, client HANDSHAKE, server HANDSHAKE response, client HELLO, client utterance, server `speak`, server `ovos.utterance.handled`. Includes a `parsed` copy. | Compare parsed JSON. |
| `full_session.json` | 3 deterministic sessions, run against the real hub handler code in-process: ChaCha20+B64, AES-GCM+HEX, and a preference-order demo. Each frame has `dir`, `wire`, `plaintext`, `encrypted`, `hive_msg_type` and `bus_type`, plus inputs, the negotiated cipher, encoding and key, and what the hub injected into the OVOS bus. | Feed hub→client `wire` frames into the client given `inputs.client_iv_hex` and check the decrypted plaintexts and the derived key. For client→hub frames, decrypt `wire` and compare parsed JSON. To compare ciphertext exactly, use the frame's own nonce and the plaintext string. |
| `errors.json` | In-process: invalid key, unauthorized BUS type dropped, unknown cipher name. | Behavior reference. |
| `live_capture.json` | Real frames from a throwaway hub container (random IVs and nonces). `reference_client` is the recommended config, plus a ws ping, an unauthorized `speak` and an unencrypted BUS. `stock_python_client` is HiveMessageBusClient 0.4.4. `errors` covers bad key, wrong password, missing auth, and raw vs. percent-encoded auth. Each includes `negotiated.key_hex` and the hub log lines. | Decrypt every frame with `negotiated.key_hex` as a realism check. Timings are in `t`. |

### 14.1 Regenerating

From the repo root:

```sh
# deterministic vectors, exact versions (inside the hub image)
podman run --rm --user root -v "$PWD":/w:Z -w /w \
  --entrypoint /home/hivemind/.venv/bin/python \
  docker.io/smartgic/hivemind-listener:stable-20260922 tools/vectors/generate.py

# or with a pinned uv venv (gives byte-identical output; verified)
uv venv --python 3.13 /tmp/hm-vectors-venv
VIRTUAL_ENV=/tmp/hm-vectors-venv uv pip install -r tools/vectors/requirements.txt
/tmp/hm-vectors-venv/bin/python tools/vectors/generate.py

# live capture (starts and stops its own throwaway hub inside one --rm container)
podman run --rm --user root -e HOME=/tmp/hmhome -v "$PWD":/w:Z -w /w \
  --entrypoint /home/hivemind/.venv/bin/python \
  docker.io/smartgic/hivemind-listener:stable-20260922 tools/vectors/live_capture.py
```

`generate.py` patches three sources of randomness, and its docstring documents
each:

- the PasswordHandShake IVs (`poorman_handshake.symmetric.generate_iv`);
- the AEAD nonces (`hivemind_bus_client.encryption.encrypt_AES` and
  `encrypt_ChaCha20_Poly1305` wrapped to always pass `nonce=`);
- the hub's RSA identity key (`RSA.generate(randfunc=SHA-256 counter DRBG)`).

Its "reference client" is about 30 lines of Python written from this document,
so it doubles as an executable summary. `live_capture.json` changes on every
run, and the other files are byte-stable.

## 15. Gotchas, collected

1. **No URL-encoding of `authorization`**, `/` path only, `authorization`
   must be the last query parameter, and no `:` in the useragent or key.
2. **Never send unknown envelope keys.** The hub does `HiveMessage(**json)`, so
   an extra field kills the connection.
3. **AES-GCM nonce is 16 bytes, not 12.** Prefer ChaCha20-Poly1305 (12-byte
   nonce, standard).
4. **JSON-Z85P is broken.** Offer only what you implement, and B64/HEX are
   enough.
5. **The hub never checks the password.** A wrong password surfaces as a 1006
   drop after the first encrypted frame. Verify the hub's envelope client-side
   and report it before sending.
6. The **HANDSHAKE request says `"handshake": false`** for add-client records,
   because of the always-present legacy key. Handshake anyway when
   `"password": true`.
7. `pubkey` in the client HANDSHAKE switches the hub to the RSA path, which
   skips negotiation and the password. Leave it out.
8. **Session defaults come from the hub container**, which has stock OVOS
   config: Lawrence, KS, en-us, metric, a default pipeline, and the stop skill
   blacklisted. Send lang, location.timezone, units and formats explicitly. The
   pipeline and blacklist question needs checking against the real ovos-core.
9. Replies are routed only after HELLO with a non-`default` `session_id`.
   Keep the `session_id` constant for the connection.
10. **Unauthorized message types vanish silently.** For Waggle (M5), run
    `hivemind-core allow-msg` per type and reconnect.
11. **Every hub message is either plaintext `hello`/`shake` or encrypted.** Detect
    encrypted frames by the `ciphertext` key.
12. **No server keepalive.** Send websocket ping frames from the client.
13. The hub's error signalling is only close/abort. Map: close before HELLO
    means auth; envelope mismatch means password; drop after the first
    encrypted frame means a key mismatch.
14. **PBKDF2 at 100k iterations is CPU work.** Do it off the main thread on each
    (re)connect.
15. The stock Python client takes about 6 s to connect: it registers its
    HANDSHAKE handler after the hub's request arrives and waits for its 5 s
    retry (seen in `live_capture.json`). Wiggins should process frames from
    the moment the socket opens.
16. **The protocol is moving.** Newer hivemind-core no longer grants default
    `allowed_types`, so each client needs explicit `allow-msg` grants there.
    Re-run `tools/vectors` against any new image tag before bumping the hub.

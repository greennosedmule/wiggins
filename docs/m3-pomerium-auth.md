# M3: Wiggins through Pomerium

Research for the M3 remote-access modes, run on 2026-10-04 against the
example deployment for sign-in mode: a Pomerium ingress controller in
Kubernetes with Entra ID as the identity provider,
`pomerium/ingress-controller:v0.33.3`, which embeds Pomerium
core `v0.33.3` (`go.mod:21` of the controller). Source citations are
`file:line` at tag `v0.33.3` of
[pomerium/pomerium](https://github.com/pomerium/pomerium/tree/v0.33.3)
(no prefix) and
[pomerium/ingress-controller](https://github.com/pomerium/ingress-controller/tree/v0.33.3)
(prefix `ic/`). Placeholders: `hivemind.example.com` is the public route
to the hub, `auth.example.com` is Pomerium's authenticate host, `<tenant-id>`
is the Entra tenant, `<pomerium-app-client-id>` is the client ID of the
Pomerium app registration, and `<wiggins-client-id>` is the client ID of the
Wiggins public-client registration.

**Deployment assumed:** Pomerium is not Traefik forward-auth middleware here.
It runs as its own ingress controller (class `pomerium`) that receives
internet traffic on 443 and terminates TLS, with policy as
`ingress.pomerium.io/*` annotations on each Ingress. Everything below assumes
that.

## Recommendation

1. **Proxy-login mode: an Entra access token, checked by Pomerium**
   (`bearer_token_format: idp_access_token`). Wiggins is an ordinary OAuth 2.1
   public client with PKCE (AppAuth-Android, Apache-2.0, no Play Services). It
   signs in to Entra in a Custom Tab with a custom-scheme redirect and gets an
   access token for the Pomerium app registration's API, refreshing it silently with
   the refresh token. It sends `Authorization: Bearer <access token>` on the
   websocket upgrade. Pomerium verifies the token against the tenant's keys and
   issuer, checks the audience, turns its claims (including `roles`) into a
   session, and applies `claim/roles: hivemind.access`. Why this rather than
   Pomerium's programmatic login:
   - There's no 14-hour re-login with an account picker. Programmatic-login
     sessions last `cookie_expire` (default 14 h) and can't be refreshed by the
     client.
   - Tokens survive Pomerium restarts. A `pomerium_jwt` lives only as long as
     its databroker session, so with non-persistent databroker storage (see
     Open items) every pod restart invalidates every `pomerium_jwt`.
   - The token is limited by audience. A `pomerium_jwt` is a handle to a
     Pomerium-wide session, so it also opens every other route whose role the
     user holds.
   - Redirects are standard. Programmatic login only redirects to `http(s)`
     hosts on an allowlist (loopback by default), so getting back into an
     Android app needs a loopback listener plus a second hop.

   This reverses the 2026-10-04 decision ("Pomerium, not direct
   OAuth/AppAuth"). That decision assumed Traefik forward-auth, and Pomerium
   still enforces the policy here. Wiggins does now talk to Entra directly. If
   that decision stands, the fallback below is complete on its own.

   **Fallback, if the live test of (1) fails:** Pomerium programmatic login
   with a loopback redirect (Q2). It needs no Entra or global Pomerium change.
2. **Client-certificate mode:** implement it on the client in M3 (KeyChain
   alias or imported PKCS#12, through an `X509KeyManager` on OkHttp). Turn it
   on at the server only after the live test in Open items, because it needs a
   global change to the Pomerium CR (`downstreamMtls.ca` with
   `enforcement: policy`). Once a client CA is configured, every Pomerium host
   asks for a client certificate during the handshake (Q5). The route then
   admits a request on **either** the Entra role **or** a pinned certificate
   (SPKI hash).
3. **Hostname:** give the Pomerium route its own name, separate from the
   name LAN clients use for the hub's own `ws://…:5678` Service. If cluster
   DNS learns names from both Service annotations and Ingress status (for
   example k8s_gateway), one shared name would resolve to the ingress and
   break LAN satellites that connect to the hub directly. The examples use
   **`hivemind.example.com`** for the Pomerium route. If something in front of
   Pomerium splits port 443 by SNI and passes only some names through (an edge
   router or stream proxy), add the new name to its passthrough list.
4. **Entra app role:** define an app role (the examples use `hivemind.access`)
   on the Pomerium app registration and assign it to the users who may reach
   the hub. The other Entra changes needed for (1) are listed below.

### Ingress

```yaml
# HiveMind for Wiggins outside the LAN, through Pomerium. HiveMind encrypts
# its payloads itself; Pomerium is the network gate. Pomerium admits a
# websocket upgrade carrying an Entra access token, issued for the Pomerium
# app registration's API, whose roles claim holds hivemind.access. A browser session
# cookie and a Pomerium programmatic-login token
# (Authorization: Pomerium <jwt>) also work.
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: hivemind-pomerium
  namespace: hivemind
  annotations:
    ingress.pomerium.io/policy: |
      allow:
        or:
          - claim/roles: hivemind.access
    # Envoy refuses Upgrade: websocket unless this is set. It also turns off
    # both the route timeout and the stream idle timeout for this route.
    ingress.pomerium.io/allow_websockets: "true"
    # Read "Authorization: Bearer <x>" as an Entra access token.
    ingress.pomerium.io/bearer_token_format: idp_access_token
    # Required: without it Pomerium checks no audience at all. Use the
    # Pomerium app registration's client ID (v2 tokens), or its api://
    # identifier URI if the API keeps v1 tokens.
    ingress.pomerium.io/idp_access_token_allowed_audiences: '["<pomerium-app-client-id>"]'
    # Optional: keep the token out of the hub. HiveMind reads only the query
    # string. Route-level removal happens after ext_authz has seen the header.
    ingress.pomerium.io/remove_request_headers: '["Authorization"]'
spec:
  ingressClassName: pomerium
  rules:
    - host: hivemind.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: hivemind
                port:
                  number: 5678
```

The annotation value formats are copied from the controller's own test
(`ic/pomerium/ingress_annotations_test.go:45-75`).

Once client-certificate mode is enabled (after its live test), the policy
becomes:

```yaml
    ingress.pomerium.io/policy: |
      allow:
        or:
          - claim/roles: hivemind.access
          - client_certificate:
              spki_hash: "<base64 SHA-256 of the phone cert's SubjectPublicKeyInfo>"
```

The Pomerium CR also gets
`downstreamMtls: {ca: <base64 of the PEM CA bundle>, enforcement: policy}`.
**Pin by `spki_hash` or `fingerprint`, never by `san_*` alone:** with
`enforcement: policy`, Envoy accepts untrusted chains (Q5).

### Entra changes for (1)

- On the Pomerium app registration, expose an API: an identifier URI, one delegated
  scope (for example `hivemind`), and `requested_access_token_version = 2`, so
  that `aud` is the client ID and `iss` is the v2 tenant issuer. Pomerium
  accepts both v1 and v2 issuers (`pkg/identity/oidc/azure/microsoft.go:238`),
  but the audience you allow must match the version. App roles appear in the
  `roles` claim of access tokens whose resource is the app that defines them,
  so the token must be for this API, not for Graph.
- Add a new public-client app registration "Wiggins": platform "Mobile and
  desktop applications", redirect URI `com.mulesipstea.wiggins://oauth2redirect`,
  public client flows allowed. Grant it the delegated scope above, with admin
  consent (`azuread_service_principal_delegated_permission_grant`). No secret.
  Keep it separate rather than adding a public platform to the confidential
  Pomerium app registration.
- Role assignment: require assignment on the Pomerium app's enterprise
  application, and assign `hivemind.access` to each user who may connect.

### Client flow (1)

1. Discovery:
   `https://login.microsoftonline.com/<tenant-id>/v2.0/.well-known/openid-configuration`.
2. AppAuth authorization request: `client_id=<wiggins-client-id>`, PKCE S256,
   `redirect_uri=com.mulesipstea.wiggins://oauth2redirect`,
   `scope=api://<pomerium-app-client-id>/hivemind offline_access`, in a Custom Tab
   (Vanadium on GrapheneOS supports Custom Tabs).
3. Exchange the code for tokens, and store the AppAuth `AuthState` in the
   Tink-encrypted DataStore.
4. Before each connect, `performActionWithFreshTokens`, then open the websocket:
   `wss://hivemind.example.com/?authorization=<b64>`
   with `Authorization: Bearer <access token>`.
5. Upgrade failure handling:
   - **401**: the token was missing, invalid or expired. Refresh once, then
     prompt for sign-in.
   - **403**: signed in but without the role. Show an error and don't retry.
   - **302 to the authenticate host**: never happens with a header present, but treat it
     as 401.

   An already open websocket isn't affected when the token expires (Q3).

### Client flow, fallback (programmatic login)

1. Open `ServerSocket(0, 1, InetAddress.getLoopbackAddress())`. `state` is 32
   random bytes, base64url-encoded.
2. Send `GET https://hivemind.example.com/.pomerium/api/v1/login?pomerium_redirect_uri=http%3A%2F%2F127.0.0.1%3A<port>%2Fcallback%3Fstate%3D<state>`.
   It answers `200 text/plain` whose body is a signed `https://auth.example.com/.pomerium/sign_in?…`
   URL, valid for 5 minutes.
3. Open that URL in a Custom Tab. After Entra and Pomerium's callback, the tab
   is redirected to
   `http://127.0.0.1:<port>/callback?state=<state>&pomerium_jwt=<jwt>`.
4. Check `state`, keep the JWT, and answer
   `302 Location: com.mulesipstea.wiggins://auth/done`, with an HTML body
   holding a "Return to Wiggins" link as a fallback. The custom scheme carries
   no secret. If that hop is blocked, the activity's `onResume` still finds the
   token once the user closes the tab.
5. Connect with `Authorization: Pomerium <jwt>`, or
   `X-Pomerium-Authorization: <jwt>`, which still works if the route also has
   `bearer_token_format: idp_access_token`. On 401, log in again.

## Answers

### 1. Programmatic login

- **Endpoint.** `GET /.pomerium/api/v1/login?pomerium_redirect_uri=<url>` on
  any route host. Any other method gets 405, and a request with no
  `pomerium_redirect_uri` gets 404 (`proxy/handlers.go:60-90`). Control-plane
  routes for `/.pomerium/` are added to every route host
  (`config/envoyconfig/routes.go:66-74`). There's no per-route switch, so the
  API is always on.
- **Response.** `ProgrammaticLogin` (`proxy/handlers.go:113-149`) validates the
  URI and writes `200 text/plain` whose body is the sign-in URL. That's
  `<authenticate_url>/.pomerium/sign_in` with `pomerium_redirect_uri`,
  `pomerium_callback_uri=https://<route>/.pomerium/callback/`,
  `pomerium_programmatic=true` and `pomerium_idp_id`, HMAC-signed
  (`internal/authenticateflow/stateful.go:686-708`). The signature expires 5
  minutes after issue (`internal/urlutil/signed.go:34`). A self-hosted
  authenticate service uses the stateful flow; only hosted authenticate uses
  the stateless one (`config/options.go:860`, `proxy/state.go:86-91`).
- **Token.** After the IdP login, the route's `/.pomerium/callback/` decrypts
  the session handle and redirects to `pomerium_redirect_uri` with
  `pomerium_jwt=<raw handle JWT>` added to its query
  (`internal/authenticateflow/stateful.go:722-776`, the token at `:753`).
  Existing query parameters on the redirect URI are kept, so a `state`
  parameter survives. The JWT is a `session.Handle` (session ID, user ID, IdP
  ID, databroker versions), HS256-signed with the shared secret
  (`config/session.go:59`). It sets no `exp`.
- **Lifetime.** The databroker session expires `cookie_expire` after login
  (`internal/authenticateflow/stateful.go:519-525`, default 14 h at
  `config/options.go:328`, unless the CR sets `cookie.expire`). `Session.Validate`
  rejects it after that (`pkg/grpc/session/session.go:133-148`). Pomerium
  refreshes the Entra tokens server-side until then. The client has no refresh
  call: it logs in again. Docs:
  [programmatic access](https://www.pomerium.com/docs/capabilities/programmatic-access).
  A session lost from the databroker also kills the token (see the
  storage open item).
- **Presenting it.** Any of `X-Pomerium-Authorization: <jwt>`,
  `Authorization: Pomerium <jwt>` or `Authorization: Bearer Pomerium-<jwt>`
  (`internal/sessions/header/header.go:49-69`). A `pomerium_session=<jwt>`
  query parameter also works (`config/session.go:77-79`). With a token
  present, an unauthenticated request gets 401, not a redirect
  (`authorize/check_response.go:426-435`).
- **Allowed `pomerium_redirect_uri`.** The scheme must be `http` or `https`, so
  custom schemes like `wiggins://auth` are **rejected**. The hostname must also
  be on `programmatic_redirect_domain_whitelist`, where the entry `localhost`
  matches any loopback host: `localhost`, `127.0.0.1` or `[::1]`
  (`internal/urlutil/whitelist.go:9-35`). The default list is `["localhost"]`
  (`config/options.go:348`), and the scheme isn't otherwise restricted, so
  `http://127.0.0.1:<port>/…` works with no configuration. The allowlist is
  global: CR field `spec.programmaticRedirectDomains`
  (`ic/apis/ingress/v1/pomerium_types.go:283-285`, mapped at
  `ic/pomerium/config.go:171`). Setting it replaces the default
  (`config/options.go:2054-2058`), so `localhost` must be listed again.

### 2. Android redirect options

- **Custom scheme:** impossible with programmatic login (`whitelist.go:10`). It
  is the standard path for the Entra-token mode (AppAuth + Entra public
  client).
- **https App Link:** possible only by adding the domain to
  `programmaticRedirectDomains`. The domain must serve
  `/.well-known/assetlinks.json` without auth, and that needs another Ingress,
  or `allow_public_unauthenticated_access` and a static server. Verification on
  GrapheneOS goes through AOSP's statement service. If verification ever
  fails, the browser loads the URL, and the `pomerium_jwt` goes to whatever
  serves that host. Too many parts.
- **Loopback listener:** works with the default allowlist. Chrome's Local
  Network Access permission covers subresources, subframes and (from M147)
  WebSockets. Main-frame navigations are out of scope
  ([WICG explainer](https://github.com/WICG/local-network-access/blob/main/explainer.md),
  [Chrome intent to ship](https://groups.google.com/a/chromium.org/g/blink-dev/c/cwu_RUmBpzY)),
  so the redirect to `http://127.0.0.1` should go through. Still to verify on
  Vanadium. The weak point is getting back into the app: a background thread
  can't reliably bring the activity forward on Android 15+ when the top activity
  in the task is the browser's (background activity launch rules). Hence the
  second-hop 302 to the custom scheme, with `onResume` as the fallback.
- **Most robust:** the Entra-token mode with AppAuth and a custom scheme. For
  programmatic login: loopback, then a custom-scheme hop, then `onResume`
  polling (URLs in the client flow above).

### 3. Websockets, timeouts and expiry

- **Token on the upgrade.** The ext_authz check runs on the upgrade request's
  headers like any other request: `Check` → `loadSession` → header readers
  (`authorize/grpc.go:34-147`). Nothing is websocket-specific, so the
  `Authorization` header on the GET with `Upgrade: websocket` is honoured
  (live test below).
- **Enabling websockets.** The annotation `ingress.pomerium.io/allow_websockets: "true"`
  (`ic/pomerium/ingress_annotations.go:27`), or `allow_upgrades: ["websocket"]`.
  Without it, the route's Envoy `upgrade_configs` has websocket disabled
  (`config/envoyconfig/routes.go:437-442`, `config/policy.go:1049-1070`).
- **Timeouts.** With websockets allowed, the route timeout and the route idle
  timeout are both set to 0 (disabled) unless set explicitly
  (`config/envoyconfig/routes.go:598-626`). The docs say the same:
  "websockets are long-lived connections, so both the route timeout and idle
  timeout are automatically disabled"
  ([timeouts](https://www.pomerium.com/docs/reference/routes/timeouts)).
  Annotations: `ingress.pomerium.io/timeout` (route timeout) and
  `ingress.pomerium.io/idle_timeout` (stream idle). Global settings:
  `spec.timeouts.{read,write,idle}`, defaulting to 30 s, 0 and 5 min
  (`config/options.go:330-334`). These map to the HCM's
  `request_timeout`/`max_stream_duration`/connection `idle_timeout`
  (`config/envoyconfig/listeners_main.go:177-202`):
  - `request_timeout` stops counting once the 101 response starts.
  - `max_stream_duration` is unlimited by default.
  - The connection `idle_timeout` applies only when the connection has no
    active streams.

  So **nothing in Pomerium drops an idle websocket.** NAT on the mobile network
  and the router still will, so keep OkHttp's `pingInterval` (for example 30 s).
  Ping and pong frames are passed through.
- **Re-checks.** Policy is evaluated only on the upgrade request. The only
  re-evaluation in core is for SSH (`pkg/ssh/stream.go`). An expiring session
  or token **does not** close an open websocket. It is closed by:
  - a Pomerium pod restart;
  - a listener change: certificate renewal, a mTLS CA change, global TLS
    settings. Envoy drains for 60 s and then closes
    (`pkg/envoy/envoy_linux.go:84-86`).

  Route changes go through RDS (`config/envoyconfig/listeners_main.go:236-244`)
  and don't drop connections. The client should expect an occasional close and
  reconnect, which it already does.

### 4. MCP authorization-server mode

Not usable for a non-MCP route. MCP access tokens are looked up only when
`policy.IsMCPServer()` or the path is under `/.pomerium/mcp`
(`authorize/grpc.go:153-161`). Any other route reads only
cookie/header/query session handles or IdP tokens (`authorize/grpc.go:101-147`,
`config/session.go:164-182`). An MCP token sent to the HiveMind route is
therefore never recognised.

Turning the HiveMind route into an `mcp_server` route would be a hack:
- the route buffers request bodies into ext_authz
  (`config/envoyconfig/routes.go:333-336`, `per_filter_config.go:28-41`);
- ext_proc runs on it;
- unauthenticated responses become MCP-shaped (`authorize/check_response.go:274-290`).

For the record, the MCP AS is a real OAuth 2.1 server. It supports
`authorization_code` and `refresh_token` grants, and its refresh tokens last
365 days (`internal/mcp/handler_token.go:28,63-72`). The access token lives as
long as the Pomerium session (`:618-642`). Clients identify themselves only by
an https CIMD URL on `mcpAllowedClientIdDomains`
(`internal/mcp/client_id_metadata.go:117-121`). Redirect URIs are matched
exactly against the document (`:298-303`), with no scheme rule. So AppAuth
could talk to it, but only for MCP routes. The standard OAuth path for a
non-MCP route is the IdP-token mode in the Recommendation.

### 5. Client certificates

- **Per-route CA.** Annotation `ingress.pomerium.io/tls_downstream_client_ca_secret: <secret>`,
  where the Secret has key `ca.crt` (`ic/model/ingress_config.go:26,56`,
  `ic/pomerium/ingress_annotations.go:251-254`). Pomerium marks the per-route CA
  **deprecated**
  ([TLS settings](https://www.pomerium.com/docs/reference/routes/tls#tls-downstream-client-certificate-authority)).
- **Global setting.** CR field
  `spec.downstreamMtls: {ca, crl, enforcement, matchSubjectAltNames, maxVerifyDepth}`
  (`ic/apis/ingress/v1/pomerium_types.go:201-222`, mapped at
  `ic/pomerium/config.go:359-405`). `ca` is a CRD `[]byte` field, so the YAML
  value is base64 of the PEM. Enforcement modes
  ([downstream mTLS](https://www.pomerium.com/docs/reference/downstream-mtls-settings)):
  - `policy_with_default_deny` (the default) adds
    `deny: invalid_client_certificate` to every route
    (`authorize/authorize.go:220-224`, `pkg/policy/parser/default.go:5-9`).
  - `policy` adds nothing; the routes decide.
  - `reject_connection` requires a certificate in the handshake.
- **Listener-wide.** Every per-route CA and the global CA go into one bundle on
  the listener (`config/envoyconfig/tls.go:185-207`). As soon as there's any
  CA, every Pomerium host (the authenticate host and every route) sends a
  CertificateRequest. Except under `reject_connection`, Envoy uses
  `ACCEPT_UNTRUSTED` (`tls.go:334-338`). The chain is checked later in
  authorize, against the route's own CA if it has one, else the global CA
  (`authorize/evaluator/evaluator.go:415-425`). An empty CA counts as valid
  (`authorize/evaluator/functions.go:80-93`).
- **Certificate alone, with no IdP session: yes.** The PPL `client_certificate`
  criterion matches `fingerprint` (SHA-256 of the DER, hex), `spki_hash`
  (base64 SHA-256 of the SPKI), or `san_email`/`san_dns`/`san_uri`
  (`pkg/policy/criteria/client_certificate.go:16-63`). It parses the presented
  leaf but **doesn't validate the chain**. A pin is still sound, because the
  TLS handshake proves the client holds the private key. A SAN match without
  chain validation is not.
- **"Valid certificate OR Entra login."** PPL can't nest operators, and every
  `and`/`or`/`not`/`nor` block under one action is ORed with the others
  (`pkg/policy/generator/generator.go:60-104`). So "valid chain AND some
  condition" can't be written as an allow next to the role check. The default
  deny would also block the Entra-only path, which rules out the default
  enforcement mode. What works:
  - `enforcement: policy`, a global `ca` so Envoy asks for certificates, and
    `allow: or: [claim/roles: hivemind.access, client_certificate: {spki_hash: …}]`.
  - Or, without the global change: a second host with the deprecated per-route
    CA, the default enforcement and `allow: or: [accept: true]`. The automatic
    deny then requires a valid chain, but this is certificate-only and uses a
    deprecated setting.
- **Caveats.**
  - CertificateRequest goes to every host on the listener. Browsers with no
    matching certificate usually continue without prompting, but Chrome on
    Android may show the KeyChain picker. That needs a live test, and it would
    affect the sign-in Custom Tab on the authenticate host too.
  - HTTP/2 coalescing: a connection authenticated for one host can carry
    requests for another covered by the same wildcard certificate. Nothing in
    Pomerium compares SNI with `:authority`. That's harmless here, and OkHttp
    always uses HTTP/1.1 for websockets.
  - TLS 1.3 offers no renegotiation, so a certificate must be presented in the
    first handshake.

### 6. Websocket proxying to HiveMind

- **Path and query.** Envoy forwards `/` and `?authorization=<b64>` unchanged.
  The only path changes are `normalize_path`/`merge_slashes`
  (`config/envoyconfig/listeners_main.go:208-209`), which don't touch `/`.
  There's no prefix rewrite unless one is configured. Pomerium doesn't strip
  ordinary query parameters; it only *reads* `pomerium_session` from them
  (`authorize/check_response.go:516-530`). Base64 `+`, `/` and `=` pass through
  as sent, so URL-encode them on the client as M1 already does.
- **Headers added.** `X-Pomerium-Jwt-Assertion` (removed unless
  `pass_identity_headers`), `X-Forwarded-*`, `X-Request-Id`, and the `Host`
  rewritten to the upstream unless `preserve_host_header`
  (`config/envoyconfig/routes.go:579-595`). The client's `Authorization` header
  is forwarded on non-MCP routes; it's stripped only for MCP
  (`authorize/evaluator/headers_evaluator_evaluation.go:121-122`). Tornado
  ignores all of these.
- **Upgrade.** The upstream gets an HTTP/1.1 upgrade (OkHttp uses HTTP/1.1 for
  websockets). Nothing found that breaks HiveMind, but confirm it with the
  live test.

## Open items needing a live test

The commands assume the Ingress above, applied to a test cluster.
`$H=hivemind.example.com`.

1. **Databroker storage is persistent.** Check what the running Pomerium CR
   actually uses, since it can differ from what your manifests say. File
   storage on an `emptyDir` (for example `storage: {file: {path: /data/pomerium.db}}`
   with `/data` an `emptyDir`), or no storage at all, means every pod restart
   signs everyone out. Use Postgres.
   `kubectl -n pomerium get pomerium global -o jsonpath='{.spec.storage}'`
2. **Programmatic login and the redirect allowlist:**
   ```sh
   curl -s "https://$H/.pomerium/api/v1/login?pomerium_redirect_uri=http://127.0.0.1:8765/cb?state=x"   # expect 200 + sign_in URL
   curl -si "https://$H/.pomerium/api/v1/login?pomerium_redirect_uri=wiggins://auth" | head -1     # expect 400
   python3 -m http.server 8765 --bind 127.0.0.1 &   # open the printed URL in a desktop browser, sign in, read pomerium_jwt from the log
   ```
3. **Websocket upgrade with each token type:**
   ```sh
   # websocat (or: curl -i -N -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: SGVsbG8sIHdvcmxkIQ==')
   websocat -v -H "Authorization: Pomerium $JWT" "wss://$H/?authorization=$HM_AUTH"      # expect 101 and a HiveMind handshake
   websocat -v "wss://$H/?authorization=$HM_AUTH"                                          # no token, no Accept: expect 302 to the authenticate host
   websocat -v -H "Authorization: Pomerium bogus" "wss://$H/?authorization=$HM_AUTH"      # expect 401
   ```
4. **Entra access token** (after the Entra changes). Get a token with the
   Wiggins public client, for example
   `az account get-access-token --scope api://<pomerium-app-client-id>/hivemind`, or
   AppAuth in the emulator. Decode it and check `aud`, `iss` and `roles`, then:
   `websocat -v -H "Authorization: Bearer $AT" "wss://$H/?authorization=$HM_AUTH"`
   → 101. A Graph token or a wrong audience should get 401 or 403. Watch
   `kubectl -n pomerium logs deploy/pomerium -f | grep -E 'authorize|idp-token'`.
5. **Idle survival.** Hold an authorized websocket open with no pings for
   15 min (`websocat --ping-interval 0`). It should still be open, including
   over mobile data. Then let the token or session expire and confirm the open
   connection survives.
6. **Android loopback hand-off (fallback only).** On the emulator and on a
   GrapheneOS phone with Vanadium: Custom Tab → Entra → redirect to
   `http://127.0.0.1:<port>` reaches the app, and the 302 to
   `com.mulesipstea.wiggins://auth/done` returns to the app.
7. **mTLS side effects, before turning on `downstreamMtls`.** Apply it in a
   quiet window, then:
   - `openssl s_client -connect $H:443 -servername auth.example.com </dev/null 2>/dev/null | grep -A3 'Acceptable client certificate CA names'`
     shows the CertificateRequest.
   - Open `https://auth.example.com` and another Pomerium route in Chrome on
     the phone and check for a certificate-picker prompt.
   - `curl --cert phone.crt --key phone.key -H 'Connection: Upgrade' … "https://$H/?authorization=…"`
     → 101 with no Entra session; any other certificate → 403.
   - Get the SPKI pin with
     `openssl x509 -in phone.crt -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64`.
8. **Entra redirect URI form.** Check that Entra accepts
   `com.mulesipstea.wiggins://oauth2redirect` on a "Mobile and desktop"
   platform, and that AppAuth's manifest placeholder
   `appAuthRedirectScheme=com.mulesipstea.wiggins` catches it.
9. **SNI passthrough, if an edge device splits 443 by SNI.** After adding
   the route's name to its passthrough list, from outside the LAN (mobile
   data, or a VPS), run
   `openssl s_client -connect hivemind.example.com:443 -servername hivemind.example.com </dev/null 2>/dev/null | openssl x509 -noout -subject -issuer`.
   It should show Pomerium's certificate for the route, not the edge
   device's own.

# Alarm follow-up investigation ("set an alarm" → answer not understood)

Investigated 2026-10-04 against a HiveMind hub running these images:
`smartgic/ovos-core:stable-20260921` (ovos-core 1.3.1, ovos-workshop 3.4.0,
ovos-bus-client 1.3.7, ovos-date-parser 0.6.4),
`smartgic/ovos-skill-alerts:stable-20260921` (ovos-skill-alerts 0.1.24) and
`smartgic/hivemind-listener:stable-20260922` (hivemind-core 3.4.0,
hivemind-bus-client 0.4.4). Nothing in the hub or its config was changed.

## Cause

**(a) Wiggins.** The answer never reaches the skill's `get_response`. ovos-core
sends it through normal intent matching instead, because the session Wiggins
sends with the answer doesn't mark the alerts skill as waiting for a response.

For any session other than `default`, the OVOS session state is carried in
`message.context.session` and nowhere else. When the skill calls `get_response`,
it sets `utterance_states["ovos-skill-alerts.openvoiceos"] = "response"` in that
session. Then it sends the updated session down to the client on
`skill.converse.get_response.enable`, the `speak` and
`…get_response.waiting`. ovos-core keeps no server-side copy, and neither does
the HiveMind hub. The client has to send that session back with its next
utterance.

Wiggins rebuilds the session from scratch for every utterance (`session_id`,
`lang`, `site_id`, `location`, units and formats only). The answer therefore
arrives with no `utterance_states` and no `active_skills`. ovos-core's converse
stage sees nobody waiting, the answer falls through to the intent pipeline and
ends up in `fallback_low`. The skill's `get_response` hears nothing, times out
after 20 s and says `error_no_time`.

"Sorry. i couldn't catch the time. Please repeat." is **the question itself**
(`alarm_ask_time.dialog`), not a retry. So the user sees the question, answers,
and then gets "I didn't hear a time to set your alarm for".

The lang tag isn't the cause: `en-US` and `en-us` parse the same, and intents
match as `(en-US)`. `destination` isn't the cause either: the hub fixes it up,
and replies route correctly.

Two secondary problems make it worse:

1. **20 s window starting when the question is sent.** The skill container's
   `SessionManager` isn't connected to the bus, so `speak_dialog(…, wait=True)`
   returns at once. The 20 s `get_response_timeout` therefore starts when the
   question is emitted, not when the phone finishes speaking it. TTS, starting
   the recognizer and the user's answer must all fit in those 20 s. The phone's
   "6.30" reached ovos-core 20.35 s after the question, so it would have missed
   the window even with the right session.
2. **"6.30" doesn't parse.** The phone's recognizer wrote "6.30", which
   `extract_dt_or_duration` doesn't parse. "6:30", "six thirty" and "Seven
   thirty in the morning" all parse. Once routing works, "6.30" would get the
   question asked again, not an alarm.

The timer issue is **(c), skill behaviour by design.** "set a timer" with no
duration starts a stopwatch. The skill never asks for a duration, and its dialog
`Timer for {remaining}, starting now.` is rendered with `remaining=""`.

## Evidence

### Logs (times are the hub's local time, 2026-10-04)

ovos-core logs one `Parsing utterance` line for every `recognizer_loop:utterance`
(`ovos_core/intent_services/__init__.py:504`), so these lines are the complete
list of utterances that reached the hub.

Phone exchange:

```
12:54:50.326 ovos-core  Parsing utterance: ['Set an alarm']
12:54:50.334 ovos-core  adapt_medium match (en-US): ...'ovos-skill-alerts.openvoiceos:CreateAlarm'...
12:54:50.343 alerts     ovos_bus_client.session:wait_while_speaking:635 - ERROR - SessionManager not connected to bus, can not monitor speech state
                        ^ get_response speaks "alarm_ask_time" (wait=True returns immediately); 20 s timer starts
12:55:10.378 alerts     ... wait_while_speaking:635 - ERROR - SessionManager not connected to bus ...
                        ^ 20.03 s later: timeout, speak_dialog("error_no_time", wait=True)
12:55:10.688 ovos-core  Parsing utterance: ['6.30']
12:55:10.698 ovos-core  ovos_commonqa.opm:match:139 - Gathering answers from skills: [...]
12:55:10.810 ovos-core  fallback_low match (en-US): PipelineMatch(... skill_id='ovos-skill-fallback-unknown.openvoiceos', utterance='6.30' ...)
```

There is no `converse` match for "6.30". It went through `common_qa` and the
fallbacks like any new question.

Emulator exchange. This one is clearer, because the second utterance arrived
**while** the skill was still waiting:

```
09:52:13.138 ovos-core  Parsing utterance: ['set an alarm']
09:52:13.147 ovos-core  adapt_medium match (en-US): ...CreateAlarm...
09:52:13.157 alerts     wait_while_speaking ERROR (question spoken, get_response waiting until ~09:52:33)
09:52:29.345 ovos-core  Parsing utterance: ['set an alarm']
09:52:29.353 ovos-core  adapt_medium match (en-US): ...CreateAlarm...     <- matched as a new intent, not routed to get_response
09:52:33.272 alerts     wait_while_speaking ERROR (first handler: 20 s timeout -> error_no_time)
09:52:33.300 alerts     wait_while_speaking ERROR (second handler gives up too)
```

If the session had carried `utterance_states: {"ovos-skill-alerts.openvoiceos": "response"}`,
ovos-core would have routed the 09:52:29 utterance to the skill's `get_response`.
Instead it started a second `CreateAlarm`.

"Seven thirty in the morning" isn't in any log. ovos-core's log covers
2026-10-03 09:40 onwards, and only the 18 utterances above reached it. That
test was either earlier or never delivered. The hivemind-listener log only goes
back to 22:26 because of liveness-probe traceback noise. It logs `shake` and
`hello`, not decrypted BUS frames, so it can't show the utterance payloads.

The Wiggins HELLO as the hub received it (23:08:37) has no `utterance_states` or
`active_skills`:

```
{"msg_type": "hello", "payload": {"session": {"session_id": "<session-id>", "lang": "en-US", "site_id": "phone",
 "location": {"timezone": {"code": "<phone timezone>", …}}, "system_unit": "imperial", "time_format": "full",
 "date_format": "MDY"}, "site_id": "phone"}, …}
```

### Source

Skill side (ovos-workshop 3.4.0, `ovos_workshop/skills/ovos.py`):

- `get_response` 1896–1976:
  - `session.enable_response_mode(self.skill_id)` (1928) changes the session
    object deserialized from the intent message.
  - It writes that session back into `message.context["session"]` and emits
    `skill.converse.get_response.enable` (1931).
  - The `speak` and `…get_response.waiting` messages that follow are forwarded
    from the same message, so they carry the same session.
- `__handle_get_response` 1833–1847 accepts the answer only for a known
  `session_id`. `__get_response` 1849–1894 polls for at most
  `skills.get_response_timeout` (default 20, line 1866). The timer restarts
  only on `recognizer_loop:record_begin` or `record_end` for the same session
  (1868–1878).
- `speak(..., wait=True)` 1744–1748 calls `SessionManager.wait_while_speaking`,
  which returns immediately when `SessionManager.bus` is unset
  (`ovos_bus_client/session.py:634-636`). That's the ERROR line in the alerts
  log.

ovos-core 1.3.1, `ovos_core/intent_services/converse_service.py`:

- `_collect_converse_skills` 210–256 picks the skills to converse with from
  **`session.utterance_states`**, where the session comes from the incoming
  message (217). It also asks `session.active_skills`.
- `converse` 267–290 sends `{skill_id}.converse.get_response` only if that
  session's state is `response` (284–290).
- `handle_get_response_enable` 364–370 updates the message's own session copy
  and syncs it into ovos-core's memory **only for `session_id == "default"`**
  (368). For HiveMind sessions, ovos-core keeps no state.
- `SessionManager.get` (`ovos_bus_client/session.py:584-607`) always rebuilds
  the session from `message.context.session` for non-default ids.

The HiveMind hub (hivemind-core 3.4.0, `hivemind_core/protocol.py`):

- `handle_bus_message` 550–573 replaces the hub's stored `client.sess` with the
  session in each client BUS message whose `session_id` matches (567–569).
- `_update_blacklist` 770–793 then overwrites `context.session` on the
  injected message with that stored copy (772), adding the blacklists.
- Downlink (`ovos_bus_client/hpm.py:79-102`) forwards messages to the peer but
  **never** updates `client.sess`.

So whatever session Wiggins sends is exactly what ovos-core sees. The hub
neither drops `utterance_states` nor adds it.

The reference client (hivemind-bus-client 0.4.4) keeps the hub's session:
`hivemind_bus_client/protocol.py:handle_bus` 216–231 runs
`SessionManager.update(sess)` on every hub→client BUS message (226). That's the
behaviour Wiggins is missing.

Wiggins:

- `HiveProtocol.kt:74-91` (`utterance`) puts
  `context.toSessionJson(sessionId, siteId)` (87).
- `SessionContext.kt:22-41` builds that session from phone settings only.
- `Assistant.kt:183` and `Assistant.kt:309` (`sessionContext()`) supply those
  settings.
- `HiveProtocol.kt:150-162` (`onBus`) reads `speak` and `mycroft.mic.listen` but
  ignores `context.session`.

ovos-skill-alerts 0.1.24:

- `handle_create_alarm` (`__init__.py:291-310`) calls
  `get_response("alarm_ask_time", validator=validate_dt_or_delta, num_retries=0)`.
  When nothing comes back it says `error_no_time`.
- `validate_dt_or_delta` (`util/parse_utils.py:659-668`) →
  `extract_dt_or_duration` (578–609) uses ovos-date-parser and parses with the
  container's default timezone (`get_default_tz`). It ignores the session's
  `location.timezone`.
- I ran this inside the alerts image (`--network none`):

  | Input | `en-US` / `en-us` |
  |---|---|
  | `6:30` | 06:30 next day |
  | `six thirty` | 06:30 |
  | `Seven thirty in the morning` | 07:30 |
  | `10 minutes` | timedelta 600 s |
  | `6.30` | **None** |
  | `6.30 am` | **None** |

- Timer: `build_alert_from_intent` sets `stopwatch_mode` when a TIMER has no
  time (`util/parse_utils.py:251-254`). `handle_create_timer` (`__init__.py:357-372`)
  then speaks `confirm_timer_started` with `{"remaining": ""}`, giving "Timer
  for , starting now.".

## Recommended fix

### Wiggins (owner: Wiggins). This is the fix

Keep the hub's session and send it back:

1. In `HiveProtocol.onBus`, when a downlink BUS message's `context.session.session_id`
   equals our `sessionId`, store that `context.session` object as the latest hub
   session. Replace it each time, in arrival order, so that
   `get_response.disable`, `mycroft.skill.handler.complete` and
   `ovos.utterance.handled` clear the `response` state again.
2. When sending `recognizer_loop:utterance`, start from the stored hub session.
   Overwrite only the fields Wiggins owns: `session_id`, `site_id`, `lang`,
   `location`, `system_unit`, `time_format` and `date_format`. Keep everything
   else as the hub sent it, especially:
   - `utterance_states`, for example `{"ovos-skill-alerts.openvoiceos": "response"}`
   - `active_skills`, as `[[skill_id, timestamp], …]`, which converse needs for
     ordinary follow-ups
   - `context`, the adapt intent context.

   Echoing `pipeline`, `blacklisted_*` and `is_speaking` is harmless; the hub
   de-duplicates the blacklists. With no stored session (the first utterance
   after HELLO), send what Wiggins sends today.
3. Clear the stored session on a new connection, since each connection gets a
   new `session_id`.

   Don't keep a stale `response` state: converse would swallow the next normal
   question as a `get_response` answer
   (`converse_service.py:284-290, 351-360`).

An answer sent this way should look like:

```json
{"type": "recognizer_loop:utterance",
 "data": {"utterances": ["6:30"], "lang": "en-US"},
 "context": {"source": "Wiggins", "destination": "HiveMind", "platform": "Wiggins",
   "session": {"session_id": "<same>", "site_id": "phone", "lang": "en-US",
     "location": {…}, "system_unit": "imperial", "time_format": "half", "date_format": "MDY",
     "utterance_states": {"ovos-skill-alerts.openvoiceos": "response"},
     "active_skills": [["ovos-skill-alerts.openvoiceos", 1759596890.3]],
     "context": {…as received…}, "pipeline": […as received…]}}}
```

Also, so answers fit the 20 s window:

4. Send `recognizer_loop:record_begin` when the follow-up recognizer starts and
   `recognizer_loop:record_end` when it returns. Both are in the default
   `allowed_types`. Use the same session context. Each one restarts the
   skill's 20 s timer (`ovos.py:1868-1878`). Optionally send `record_begin`
   (or `recognizer_loop:audio_output_start`/`end`, also allowed) as soon as
   the `speak` with `expect_response` arrives, to cover the phone's TTS time.

Update `docs/hivemind-protocol.md` §11.1/§11.3 to match. They currently say to
send a fixed session.

### Hub (owner: the hub operator; optional, describe-only)

- **Timeout:** add `"skills": {"get_response_timeout": 45}` to the
  `mycroft.conf` the skill containers load;
  `self.config_core["skills"]["get_response_timeout"]` reads it. This gives
  voice satellites without `record_begin` support more time. It doesn't fix
  routing.
- **"6.30":** this is an upstream parser gap (ovos-date-parser 0.6.4 /
  ovos-skill-alerts `extract_dt_or_duration`): a period as the hour:minute
  separator isn't recognised. One option is an utterance transformer on
  ovos-core that rewrites `\b(\d{1,2})\.(\d{2})\b` to `\1:\2`. It would also
  rewrite real decimals like "2.50", so a narrower rule is safer. The other
  option is Wiggins doing the same rewrite on recognizer output when it answers
  an `expect_response` question. Worth an upstream issue on ovos-date-parser
  either way.
- **Timezone:** alarms are parsed in the timezone from the skill container's
  `mycroft.conf`, not the phone's session timezone. That's upstream
  skill behaviour (`get_default_tz`). It's harmless while the phone and hub
  share a zone. Note it for travel.
- **Timer:** no fix needed. Stopwatch mode is intended. The empty
  "Timer for , starting now." is a cosmetic dialog issue for upstream
  ovos-skill-alerts. To get a countdown, say a duration ("set a timer for
  10 minutes").

### Not the cause

- HiveMind agent bridge (b): it passes the client's session through faithfully.
  A hub patch that updates `client.sess` from downlink messages would also
  work, but it isn't upstream behaviour, and the reference client handles
  this on the client side.
- Lang tag `en-US` vs `en-us`.
- `destination`.

## Verifying after the fix

In Wiggins's downlink log, check that the `speak` carrying `expect_response`
has `context.session.utterance_states` with the alerts skill set to `response`.
Then answer "six thirty". ovos-core should log `Parsing utterance: ['six thirty']`
followed by `converse match (en-US): PipelineMatch(... skill_id='ovos-skill-alerts.openvoiceos' ...)`,
not by adapt, common_qa or fallback. The skill should then confirm the alarm.

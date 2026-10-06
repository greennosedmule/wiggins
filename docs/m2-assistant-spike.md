# M2 spike: assistant role and trigger

Answers the SPEC open question "Trigger: `VoiceInteractionService` or an `ACTION_ASSIST` activity?", plus the background-launch question that gates M6.
Researched 2026-10-04 against AOSP `android14-release`, `android15-release` and `android16-release`, Dicio `main` @ [`7315c37`](https://github.com/Stypox/dicio-android/tree/7315c37f241d6580d5d86499e46481f1b08fe936) (version 4.1, versionCode 18), and the Android 16 AOSP emulator (`sdk_phone64_x86_64`, `BE2A.250530.026.D1`).

Link shorthand: `fw/` = `https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/`, `perm/` = `https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android16-release/`, `settings/` = `https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android16-release/`. Line numbers are for android16-release unless noted.

## Recommendation

**Use an exported `ACTION_ASSIST` activity, not a `VoiceInteractionService` (VIS).** This is what Dicio does.

- An `ACTION_ASSIST` activity is enough to qualify for `android.app.role.ASSISTANT`. When the user picks Wiggins, the system writes `Settings.Secure.ASSISTANT` = Wiggins' activity, and the assist gesture, `KEYCODE_ASSIST` and long-press home all launch it with `startActivity`. Checked on the emulator: `KEYCODE_ASSIST` launched an `ACTION_ASSIST` holder from SystemUI.
- Wiggins needs an activity anyway, to call `startActivityForResult(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)`. A VIS would show a `VoiceInteractionSession` window and then still have to start a Wiggins activity to get a result.
- A VIS must declare a `recognitionService` to qualify. That pulls in a `RecognitionService` stub, and a careless one can become the system's default `SpeechRecognizer` (see Q3). An `ACTION_ASSIST` holder leaves the recognizer settings alone (also checked on the emulator).
- The one thing only a VIS gives is a persistent system binding with `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS` (Q5). For M6, an `ACTION_ASSIST` assistant can get a background-launch exemption another way: declare `SYSTEM_ALERT_WINDOW`, and the assistant role grants its app-op automatically. That is simpler than running a VIS.
- The role **cannot** be requested with `RoleManager.createRequestRoleIntent` (`requestable="false"`). Send the user to Settings with `Settings.ACTION_VOICE_INPUT_SETTINGS` ("Assist & voice input" → "Default digital assistant app"), falling back to `Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS`. Check the result with `RoleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)`.

Manifest snippet:

```xml
<!-- Only if M6 needs background launches: the assistant role grants this app-op
     automatically (see Q5). Leave it out for M2. -->
<!-- <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" /> -->

<queries>
    <!-- resolve the user's recognizer (FUTO Voice Input, ...) -->
    <intent>
        <action android:name="android.speech.action.RECOGNIZE_SPEECH" />
    </intent>
</queries>

<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTop"
    android:windowSoftInputMode="adjustResize">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
    <!-- Qualifies for android.app.role.ASSISTANT. exported + DEFAULT are both required. -->
    <intent-filter>
        <action android:name="android.intent.action.ASSIST" />
        <!-- optional, as in Dicio: "Start Voice Command" (e.g. headset voice button) -->
        <action android:name="android.intent.action.VOICE_COMMAND" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

Handling, copied from Dicio: treat `ACTION_ASSIST`/`ACTION_VOICE_COMMAND` in both `onCreate` and `onNewIntent` (singleTop) as "start listening". Debounce duplicates, because Dicio saw Android deliver the assist intent twice in a row and ignores repeats within 100 ms. If the extra `Intent.EXTRA_ASSIST_INPUT_HINT_KEYBOARD` is true (keyboard shortcut invocation, `PhoneWindowManager` [3670](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/policy/PhoneWindowManager.java#3670)), focus the text box instead of launching the recognizer.

## 1. How Dicio does it

Dicio uses **an `ACTION_ASSIST` activity only. It has no VoiceInteractionService and no session service.**

- [`AndroidManifest.xml` L42–67](https://github.com/Stypox/dicio-android/blob/7315c37f241d6580d5d86499e46481f1b08fe936/app/src/main/AndroidManifest.xml#L42-L67): `MainActivity` (`exported`, `singleTop`, `showOnLockScreen`) has an intent filter with `android.intent.action.ASSIST` + `android.intent.action.VOICE_COMMAND` + `DEFAULT`, plus the `com.android.systemui.action_assist_icon` meta-data (legacy SystemUI icon hint).
- Dicio also *provides* speech recognition to other apps: an `android.speech.action.RECOGNIZE_SPEECH` activity (`SttPopupActivity`, [L73–85](https://github.com/Stypox/dicio-android/blob/7315c37f241d6580d5d86499e46481f1b08fe936/app/src/main/AndroidManifest.xml#L73-L85)) and an `android.speech.RecognitionService` (`SttService`, [L87–103](https://github.com/Stypox/dicio-android/blob/7315c37f241d6580d5d86499e46481f1b08fe936/app/src/main/AndroidManifest.xml#L87-L103)). These are Dicio being a recognizer. They have nothing to do with the assistant role, and Wiggins doesn't need them.
- Invocation: [`MainActivity.kt` L58–68, L84–90, L111–118, L182–187](https://github.com/Stypox/dicio-android/blob/7315c37f241d6580d5d86499e46481f1b08fe936/app/src/main/kotlin/org/stypox/dicio/MainActivity.kt#L58-L68). `isAssistIntent()` matches `ACTION_ASSIST`/`ACTION_VOICE_COMMAND`, and `onAssistIntentReceived()` calls `sttInputDevice.tryLoad(listener)` (which starts listening), with a 100 ms backoff "since during testing Android would send the assist intent to the app twice in a row".
- With the "external popup" input setting ([`SttInputDeviceWrapper.kt` L110](https://github.com/Stypox/dicio-android/blob/7315c37f241d6580d5d86499e46481f1b08fe936/app/src/main/kotlin/org/stypox/dicio/di/SttInputDeviceWrapper.kt#L110)), listening means [`ExternalPopupInputDevice.kt` L100–113](https://github.com/Stypox/dicio-android/blob/7315c37f241d6580d5d86499e46481f1b08fe936/app/src/main/kotlin/org/stypox/dicio/io/input/external_popup/ExternalPopupInputDevice.kt#L100-L113). It builds `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` with `LANGUAGE_MODEL_FREE_FORM`, `EXTRA_LANGUAGE` (a locale with a country, because some recognizers need it) and `EXTRA_PROMPT`, checks it with `resolveActivity`, launches it for a result, and reads `EXTRA_RESULTS`/`EXTRA_CONFIDENCE_SCORES` ([L147–150](https://github.com/Stypox/dicio-android/blob/7315c37f241d6580d5d86499e46481f1b08fe936/app/src/main/kotlin/org/stypox/dicio/io/input/external_popup/ExternalPopupInputDevice.kt#L147-L150)). This is exactly Wiggins' pipeline.
- Dicio has no `RoleManager` or settings deep-link code. Users set it through Settings → Default apps.

## 2. AOSP rules for the assistant role (Android 14–16)

Role definition: [`perm/PermissionController/res/xml/roles.xml` L105–162](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android16-release/PermissionController/res/xml/roles.xml#105). It is the same in android14-release, apart from per-SDK permission additions.

- `behavior="AssistantRoleBehavior"`, `exclusive="true"`, `showNone="true"`, **`requestable="false"`** (L115).
- `<permissions>` grants SMS, `READ_CALL_LOG`, etc. (L144–158). **`<app-op-permission name="android.permission.SYSTEM_ALERT_WINDOW"/>`** (L159–161) matters for Q5.

Qualification: [`AssistantRoleBehavior.java` L94–196](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android16-release/PermissionController/role-controller/java/com/android/role/controller/behavior/AssistantRoleBehavior.java#94). A package qualifies through **either** of these:

1. **VIS** (skipped on low-RAM devices): a service for `android.service.voice.VoiceInteractionService` that is protected by `BIND_VOICE_INTERACTION` and whose `android.voice_interaction` meta-data has **`sessionService`, `recognitionService` and `supportsAssist="true"`** all set (L151–196).
2. **Activity**: any activity matching `Intent.ACTION_ASSIST` with `MATCH_DEFAULT_ONLY` (so it needs `category.DEFAULT`) that is **`exported`** (L128–146).

Requesting the role:

- `RoleManager.createRequestRoleIntent(ROLE_ASSISTANT)` does nothing. [`RequestRoleActivity.java` L125–131](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android16-release/PermissionController/src/com/android/permissioncontroller/role/ui/RequestRoleActivity.java#125) logs "Role is not requestable", reports IGNORED and finishes (the caller gets `RESULT_CANCELED`).
- `Settings.ACTION_VOICE_INPUT_SETTINGS` (`android.settings.VOICE_INPUT_SETTINGS`) opens Settings' `ManageAssistActivity` ([`settings/AndroidManifest.xml` L1249–1260](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android16-release/AndroidManifest.xml#1249)). The screen's first entry is "Default digital assistant app" ([`res/xml/manage_assist.xml`](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android16-release/res/xml/manage_assist.xml)). This is the best deep link; it resolved on the emulator.
- `Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS` opens PermissionController's `DefaultAppListActivity` (the "Default apps" list) ([`perm/PermissionController/AndroidManifest.xml` L440–448](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android16-release/PermissionController/AndroidManifest.xml#440)). Use it as the fallback.
- `Intent.ACTION_MANAGE_DEFAULT_APP` (the direct role page) is guarded by `android.permission.MANAGE_ROLE_HOLDERS` (L450–458), so a normal app can't use it.

## 3. VIS side-effects on the system recognizer

- `VoiceInteractionServiceInfo` requires `recognitionService`. Parsing fails with "No recognitionService specified" if it is null ([`fw/core/java/android/service/voice/VoiceInteractionServiceInfo.java` L143–150](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/service/voice/VoiceInteractionServiceInfo.java#143)). The role behavior checks the same (above).
  - On Android 12+ only the attribute's presence is checked. The log text in VIMS says "Also make sure that this is a valid RecognitionService when running on Android 11 or earlier" ([`VoiceInteractionManagerService.java` L2471–2477](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerService.java#2471), [L2536–2544](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerService.java#2536)).
- **Becoming the assistant does not change `VOICE_RECOGNITION_SERVICE`.** The `RoleObserver` in VIMS ([L2398–2504](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerService.java#2398)) writes only `Settings.Secure.ASSISTANT` and `VOICE_INTERACTION_SERVICE`:
  - For a VIS holder, both are set to the VIS component (L2459–2484).
  - For an activity holder, `ASSISTANT` is set to the activity and `VOICE_INTERACTION_SERVICE` to `""` (L2487–2502).
  - This is the same in android14-release (`RoleObserver` at L2175). The old Settings "Voice input" picker that used to copy a VIS's `recognitionService` into `VOICE_RECOGNITION_SERVICE` is gone. `settings/src/com/android/settings/applications/assist/` contains no `DefaultVoiceInputPicker` in 14, 15 or 16.
  - Emulator check: switching the role holder changed `assistant` but left `voice_recognition_service` untouched.
- **But a declared `RecognitionService` can still become the system default.** VIMS fills an empty `VOICE_RECOGNITION_SERVICE` with the first available recognizer whenever packages appear, preferring `config_systemSpeechRecognizer` (`onSomePackagesChanged` [L2642–2653](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerService.java#2642), `initRecognizer` [L703–708](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerService.java#703), `findAvailRecognizer` [L909–940](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerService.java#909)).
  - "Available" means any service that resolves `android.speech.RecognitionService` ([`RecognitionServiceInfo.java` L64–66](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/voiceinteraction/java/com/android/server/voiceinteraction/RecognitionServiceInfo.java#64)) and isn't marked `android:selectableAsDefault="false"` (L117–120).
  - Seen on the emulator: before FUTO was installed, `voice_recognition_service` was `null`. Installing FUTO Voice Input set it to `org.futo.voiceinput/.DummyService`, a stub with category `TEST`.
  - So if Wiggins shipped a VIS with an intent-filtered stub recognizer on a device with no recognizer service (GrapheneOS ships none), every `SpeechRecognizer.createSpeechRecognizer()` user would bind Wiggins' stub. `SpeechRecognizerImpl` reads `VOICE_RECOGNITION_SERVICE` ([`fw/core/java/android/speech/SpeechRecognizerImpl.java` L534–538](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/speech/SpeechRecognizerImpl.java#534)).
  - Mitigation if a VIS is ever used: point `recognitionService` at a non-exported stub with **no** `android.speech.RecognitionService` intent filter, or give it `<recognition-service android:selectableAsDefault="false"/>` meta-data.
- **`RecognizerIntent.ACTION_RECOGNIZE_SPEECH` isn't affected either way.** It is an activity intent resolved by `PackageManager` (one handler, or a chooser/preferred activity), independent of `VOICE_RECOGNITION_SERVICE`. Wiggins needs a `<queries>` entry for it (targetSdk 30+ package visibility) so that `resolveActivity` works.

## 4. How an `ACTION_ASSIST`-only assistant is launched

All entry points converge on SystemUI `AssistManager.startAssist()`:

- **Assist gesture (gesture-nav corner swipe):**
  - SystemUI only advertises the gesture when `AssistUtils.getAssistComponentForUser() != null` (it reads `Settings.Secure.ASSISTANT`, [`AssistUtils.java` L285–293](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/com/android/internal/app/AssistUtils.java#285)), `ASSIST_TOUCH_GESTURE_ENABLED` is on, and the nav mode is gestural ([`NavBarHelper.java` L485–510](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/packages/SystemUI/src/com/android/systemui/navigationbar/NavBarHelper.java#485)).
  - Launcher (Quickstep) detects the swipe and calls SystemUI's `startAssistant`.
  - Because the holder is an activity, the `RoleObserver` set `ASSISTANT` to it, so the gesture works.
- **`KEYCODE_ASSIST` / long-press home / keyboard shortcut:** `PhoneWindowManager.launchAssistAction()` → `SearchManager.launchAssist()` → SystemUI ([`PhoneWindowManager.java` L4830–4860](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/policy/PhoneWindowManager.java#4830), `KEYCODE_ASSIST` at [L5586](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/policy/PhoneWindowManager.java#5586)).
- **SystemUI → activity:**
  - [`AssistManager.java` L286–326, L390–441](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/packages/SystemUI/src/com/android/systemui/assist/AssistManager.java#286): if `ASSISTANT` equals the active VIS, it calls `showSessionForActiveService`. Otherwise `startAssistActivity()` builds `SearchManager.getAssistIntent()` (`ACTION_ASSIST`), sets the component, adds the invocation extras, adds `FLAG_ACTIVITY_NEW_TASK`, and calls `startActivityAsUser`.
  - Exceptions: no launch while the device is unprovisioned or in lock-task mode, and invocation types the launcher has claimed via `setAssistantOverridesRequested` go to the launcher instead. AOSP Launcher3 doesn't claim any; Pixel Launcher does for Google features, but GrapheneOS uses Launcher3.
  - **Emulator check:** with Dicio as the role holder, `input keyevent KEYCODE_ASSIST` logged `START u0 {act=android.intent.action.ASSIST flg=0x10000000 cmp=org.stypox.dicio/.MainActivity (has extras)} ... from uid 10124 (BAL_ALLOW_NON_APP_VISIBLE_WINDOW)`. The launch comes from SystemUI, so background-launch limits never apply to the assist trigger itself.
- **Power-button long-press:**
  - `LONG_PRESS_POWER_ASSISTANT` (5) also calls `launchAssistAction` ([L1551–1557](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/policy/PhoneWindowManager.java#1551)), so it would open an `ACTION_ASSIST` activity just as well.
  - Whether long-press does that depends on `config_longPressOnPowerBehavior`, overridable by `Settings.Global.POWER_BUTTON_LONG_PRESS` (L3137–3140).
  - **GrapheneOS doesn't offer "hold power for assistant" in Settings.** It is an open feature request, [GrapheneOS/os-issue-tracker#7035](https://github.com/GrapheneOS/os-issue-tracker/issues/7035) (Jan 2026, no developer response). An adb override (`settings put global power_button_long_press 5`) should work per the code above, but this is unverified on GrapheneOS (see open items).
- **GrapheneOS otherwise:** it uses AOSP SystemUI, Launcher3 and PermissionController. I found no GrapheneOS-specific changes to the assistant role or assist gesture, and community reports describe Dicio working as the default assistant with FUTO Voice Input. Set it under Settings → Apps → Default apps → Digital assistant app ([usage guide](https://grapheneos.org/usage)). The corner-swipe toggle lives under gesture navigation settings ("Swipe to invoke assistant" = `ASSIST_TOUCH_GESTURE_ENABLED`).

## 5. Background activity launch (BAL) and M6

There is **no exemption for "holds `ROLE_ASSISTANT`" as such.** The caller-side chain in [`BackgroundActivityStartController.java` L995–1013](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/wm/BackgroundActivityStartController.java#995) is: visible window, non-app visible window, foreground process, allowlisted UID (root/system/NFC), allowlisted component (home, active IME, persistent system, recents, device owner, companion app; L1066–1104), `START_ACTIVITIES_FROM_BACKGROUND`, **`SYSTEM_ALERT_WINDOW`**, a system-exempt app-op, and **process tokens**. Assistant and voice interaction appear nowhere. Two routes still reach an assistant:

1. **VIS route (token).** While a VIS is the active interactor, system_server keeps it bound with `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS` ([`VoiceInteractionManagerServiceImpl.java` L1057–1066](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerServiceImpl.java#1057); same flags in android14-release L989–995; the session binds also carry it, [`VoiceInteractionSessionConnection.java` L247–251, L283–288](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionSessionConnection.java#247)). The chain from there:
   - The flag is permission-checked against the binder, which is system ([`ActiveServices.java` L4150–4154](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/am/ActiveServices.java#4150)).
   - It marks the `ServiceRecord` (L4282–4284), which hands the host process an `ALLOW_BAL` token ([`ServiceRecord.java` L1465–1473](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/am/ServiceRecord.java#1465)).
   - `BackgroundLaunchProcessController` then returns `BAL_ALLOW_TOKEN` ([L120–128, L186–229](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/wm/BackgroundLaunchProcessController.java#120)). The only callback is the notification-trampoline one, which allows any token that isn't its own ([`NotificationManagerService.java` L14567–14587](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/NotificationManagerService.java#14567)).
   - Any process of the same UID counts (`checkProcessAllowsBal` L1253–1281).
   - So **an active VIS exempts all of Wiggins' processes, at all times**, at the cost of a permanently bound VIS process.
2. **SAW route (works with `ACTION_ASSIST`).** The assistant role grants the `SYSTEM_ALERT_WINDOW` app-op (`roles.xml` L159–161), but **only if the app declares `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>`** ([`AppOpPermissions.java` L51–75](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android16-release/PermissionController/role-controller/java/com/android/role/controller/model/AppOpPermissions.java#51), the `requestedPermissions` check at L58–60).
   - A granted SAW gives `BAL_ALLOW_SAW_PERMISSION` (`mCheckCallerHasSawPermission` L1114–1121; `ActivityTaskManagerService.hasSystemAlertWindowPermission` [L1096–1105](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/wm/ActivityTaskManagerService.java#1096)).
   - The same check exists in android14-release.
   - Unlike FGS starts, BAL from SAW has no "visible overlay" requirement in 16.
   - The user could also grant "Display over other apps" by hand.
   - Downside: Wiggins would declare a scary-sounding permission it never uses for overlays.

Both routes also pass the Activity Security Model check for new tasks. `BAL_ALLOW_TOKEN` and `BAL_ALLOW_SAW_PERMISSION` are exempt when `FLAG_ACTIVITY_NEW_TASK` is set (L1303–1318), so launch Waggle intents with `NEW_TASK`.

What doesn't help:

- **A `shortService` (or any) foreground service gives no BAL exemption.** An FGS is not in the chain above, matching the [background-starts docs](https://developer.android.com/guide/components/activities/background-starts).
- **The UnifiedPush distributor's binding doesn't help either.** The [UnifiedPush Android spec](https://unifiedpush.org/developers/spec/android/) (AND_3.1.0) has the distributor raise the app to foreground importance for 5 s, or bind its `RAISE_TO_FOREGROUND` service. That lets Wiggins *start an FGS*, not an activity. "Bound by foreground UID" only counts when the binder has a visible window, and for targetSdk 34+ it also needs `BIND_ALLOW_ACTIVITY_STARTS` ([`BackgroundLaunchProcessController.java` L69–73, L267–279](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/wm/BackgroundLaunchProcessController.java#267)).

**Meaning for M6:** M6 can go ahead with the `ACTION_ASSIST` design. The UnifiedPush message gives the right to start a `shortService` FGS that holds the websocket, and then:

- **(a)** declare `SYSTEM_ALERT_WINDOW` so the assistant role makes `startActivity` (with `NEW_TASK`) legal from the background; or
- **(b)** without SAW, post a high-priority notification whose tap launches the intent. That always works, but needs a tap (similar to the ask card).

A VIS is not needed for M6. Keep it in reserve only if (a) turns out to be blocked on GrapheneOS. All of this needs a device test (below) before M6 is committed.

## Trade-offs at a glance

| | `ACTION_ASSIST` activity | `VoiceInteractionService` |
| --- | --- | --- |
| Qualifies for role | yes (exported + DEFAULT) | yes (needs session service, `recognitionService`, `supportsAssist`) |
| Assist gesture / `KEYCODE_ASSIST` | starts the activity | shows a `VoiceInteractionSession`; Wiggins must then `startAssistantActivity` to get an activity for `startActivityForResult` |
| Effect on system recognizer | none | none from the role, but its stub `RecognitionService` can become the default if intent-filtered |
| Process cost | none until invoked | VIS bound permanently with `BIND_FOREGROUND_SERVICE` |
| Background launches (M6) | via the role's SAW app-op, if `SYSTEM_ALERT_WINDOW` is declared | always (`BAL_ALLOW_TOKEN`) |
| Lock-screen / hotword / assist-structure APIs | no | yes (unused by Wiggins) |
| Reference | Dicio | none among FOSS assistants checked |

## Recognizer on the emulator

- **FUTO Voice Input** `org.futo.voiceinput` 1.3.7-3 (versionCode 32), from the official standalone APK `https://voiceinput.futo.org/VoiceInput/standalone.apk`.
  - sha256 `9cb4c5f3a47c2649b7ee7b5a8c3667e58eaa73744ce73e0459f2a35aa63f7091`; a copy is in the session scratchpad.
  - Native libs for arm64-v8a, armeabi-v7a, x86 and x86_64, with a bundled English `tiny_en` model, so **no model download is needed** for English.
  - Also published in FUTO's own F-Droid repo (`https://app.futo.org/fdroid/repo/`, which listed 1.3.7-2); it isn't on f-droid.org.
  - License: **FUTO Source First License 1.0**. The source is available, but use is limited to non-commercial purposes and it isn't OSI open source. That's fine for testing; Wiggins only invokes it by intent.
- Installed on emulator-5554, with `RECORD_AUDIO` and `POST_NOTIFICATIONS` granted via `pm grant`. `cmd package query-activities -a android.speech.action.RECOGNIZE_SPEECH` → `org.futo.voiceinput/.RecognizeActivity` (the only handler).
- `am start -a android.speech.action.RECOGNIZE_SPEECH` opens FUTO's listening popup, which reports "No audio detected, is your microphone blocked?" because the emulator's virtual mic isn't fed from the host by default.
- FUTO's first-run wizard (enable its IME) is only needed for keyboard integration, not for `RECOGNIZE_SPEECH`. It was skipped.
- Side-effect: FUTO's `DummyService` became `voice_recognition_service` (see Q3).
- **Manual step remaining:** emulator Extended controls → Microphone → enable "Virtual microphone uses host audio input" (resets on each emulator start).

## Open items (device checks, not run)

The coordinator was using the emulator for end-to-end tests, so these weren't run. All assume Wiggins is the assistant role holder.

1. **Gesture and keys on GrapheneOS** (Pixel, gesture nav): swipe up from a bottom corner and confirm `MainActivity` gets `ACTION_ASSIST`. Watch with `adb logcat -s ActivityTaskManager | grep ASSIST` and expect `START ... act=android.intent.action.ASSIST ... cmp=com.mulesipstea.wiggins/.MainActivity`. Repeat with `adb shell input keyevent KEYCODE_ASSIST`.
2. **Power long-press override on GrapheneOS:**
   - `adb shell settings get global power_button_long_press` (note the old value), then `adb shell settings put global power_button_long_press 5`, then long-press power and expect Wiggins.
   - Restore with `settings put global power_button_long_press <old>`, or `settings delete global power_button_long_press` if it was `null`.
3. **Role grants SAW** (after adding `SYSTEM_ALERT_WINDOW` to the manifest and re-selecting Wiggins as assistant):
   - `adb shell appops get com.mulesipstea.wiggins SYSTEM_ALERT_WINDOW` → expect `allow`.
   - `adb shell dumpsys role | grep -A3 ASSISTANT` → holder `com.mulesipstea.wiggins`.
4. **BAL from background (decides M6).** Add a debug-only exported receiver to Wiggins that calls `startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(FLAG_ACTIVITY_NEW_TASK))` after a 15 s delay (via a `shortService` FGS, to mirror M6). Then:
   - `adb shell am broadcast -n com.mulesipstea.wiggins/.debug.BalTestReceiver`, press Home, and wait.
   - Check `adb logcat | grep -E "Background activity launch blocked|BAL_ALLOW"`. Expect `BAL_ALLOW_SAW_PERMISSION` with SAW, and "blocked" without it.
   - Run once with SAW granted and once after `adb shell appops set com.mulesipstea.wiggins SYSTEM_ALERT_WINDOW default`, then re-grant with `appops set ... allow` or by re-selecting the role.
5. **Recognizer settings untouched:** before and after selecting Wiggins as assistant, run `adb shell settings get secure voice_recognition_service` (unchanged) and `adb shell settings get secure assistant` (= `com.mulesipstea.wiggins/.MainActivity`).
6. **`createRequestRoleIntent` behavior:** confirm it returns `RESULT_CANCELED` immediately (logcat tag `RequestRoleActivity`: "Role is not requestable"), so the UI goes straight to `ACTION_VOICE_INPUT_SETTINGS`.

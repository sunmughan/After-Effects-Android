# Firebase registration — EffectCraft for Android

> Status (2026-11-07): **NOT REGISTERED — blocked, no authenticated route.**
> This VM has no `firebase` CLI, no `gcloud`, and no Google application
> credentials (`GOOGLE_APPLICATION_CREDENTIALS` unset, no
> `~/.config/gcloud`), so no agent here can register the app or download
> a `google-services.json`. Nothing was faked: no placeholder file was
> created and no fingerprint was invented. The console steps below are
> the whole remaining work and take minutes once the release keystore
> exists. This file is updated the moment registration lands.

## What is being registered

- Firebase project: **Codeair Apps** (select it by name in the Firebase
  console; the project ID is whatever that project carries — it is not
  recorded here because it was never verified from a live console).
- Android app package name: `com.codeair.effectcraft`
- App nickname (suggested): `EffectCraft Android`

## Fingerprints

### Debug keystore — recorded (this build VM)

From the debug keystore on this machine
(`~/.android/debug.keystore`, alias `androiddebugkey`, created
2026-10-12, certificate `CN=Android Debug, O=Android, C=US`, valid to
2056), read with `keytool -list -v`:

- SHA-1: `45:DC:6B:F8:5E:9E:1B:88:62:F1:10:B1:86:BA:36:AF:30:9F:D3:C1`
- SHA-256: `9D:DA:A5:D2:6D:B4:1F:4B:21:C4:19:D4:D1:E1:D5:35:F9:8F:71:D1:16:FF:50:42:E6:9C:FD:A0:BE:24:CD:7D`

Caveats, read before using these:

- These fingerprints match **only** APKs signed with this exact
  keystore (local builds on this machine). GitHub CI runners generate
  their own ephemeral debug keystore on every run, so a CI-built debug
  APK will **not** match them — that is expected, not a mismatch.
- The owner's own machine has its own debug keystore with different
  fingerprints. To print them on any machine:
  `keytool -list -v -keystore ~/.android/debug.keystore -storepass android -alias androiddebugkey`
  (macOS/Linux; on Windows the keystore is at
  `%USERPROFILE%\.android\debug.keystore`).
- Register the debug fingerprint if the owner installs debug builds
  from this machine. It is optional for the release build; the release
  fingerprint below is the one that matters for the shipped app.

### Release keystore — BLOCKED, cannot exist yet

The release keystore does not exist: the four release secrets
(`EFFECTCRAFT_RELEASE_KEYSTORE_BASE64`,
`EFFECTCRAFT_RELEASE_KEYSTORE_PASSWORD`,
`EFFECTCRAFT_RELEASE_KEY_ALIAS`, `EFFECTCRAFT_RELEASE_KEY_PASSWORD`)
do not exist and only the owner can supply them (see
`docs/M7_RELEASE_BUILD.md` §3). Until then there is no release
certificate and no release fingerprint to record — any value written
here before that would be invented.

Once the secrets exist, the authoritative fingerprints come from the
release workflow itself: its "Print signing certificate fingerprints"
step prints the SHA-1 and SHA-256 of the signer actually present in
the built APK. Copy those values exactly as printed into the Firebase
console. The equivalent local command, run against the real keystore
file once the owner supplies it (passwords typed at the prompt, never
pasted into chat or a file):

```bash
keytool -list -v -keystore /path/to/effectcraft-release.keystore -alias <key alias>
```

## Console steps (owner, minutes)

1. Open the [Firebase console](https://console.firebase.google.com/)
   and select the **Codeair Apps** project.
2. Project settings (gear icon) → **Your apps** → **Add app** →
   **Android** (the `</>` icon).
3. Enter Android package name `com.codeair.effectcraft`, nickname
   `EffectCraft Android`.
4. Add the **debug** SHA-1 above (and SHA-256) under "Debug signing
   certificate SHA-1". Add the **release** SHA-1/SHA-256 from the
   release workflow output once it exists — a release build without
   its fingerprint registered cannot use Firebase/Google sign-in.
5. Download `google-services.json` and place it at
   `app/google-services.json` in the repository (replacing nothing —
   no such file exists yet).
6. Verify the file before committing it (it must parse, and the
   package must match):
   ```bash
   python3 -c "import json; d=json.load(open('app/google-services.json')); print([c['client_info']['android_client_info']['package_name'] for c in d['client']])"
   ```
   Expected output: `['com.codeair.effectcraft']`.
7. Commit the real file. It contains no secret: the values inside are
   client identifiers (API key, project ID) that Firebase restricts by
   package name + SHA-1. Never commit a hand-written or placeholder
   file — only the file downloaded from the console.
8. Rebuild. The `google-services` Gradle plugin (4.4.4) is wired to
   apply automatically when `app/google-services.json` exists (see
   `app/build.gradle.kts`); with no file present the build works
   exactly as before. On first launch of a registered build,
   `FirebaseInit` logs `Firebase configured: FCM auto-init enabled`
   in logcat and fetches the FCM token; without the file it logs
   `Firebase not configured (no google-services.json)` and the editor
   runs fully offline.

## What FCM does in this app (no backend)

- `FirebaseInit.ensureInitialized` (called from `MainActivity`) is the
  only entry point: it no-ops safely with no config, and enables FCM
  auto-init plus a one-time token fetch when configured.
- `EffectCraftMessagingService` stores token refreshes and posts every
  received message as a visible notification through
  `PushMessageMapper` (pure, unit-tested); there is no silent drop.
- There are no topic subscriptions and no server/backend in this
  app: nothing sends messages yet. Registration only makes the app
  *receivable*; a sender is a separate, owner-decided step.

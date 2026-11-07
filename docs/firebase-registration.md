# Firebase registration — EffectCraft for Android

> Status (2026-11-07): **REGISTERED.**
> Firebase project: **Codeair Apps** (project ID `codeair-apps`,
> project number `249079131159`). Android app `com.codeair.effectcraft`
> ("EffectCraft", App ID
> `1:249079131159:android:45da270abd5468114a4052`) registered in the
> console 2026-11-07; both SHA-1 fingerprints below added;
> `app/google-services.json` is the file downloaded from the console
> (verified: package + App ID + both certificate hashes match).

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

- These fingerprints match APKs signed with this exact keystore. The
  keystore is committed at `app/debug.keystore` and wired as the debug
  signing config in `app/build.gradle.kts`, so CI debug APKs are signed
  with it too (before that change, CI runners generated their own
  ephemeral debug keystore on every run and never matched).
- The owner's own machine has its own debug keystore with different
  fingerprints. To print them on any machine:
  `keytool -list -v -keystore ~/.android/debug.keystore -storepass android -alias androiddebugkey`
  (macOS/Linux; on Windows the keystore is at
  `%USERPROFILE%\.android\debug.keystore`).
- Register the debug fingerprint if the owner installs debug builds
  from this machine. It is optional for the release build; the release
  fingerprint below is the one that matters for the shipped app.

### Release keystore — created 2026-11-07, secrets set

The release keystore was generated 2026-11-07 (alias `effectcraft`,
RSA 2048, valid to 2056-10-30) and its four secrets are set in GitHub
Actions (`EFFECTCRAFT_RELEASE_KEYSTORE_BASE64`,
`EFFECTCRAFT_RELEASE_KEYSTORE_PASSWORD`,
`EFFECTCRAFT_RELEASE_KEY_ALIAS`, `EFFECTCRAFT_RELEASE_KEY_PASSWORD`;
see `docs/M7_RELEASE_BUILD.md` §3). Passwords live only in those
secrets, never in a file.

- SHA-1: `32:79:28:E2:3A:0A:7D:F6:39:C8:E6:DF:41:C2:B7:53:04:89:EA:16`
- SHA-256: `01:1C:F8:A1:65:4D:AC:12:69:D1:F8:94:65:88:BD:BC:46:3E:67:5F:23:73:91:7A:67:84:A4:15:5C:D1:AA:A1`

Both fingerprints are registered on the Firebase app. The release
workflow's "Print signing certificate fingerprints" step prints the
SHA-1 and SHA-256 of the signer actually present in the built APK;
those printed values must equal the ones above before any release is
treated as genuine.

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

# Changelog

## Unreleased

- `ConsentSheet` uses the banner's `bannerBgColor` and `bannerTextColor` from the dashboard when present (new optional `BannerConfig` fields), and keeps the app's Material theme when they are absent. Invalid hex colors, including the brand color, are now ignored.
- IAB TCF in-app support. When the property has TCF on, the SDK writes the `IABTCF_*` keys to the app's default `SharedPreferences` and shows ConsentIQ's hosted TCF screens full-screen in a web view, in place of the category sheet, for visitors where GDPR applies. Where it does not apply, the SDK writes `IABTCF_gdprApplies = 0` and keeps the sheet.
- `ConsentIQ.showSheet()` re-opens the TCF screens when TCF is active. `ConsentIQ.showTcfPreferences()` does the same and returns `false` when TCF is not active.
- New `ConsentIQState.isTcfActive` and `ConsentIQ.isTcfActive`.
- New `onError(ErrorInfo)` callback on `ConsentIQConfig`. If the TCF screens cannot run, it fires and the SDK falls back to the category sheet; after a transient failure the next open tries the TCF screens again.
- `PropertyConfig` has a new optional `tcf` field (`TcfSettings`).
- `resetConsent()`, and consent the server reports as expired, also clear the stored TCF decision and remove its `IABTCF_*` keys, keeping `IABTCF_CmpSdkID`, `IABTCF_CmpSdkVersion` and `IABTCF_gdprApplies`. Keys are only removed while `IABTCF_CmpSdkID` is still the one this SDK wrote.
- If TCF is later turned off for the property, the next start removes every `IABTCF_*` key this SDK wrote, again only while `IABTCF_CmpSdkID` is still its own.

## 1.1.0

- Properties with "Require CAPTCHA" on now accept consent from the SDK. Before each submit, the SDK runs Cloudflare Turnstile invisibly in a hidden web view and sends the token with the consent. If the check fails or needs user interaction, nothing is sent, the local state is kept, and the SDK logs `[ConsentIQ] Captcha check failed; consent not submitted`.
- `FormConsent` runs the same check, using the web view that `ConsentIQ.init` sets up.
- `ConsentSubmission` has a new optional `captchaToken` field.
- The startup warning that mobile SDKs cannot satisfy a captcha is gone.
- The core now depends on `androidx.webkit` 1.17.1.

## 1.0.1

- Debug logs no longer include the subject id.
- The example app turns on debug logging in debuggable builds only.
- README: a caller-supplied `subjectId` must be random and unguessable.

## 1.0.0

Initial release.

- `ai.iqworks:consentiq-android`: `ConsentIQ` singleton with `StateFlow` state, `ConsentIQApiClient` for the public config, consent, submit and verify endpoints, `SharedPreferences` storage for the subject id and consent cache, `FormConsent` for marketing forms.
- `ai.iqworks:consentiq-android-compose`: `ConsentSheet` (Material 3 modal bottom sheet, server-provided labels, RTL layout, brand color) and `ConsentGate`.
- Example app under `example/`.

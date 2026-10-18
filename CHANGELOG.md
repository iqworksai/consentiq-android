# Changelog

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

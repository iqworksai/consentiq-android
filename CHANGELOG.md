# Changelog

## 1.0.1

- Debug logs no longer include the subject id.
- The example app turns on debug logging in debuggable builds only.
- README: a caller-supplied `subjectId` must be random and unguessable.

## 1.0.0

Initial release.

- `ai.iqworks:consentiq-android`: `ConsentIQ` singleton with `StateFlow` state, `ConsentIQApiClient` for the public config, consent, submit and verify endpoints, `SharedPreferences` storage for the subject id and consent cache, `FormConsent` for marketing forms.
- `ai.iqworks:consentiq-android-compose`: `ConsentSheet` (Material 3 modal bottom sheet, server-provided labels, RTL layout, brand color) and `ConsentGate`.
- Example app under `example/`.

# ConsentIQ Android SDK

The Android SDK for [ConsentIQ](https://consent.iqworks.ai), the IQWorks consent management platform. It fetches your property's configuration, shows a consent sheet on first launch, records the visitor's choices against a pseudonymous subject id, and lets your app gate features on consent.

Two artifacts are published to Maven Central:

- `ai.iqworks:consentiq-android`: the client, models, storage and state machine. Depends on `kotlinx-coroutines-android` and `androidx.webkit`.
- `ai.iqworks:consentiq-android-compose`: a Jetpack Compose `ConsentSheet` and `ConsentGate` on top of the core. Pulls the core in transitively.

Requirements: minSdk 24, Kotlin 2.x, Jetpack Compose with Material 3 for the Compose module.

## Install

With Compose (recommended):

```kotlin
dependencies {
    implementation("ai.iqworks:consentiq-android-compose:1.1.0")
}
```

Core only, if you render your own consent UI:

```kotlin
dependencies {
    implementation("ai.iqworks:consentiq-android:1.1.0")
}
```

The core declares the `INTERNET` permission in its manifest, so nothing else is needed.

## Quick start

1. In the ConsentIQ dashboard, create a property with platform "Android" and copy its public key.
2. Initialize the SDK once in your `Application`:

```kotlin
import ai.iqworks.consentiq.ConsentIQ
import ai.iqworks.consentiq.ConsentIQConfig

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ConsentIQ.init(
            this,
            ConsentIQConfig(
                propertyKey = "YOUR_ANDROID_PROPERTY_KEY",
                onConsentChange = { consent, marketing -> analytics.setEnabled(consent["analytics"] == true) },
            ),
        )
    }
}
```

3. Place `ConsentSheet()` somewhere near the root of your Compose tree. It renders a Material 3 modal bottom sheet whenever the SDK asks for consent and stays out of the way otherwise:

```kotlin
import ai.iqworks.consentiq.compose.ConsentGate
import ai.iqworks.consentiq.compose.ConsentSheet

setContent {
    MaterialTheme {
        MyApp()
        ConsentSheet()
    }
}

@Composable
fun AnalyticsBanner() {
    ConsentGate(category = "analytics") {
        Text("Analytics is on")
    }
}
```

4. Reopen the sheet from a settings screen with `ConsentIQ.showSheet()`.

On the first launch the SDK generates a subject id, loads the property configuration, finds no consent, and shows the sheet. On later launches it restores the consent stored on the server (or the local cache when the device is offline) and shows nothing.

The `example/` module is a complete app wired this way.

## One property per platform

A ConsentIQ property belongs to exactly one platform. If you ship the same product on Android and iOS, create two properties in the dashboard, one per platform, and give each app its own key: this SDK takes the Android key, and the [ConsentIQ iOS SDK](https://github.com/iqworksai/consentiq-ios) takes the iOS key. Do not share one key across platforms; consent records, regulations and reports are kept per property.

## API summary

Everything is reached through the `ConsentIQ` singleton.

State is exposed as `ConsentIQ.state: StateFlow<ConsentIQState>` with these fields:

| Field | Meaning |
| --- | --- |
| `consent` | Map of category code to granted, for example `{"necessary": true, "analytics": false}` |
| `marketingConsent` | Map of marketing channel code to granted, for example `{"email": true}` |
| `config` | The property configuration once loaded, otherwise `null` |
| `isLoading` | `true` until initialization finishes, successfully or not |
| `isSheetVisible` | Whether the consent sheet should be shown |
| `language` | The language used for the sheet |
| `regulation` | The regulation the server resolved for this visitor, for example `DPDPA`, `GDPR` or `null` |
| `isTcfActive` | `true` when the IAB TCF screens replace the sheet for this visitor. See [IAB TCF](#iab-tcf) |

Operations:

| Call | What it does |
| --- | --- |
| `init(context, config)` | Starts the SDK. Calling it again with an equal config is a no-op |
| `hasConsent(category)` | `true` when the category is granted |
| `hasMarketingConsent(channel)` | `true` when the marketing channel is granted |
| `acceptAll()` | Grants every enabled category, closes the sheet, records the consent |
| `rejectAll()` | Grants only required categories, closes the sheet, records the consent |
| `savePreferences(map)` | Records a custom selection from the sheet's category list |
| `updateConsent(partial)` | Merges changes into the consent map and records them |
| `updateMarketingConsent(partial)` | Merges changes into the marketing map and records them |
| `showSheet()` | Opens the sheet and fires `onOpen`. When TCF is active, opens the TCF screens instead |
| `showTcfPreferences()` | Re-opens the TCF screens. Returns `false`, and does nothing, when TCF is not active |
| `hideSheet()` | Closes the sheet from your code. Fires nothing |
| `dismissSheet()` | Closes the sheet on behalf of the visitor (back button, swipe). Fires `onClose` |
| `resetConsent()` | Clears the consent map, the cache and the subject id, then generates a fresh subject id |
| `subjectId` | The subject id in use, or `null` before initialization |

`acceptAll`, `rejectAll`, `savePreferences`, `updateConsent` and `updateMarketingConsent` are `suspend` functions. Each has a fire-and-forget twin with an `Async` suffix (`acceptAllAsync()` and so on) that launches on the main thread and returns the `Job`.

Every write updates the state first, then posts the consent to ConsentIQ, then persists the local cache, then calls `onConsentChange`. A failed request is logged and the local state is kept.

`ConsentIQConfig` fields:

| Field | Default | Meaning |
| --- | --- | --- |
| `propertyKey` | required | The property's public key |
| `apiUrl` | `https://consent.iqworks.ai` | ConsentIQ base URL |
| `subjectId` | `null` | Supply your own subject id instead of a generated one. It must be random and unguessable, never an account number, email address or phone number: the consent lookup needs only the property key, which ships in your app |
| `autoGenerateSubjectId` | `true` | Generate and persist a UUID when no subject id is supplied |
| `autoShow` | `true` | Show the sheet automatically when no consent is found |
| `language` | `null` | Force a language; otherwise the property's default language, then `en` |
| `debug` | `false` | Log request details to Logcat under the `ConsentIQ` tag. Turn it on in debug builds only |

With `autoShow = false` and TCF active, the TCF screens also wait for `showSheet()`. `IABTCF_gdprApplies` is still written at start-up; whether a stored TC string is out of date is checked when the screens next open.

Marketing forms: `FormConsent` mirrors the marketing form helper in the web SDKs. It holds a map of channels (default `email`, `sms`, `whatsapp`, `push`), exposes `toggleChannel`, `setChannel`, `reset` and a `suspend fun submit()` that posts the selection with `subjectType = "user"` and `consentMethod = "form"`.

Lower level: `ConsentIQApiClient` wraps the four public endpoints (`getConfig`, `getConsent`, `submitConsent`, `verifyConsent`) and accepts a custom `HttpExecutor` for testing.

## Callbacks

All callbacks are optional fields on `ConsentIQConfig` and run on the thread that performed the operation (the main thread for the sheet and the `Async` variants).

| Callback | When |
| --- | --- |
| `onConsentChange(consent, marketing)` | After a consent write is recorded on the server |
| `onOpen()` | The sheet opened, automatically or through `showSheet()` |
| `onAccept(AcceptInfo)` | The visitor accepted all. `scope` is always `"all"` |
| `onReject(RejectInfo)` | The visitor rejected all optional categories |
| `onSave(SaveInfo)` | The visitor saved a custom selection |
| `onClose()` | The visitor backed out without answering. Never fires on accept, reject, save or `hideSheet()` |
| `onError(ErrorInfo)` | Something the SDK could not do, with a stable `code` and a `message`. Today only the TCF screens report here: `tcf-unsupported`, `tcf-unavailable`, `tcf-timeout`, `tcf-load-failed`, `tcf-page-error` |

## IAB TCF

When TCF is on for the property in the ConsentIQ dashboard, the SDK acts as the app's IAB TCF CMP:

- At start-up it writes `IABTCF_CmpSdkID` and `IABTCF_CmpSdkVersion` to the app's default `SharedPreferences` (`<package>_preferences`, the file `PreferenceManager.getDefaultSharedPreferences` returns), where vendor SDKs read them.
- Where GDPR does not apply to the visitor, it writes `IABTCF_gdprApplies = 0` and the category sheet runs as usual.
- Otherwise ConsentIQ's TCF screens replace the sheet. The SDK loads them in a hidden web view from your `apiUrl` and shows them full-screen only when the visitor has to answer: first launch, a changed vendor list or policy version, or the re-prompt period running out. On a decision it writes every `IABTCF_*` key (numbers as `Int`, strings as `String`), removes `IABTCF_PublisherRestrictions{1..11}` keys the decision no longer has, and applies the category choices to `state` and the callbacks. The screens record the consent with ConsentIQ themselves, so the SDK does not submit it again.
- System back is ignored on a first prompt and closes re-opened screens. If the visitor had tapped the screens, the SDK hides them and waits up to 10 seconds for a choice already on its way before giving up.
- An open requested while the screens are already running is kept and runs as soon as they finish.
- `resetConsent()`, and consent the server reports as expired, remove the decision's `IABTCF_*` keys (all but `IABTCF_CmpSdkID`, `IABTCF_CmpSdkVersion` and `IABTCF_gdprApplies`), so the next start asks again. If TCF is turned off for the property, the next start removes every `IABTCF_*` key the SDK wrote. Keys written by another CMP are never touched.
- Vendor privacy links open in the system browser.
- If the screens cannot run on the device (no WebView, or a WebView without the web message listener feature), no `IABTCF_*` key is written, `onError` fires and the category sheet takes over for the session.
- A load failure or timeout fires `onError` and shows the category sheet; the next `showSheet()` tries the TCF screens again. A choice made on the category sheet meanwhile removes the old decision's `IABTCF_*` keys.

Give your settings screen a "Privacy choices" entry that calls `ConsentIQ.showSheet()`; it opens whichever of the two applies.

## Sheet behavior

- Title, button labels and category copy come from the property configuration in the visitor's language, so wording changes in the dashboard reach the app without an SDK update.
- The primary button is tinted with the banner's brand color, falling back to `#f97316`.
- The sheet takes the banner's background and text colors from the dashboard when the server sends them, with secondary text and dividers matched to a light or dark background. Without them, it follows your app's Material theme.
- Right-to-left languages (Arabic, Urdu, Kashmiri, Sindhi) lay the sheet out right-to-left.
- The "view notice" link opens the cookie notice in the system browser with the current language appended as `lang`.
- The back button and a swipe down call `dismissSheet()`.

## Limitations

- Captcha. When "Require CAPTCHA" is on for the property, the SDK runs Cloudflare Turnstile invisibly in a hidden web view, loaded from `consent.iqworks.ai` (your `apiUrl`) and `challenges.cloudflare.com`, before each submit. If the check fails or Turnstile asks for user interaction, nothing is sent, the local state is kept, and the SDK logs `[ConsentIQ] Captcha check failed; consent not submitted`. `FormConsent` uses the web view that `ConsentIQ.init` sets up, so call `init` first. List Cloudflare Turnstile in your app's privacy disclosures.
- Regulation detection uses the server's view of the device's IP address. There is no client-side override.
- The SDK does not block third-party SDKs on its own. Read `hasConsent` before starting them.

## License

MIT. See [LICENSE](LICENSE).

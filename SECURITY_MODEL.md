# classtable security model

Covers `kuailiaojie/classtable`: the Android timetable app "余课课程表" (`com.kxin.classtable`) plus the backend config kept in the same repo (Netlify site and function, Firebase rules, GitHub Actions). There is no separate infrastructure repository. The app works local-first: with no account everything stays in Room on the device. Signing in turns on Firebase sync, which goes through the Netlify proxy.

## Components

| Name | Code | Notes |
|---|---|---|
| **Android App** | `app/src/main/java/com/kxin/classtable/**` (except importer/yuketang), `AndroidManifest.xml`, `res/xml/*` | Compose UI, Room, SecurePrefs (Keystore AES-GCM), reminders/widgets, AI assistant (user-supplied OpenAI/Gemini key + base URL), in-app updater (`data/UpdateRepository.kt`), FCM (`data/FcmMessagingService.kt`) |
| **Import WebView** | `ui/importer/**`, `data/importer/**`, `ui/yuketang/**`, `data/yuketang/**` | Loads school SIS/LMS portals (often plain http) and injects adapter JS. The JS calls back through the `AndroidBridgeNative` JavascriptInterface (`ImportBridge.kt`). Also covers the Yuketang login WebView/session |
| **Adapter Warehouse** | `warehouse/**`, `app/src/main/assets/warehouse/**`, `tools/*.mjs`, `.github/workflows/update-adapters.yml` | Third-party adapter scripts synced automatically from upstream, bundled into the APK and published as `<site>/warehouse/bundle.json`. `AdapterSyncRepository` downloads that file and caches it in `filesDir/warehouse` |
| **Netlify Proxy** | `netlify/functions/proxy.mjs`, `netlify.toml`, `package.json` | Public function with routes `/auth/*` (allowlist), `/firestore/*` (regex allowlist), `/push` (X-Push-Key), `/appcheck/token` (cert-SHA256 check), `/version`, `/apk` (GitHub Release proxy). Holds `SERVICE_ACCOUNT`, `PUSH_API_KEY`, `GITHUB_TOKEN`, `APP_CHECK_CERT_SHA256` |
| **Firebase Backend** | `firestore.rules`, `firebase.json`, `app/google-services.json` | Email/password Auth. Firestore `users/{uid}/...` is owner-only through `isOwner` |
| **Website** | `netlify/site/**` | Static landing page. `site.js` reads `/version` |
| **CI/Release** | `.github/workflows/build-apk.yml`, `app/build.gradle.kts` | Builds the APK and signs it with the `KEYSTORE_*` secrets. Auto-publishes GitHub Releases, which the updater then serves to users |

## Trust boundaries and entry points
- **Internet → Netlify Proxy**: every route is anonymous at the edge. `/firestore` relies on the Firebase ID token being forwarded and on the rules being evaluated upstream. `/push` relies only on the shared `PUSH_API_KEY`. `/appcheck/token` relies on the client-declared signing SHA-256 and package name, which raises the bar but does not prove the caller is the app.
- **School portal / network → Import WebView**: untrusted pages, often over cleartext, run with the native bridge attached (`addJavascriptInterface` is re-added on every page load). Cleartext is allowed app-wide by `network_security_config.xml` and is pinned to https only for the app's own domains. `MIXED_CONTENT_ALWAYS_ALLOW` is set.
- **Upstream warehouse / Netlify → app**: adapter JS from `bundle.json` runs inside portal sessions that are logged in. The bundle carries no signature beyond TLS to `SITE_BASE_URL`.
- **GitHub Releases → updater**: the APK and its SHA-256 both come from the proxy's `/version`.
- **Other apps on the device → Android App**: exported components are `MainActivity` and the launcher aliases. Receivers and the FileProvider are not exported. Backup excludes the session prefs and `updates/`.
- **FCM → app**: data messages sent through `/push`.

## Assets (the user rates them all critical)
- Netlify secrets: Firebase service account (full admin), `PUSH_API_KEY`, `GITHUB_TOKEN`
- Release signing keystore and the CI secrets (`KEYSTORE_*`, `WEATHER_API_KEY`)
- Users' school portal credentials, cookies and SSO sessions, plus the Yuketang session
- Users' Firebase accounts, ID/refresh tokens and synced timetable/agenda data
- Users' AI API keys (SecurePrefs `ai_session.xml`)
- Integrity of the APK and adapter scripts delivered to users

## Attackers (the user says all of them matter)
1. Anonymous internet attacker using the proxy and Firebase directly: data access, quota abuse, push spoofing, open-proxy/SSRF use
2. Malicious or compromised upstream adapter, or a tampered `bundle.json`
3. Network MITM, especially on cleartext school portals and open Wi-Fi
4. Malicious app on the same device: intents, exported components, shared storage
5. Authenticated user going after other users' data

## Existing controls
- Firestore rules: owner-only, write whitelists, field-count limits
- Proxy path allowlists for `/auth` and `/firestore`, `/push` key check, App Check signing with a timestamp window
- SecurePrefs with Keystore AES-GCM. Backup/transfer rules exclude the session prefs
- SHA-256 check of the updater's APK. Cleartext is blocked for the app's own domains
- WebView: `allowFileAccess`/`allowContentAccess` are off. External navigation is handled by `handleExternalNavigation`
- CI: `permissions: contents: read` on the build workflow

## Out of scope / accepted risk
- None. The user wants everything reported.
- `docs/workers-backend.md` describes a Cloudflare backend that has not been built, so there is no code in it to assess.

## Open questions
- Is App Check enforced on Firestore/Auth in the Firebase console? Without enforcement, App Check gives no protection.
- Is `GITHUB_TOKEN` in Netlify a fine-grained, read-only token?

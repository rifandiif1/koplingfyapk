# API Configuration Guide — KOPLINGFY

Koplingfy is designed to work out-of-the-box with **zero paid API keys** for Audius, YouTube Music (via open Piped/Invidious gateways), Radio Browser, LRCLIB synced lyrics, ML Kit On-Device Translation, and Local MediaStore playback.

## 1. Zero-Key Providers (Ready Immediately)

| Feature | Provider | Configuration Needed |
| --- | --- | --- |
| Indie & Electronic Streaming | **Audius Open API** | None (`app_name=KOPLINGFY`) |
| YouTube Music Catalog & Audio | **Piped / Invidious Federation** | None (custom Piped URL optional in Explore -> Sources) |
| Global Internet Radio | **Radio Browser API** | None (automatic server failover) |
| Synced & Plain Lyrics | **LRCLIB + Lyrics.ovh** | None (custom `User-Agent` & rate limit built-in) |
| Offline Lyrics Translation | **Google ML Kit On-Device** | None (language packs download once automatically) |

## 2. Optional Third-Party Keys (In-App or Secrets Panel)

All optional credentials can be entered in **Explore -> Sources** or **Settings -> Lyrics & Translation**, or supplied via the AI Studio Secrets panel (`.env`, never committed to Git).

### Jamendo API (`client_id`) — Free Creative Commons Streaming & Downloads
1. Create a free developer account at [https://devportal.jamendo.com/](https://devportal.jamendo.com/).
2. Register an application to receive your free `client_id`.
3. Open **Explore -> Sources -> Jamendo API Configuration** and save your `client_id`.

### Musixmatch API (Optional Licensed Lyrics Fallback)
1. Register at [https://developer.musixmatch.com/](https://developer.musixmatch.com/) for a licensed API key.
2. Enter the key under **Settings -> Lyrics & Translation -> Optional Musixmatch API Key**.

### Google Cloud Translation API (Optional Cloud Translation)
1. Enable Cloud Translation API in Google Cloud Console and generate a restricted API key (or route through your own backend proxy for production releases).
2. Enter the key under **Settings -> Lyrics & Translation -> Optional Google Cloud Translate Key**, then select **Google Cloud Translate** as the active translation provider.

### LibreTranslate / Self-Hosted Endpoint
1. Deploy or select any LibreTranslate-compatible endpoint (default: `https://libretranslate.com/translate`).
2. Save the URL in **Settings -> Lyrics & Translation -> LibreTranslate Endpoint URL**.

## 3. GitHub Actions Release Signing Secrets

To enable automatic signed Release APK generation in `.github/workflows/android-build.yml`, add these encrypted secrets in **GitHub Repository Settings -> Secrets and variables -> Actions**:

- `RELEASE_KEYSTORE_BASE64`: Base64-encoded contents of your `.jks` upload keystore (`base64 -w 0 my-upload-key.jks`).
- `STORE_PASSWORD`: Keystore password.
- `KEY_PASSWORD`: Key password for alias `upload`.

Never commit `.env`, `.jks`, or raw API secrets into version control.

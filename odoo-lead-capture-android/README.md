# Odoo Lead Capture (Android)

A small Android app for capturing leads, for example at a trade show, and sending them
straight to your Odoo CRM.

For each lead you can:

| In the app | Saved in Odoo (`crm.lead`) |
|---|---|
| Photo of a business card or name badge (taken with the phone camera) | Attachment `business_card_<timestamp>.jpg` |
| Name | Contact Name (`contact_name`) |
| Company name | Company Name (`partner_name`) |
| Email | Email (`email_from`) |
| Phone | Phone (`phone`) |
| Note | Internal Notes (`description`) |
| Voice note (recorded in the app, AAC/M4A) | Attachment `voice_note_<timestamp>.m4a` |

The lead title (`name`) is built as `Company - Name`. If both are empty, the app uses the
email, then the phone, then "Lead from mobile app". The salesperson is the Odoo user you
log in with. The app does not set the lead type. Odoo picks it: a *Lead* when "Leads" is
enabled in CRM settings, otherwise an *Opportunity*.

The photo and voice note are also posted as a note in the lead's chatter, so you can see
and play them on the lead.

## Odoo compatibility

The app uses Odoo's standard external API (`/jsonrpc`, `common.authenticate` +
`object.execute_kw`). It works with Odoo Online, Odoo.sh and on-premise installs. You need
the **CRM** app installed. Recent versions store `description` as HTML and older ones as
plain text; the app checks which one your server uses.

## Setup in Odoo

1. Log in as the user who will own the leads. That user needs the *Sales / User* rights or higher.
2. Open **My Profile → Account Security → New API Key**, give the key a name such as
   "Lead Capture phone", and copy it.

## Setup in the app

On first launch the app opens **Odoo connection**:

- **Server URL**: e.g. `https://mycompany.odoo.com`. If you paste a URL from your browser,
  the app removes the `/odoo/...` or `/web#...` part.
- **Database**: on Odoo Online this is usually the subdomain (`mycompany`). On-premise, it
  is the name shown in the database selector.
- **Login**: the user's login, usually their email address.
- **API key**: the key from the steps above. A password also works, but an API key is
  better. The key is encrypted with the Android Keystore and never leaves the device except
  to your server.

Tap **Test connection** to check the login and whether the user can create leads.

## Build

Requirements: Android Studio (Ladybug or newer) or the Android SDK with platform 35, and JDK 17+.

```bash
cd odoo-lead-capture-android
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # Odoo client tests (fake Odoo JSON-RPC server)
```

To install on a connected phone: `./gradlew installDebug`. Or open the folder in Android
Studio and press Run.

For a release build to share with your team, create a signing key and set up
`signingConfigs` in `app/build.gradle.kts`, then run `./gradlew assembleRelease`, or use
*Build → Generate Signed Bundle / APK* in Android Studio.

- Minimum Android version: 8.0 (API 26). Target: Android 15 (API 35).
- Permissions: Internet and microphone. The microphone permission is requested the first
  time you record. Photos are taken with the phone's camera app, which needs no camera
  permission.
- Only HTTPS is allowed by default. To use plain `http://` with a local test server, add
  a network security config.

## Project layout

```
app/src/main/java/com/leadcapture/odoo/
  OdooClient.kt       JSON-RPC client: authenticate, create crm.lead, upload ir.attachment, chatter note
  SettingsStore.kt    Connection settings; API key encrypted with an Android Keystore AES-GCM key
  LeadViewModel.kt    Photo / audio recording / playback / submit state (survives rotation and process death)
  ImageUtils.kt       Shrinks camera photos to 2000 px max and fixes EXIF rotation
  MainActivity.kt     Lead capture screen
  SettingsActivity.kt Odoo connection screen with "Test connection"
app/src/test/...      OdooClient tests against a fake Odoo server
```

## Behaviour notes

- If an attachment upload fails, the lead still exists. The app reports the lead as
  created and lists which file failed, so you don't create a duplicate by submitting again.
- After a successful submit the form is cleared. **Open in Odoo** opens the new lead in
  the browser.
- A recording stops when the app goes to the background, because Android blocks the
  microphone for background apps.
- The app needs a connection when you tap Submit. It has no offline queue yet; see the
  ideas below.

## Ideas for later

- An offline queue that sends leads when you are back online, for show floors with poor Wi-Fi.
- Reading the business card text on the device with ML Kit and filling in the fields.
- Choosing tags, a source/campaign (e.g. the event name) or a sales team for each lead.

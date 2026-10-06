# SpiritualTracker

SpiritualTracker is a native Android app that helps two people — a couple, accountability partners, or friends — build daily spiritual habits **together**. It tracks Bible reading, prayer, journaling, and Scripture memorization for both partners side by side, and layers in real-time chat, nudges, and push notifications to keep each person accountable to the other.

> Built with Java, Firebase (Auth, Firestore, Cloud Messaging), and Android View Binding.

---

## Table of Contents

- [Features](#features)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Data Model](#data-model-firestore)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Firebase Setup](#1-firebase-setup)
  - [Cloudinary Setup](#2-cloudinary-setup-audioimage-uploads)
  - [Push Notification Relay](#3-push-notification-relay)
  - [Build & Run](#4-build--run)
- [⚠️ Before You Push to GitHub](#️-before-you-push-to-github)
- [Permissions](#permissions)
- [Known Limitations / Roadmap](#known-limitations--roadmap)
- [Contributing](#contributing)
- [License](#license)

---

## Features

### 🏠 Shared Dashboard
- A single home screen shows **"Days Together"** (a shared streak score) plus a live progress card with a dot for each partner across four habits: Bible, Prayer, Journal, and Memorize.
- A 7-day heatmap visualizes how consistently both partners have read the Bible together (full / half / no credit per day).
- Unread badges surface new activity in Bible, Prayer, and Journal sections, and tapping a push notification deep-links straight into the relevant screen.

### 📖 Bible
- Daily "Verse of the Day" with a one-tap "Mark as Read" check-in.
- Each partner can jot a personal note/verse for the day, visible to the other once they finish reading.
- Completing the daily reading nudges the partner's unread badge and sends them a push notification; when **both** partners finish on the same day, a shared streak point is awarded (via a Firestore transaction to avoid double-counting).
- A manual "Nudge" button reminds a partner who hasn't read yet.

### 🙏 Prayer
A tabbed experience (via `ViewPager2` + bottom navigation) with three sections:
- **Connection** — a real-time 1:1 chat feed with the linked partner: text messages, voice notes (recorded on-device and uploaded to Cloudinary), swipe-to-reply/quote, emoji reactions, typing indicators, read receipts, and an unread-message divider.
- **Shared Board** — a joint prayer request board both partners can post to, check off, and mark answered.
- **My Space** — a private list of personal prayer requests, separate from the shared board.
- **Prayer Vault** — an archive of answered prayers/testimonies, filterable by "All / Mine / Partner's," with running stats on how many requests have been answered.

### 📓 Journal
- Create personal or partner-visible journal entries, with optional photo and audio attachments.
- Threaded replies/comments on each entry (similar to the chat experience in Prayer → Connection), with quoting, reactions, and edit/delete support.
- Draft vs. published states — a journal entry only counts toward the daily streak once it's published (not left as a draft).

### 🧠 Memorize
- Add verses to a personal memorization list and review them with a tap-to-reveal quiz dialog.
- Spaced-repetition style review intervals (1 → 2 → 4 → 7 → 30 days) before a verse is marked "Mastered."
- See the partner's shared/mastered verses alongside your own, and get a daily push notification listing how many verses are due for review (scheduled via `WorkManager`).

### 🔔 Notifications & Accountability
- Firebase Cloud Messaging (FCM) tokens are stored per-user so partners can nudge or be notified of each other's activity (new journal entry, prayer chat message, daily verse reminder, etc.).
- Outbound notifications are relayed through a small external webhook (since FCM "send" calls require server credentials that shouldn't live in a client app) — see [Push Notification Relay](#3-push-notification-relay) below.

### 🔐 Auth
- Email/password sign-up and sign-in via Firebase Authentication, with auto-login if a session already exists.

---

## Tech Stack

| Layer | Choice |
|---|---|
| Language | Java |
| Build | Gradle (Kotlin DSL), Android Gradle Plugin 8.13.2 |
| Min / Target / Compile SDK | 24 / 35 / 35 |
| UI | Android Views, View Binding, Material Components, `ViewPager2` |
| Backend | Firebase Authentication, Cloud Firestore (real-time listeners), Firebase Cloud Messaging |
| Background work | `androidx.work` (WorkManager) for scheduled daily reminders |
| Media | Cloudinary Android SDK (voice-note uploads), `MediaRecorder`/`MediaPlayer` wrapper (`AudioRecorder`) |
| Networking | OkHttp (webhook calls for push relay) |

## Project Structure

```
app/src/main/java/com/keziah/spiritualtracker/
├── MainActivity.java            # Shared dashboard, streak, heatmap, badges
├── LoginActivity.java           # Firebase email/password auth
├── BibleActivity.java           # Daily reading check-in + partner status
├── PrayerActivity.java          # Hosts the Connection / Board / My Space tabs
│   ├── ConnectionFragment.java  # 1:1 real-time chat (text/voice, reactions, receipts)
│   ├── SharedBoardFragment.java # Joint prayer request board
│   └── MySpaceFragment.java     # Private prayer requests
├── PrayerVaultActivity.java     # Answered-prayer archive
├── JournalActivity.java         # Compose a journal entry
├── JournalListActivity.java     # List of journal entries
├── JournalDetailActivity.java   # Entry detail + threaded replies
├── MemorizeActivity.java        # Verse memorization + spaced-repetition review
├── MyFirebaseMessagingService.java
├── DailyVerseReminderWorker.java
├── AudioRecorder.java           # Voice-note recording helper
└── models/ (JournalEntry, JournalReply, PrayerRequest, MemoryVerse, ...)
```

## Data Model (Firestore)

| Collection | Purpose |
|---|---|
| `users/{uid}` | Profile, `partnerId`, `fcmToken`, `sharedScore`, daily completion flags, unread counters |
| `daily_readings/{yyyy-MM-dd}` | Per-day Bible check-in map, keyed by user ID (`{uid}: bool`, `{uid}_verse: string`) |
| `journal_entries/{docId}` | Journal entries (title, content, media, author, status) |
| `journals/{docId}/replies/{replyId}` | Threaded replies on a journal entry |
| `prayer_messages/{docId}` | Connection-tab chat messages between partners |
| `shared_prayers/{docId}` | Joint prayer board requests |
| `personal_prayers/{docId}` | Private prayer requests |
| `partnerships/{id}/joint_list/{id}` | Shared focus-item list between partners |
| `memory_verses/{docId}` | Scripture memorization entries with review scheduling |

> **Note:** journal entries are written to `journal_entries`, but replies are nested under a sibling `journals/{docId}/replies` path that uses the same document ID. Double-check this is intentional for your security rules, or normalize it to a single top-level collection.

---

## Getting Started

### Prerequisites
- Android Studio (Ladybug or newer recommended)
- JDK 11+
- A Firebase project
- A Cloudinary account (for voice-note/image uploads in the Connection and Journal features)

### 1. Firebase Setup
1. Create a project at the [Firebase Console](https://console.firebase.google.com/).
2. Add an Android app with package name `com.keziah.spiritualtracker`.
3. Enable **Authentication → Email/Password**.
4. Enable **Cloud Firestore** (start in test mode locally, then lock down rules before going live — see security notes below).
5. Enable **Cloud Messaging**.
6. Download the generated `google-services.json` and place it at `app/google-services.json` (replacing the placeholder/old one if present).

### 2. Cloudinary Setup (audio/image uploads)
1. Create a free account at [cloudinary.com](https://cloudinary.com/).
2. Create an **unsigned upload preset** named `YOUR_UNSIGNED_UPLOAD_PRESET` (or update the preset name referenced in `ConnectionFragment.java` / `JournalDetailActivity.java` to match your own).
3. Initialize `MediaManager` with your Cloudinary cloud name (the app currently expects this to be configured at app startup — wire your cloud name into `SpiritualTrackerApp.java` if it isn't already).

### 3. Push Notification Relay
Sending FCM pushes requires a server-side credential (the Firebase Admin SDK), which can't safely live inside the Android app. This project calls a small external HTTP endpoint that does that server-side send on its behalf:

- `DailyVerseReminderWorker`, `MemorizeActivity`, and `JournalActivity` call `https://YOUR-NOTIFY-WEBHOOK/api/notify`.
- `BibleActivity`, `ConnectionFragment`, `JournalDetailActivity`, and `SharedBoardFragment` currently call a Pipedream debug endpoint (`https://YOUR-PIPEDREAM-ENDPOINT`).

Both of these are the original author's personal endpoints. **You will need to deploy your own relay** (a tiny serverless function using the Firebase Admin SDK to call `admin.messaging().send(...)`) and point all of these URLs at it before notifications will work for your own Firebase project. Centralizing them into a single `BuildConfig` field or remote config value is recommended so you only have to change one place.

### 4. Build & Run
```bash
git clone <your-fork-url>
cd SpiritualTracker
./gradlew assembleDebug
```
Or simply open the project in Android Studio and press **Run**. The app launches into `LoginActivity`; create an account (or sign in) to reach the dashboard.

> **Linking two accounts:** there is currently no in-app "invite/connect" flow. Partner pairing is done by setting `partnerId` on both users' `users/{uid}` documents (pointing at each other's UID) directly in Firestore. See [Known Limitations](#known-limitations--roadmap).

---

## ⚠️ Before You Push to GitHub

This repository's first commit includes `app/google-services.json`, which contains a live Firebase API key for the original project. Before publishing publicly:

1. **Remove the real file from git history** (or rewrite history) and add it to `.gitignore`:
   ```
   app/google-services.json
   ```
2. **Rotate the exposed API key** in the [Google Cloud Console](https://console.cloud.google.com/apis/credentials) for the associated project, and restrict it to your app's package name + SHA-1 fingerprint.
3. **Review your Firestore security rules** — make sure `users`, `journal_entries`, `prayer_messages`, etc. are locked down so only authenticated partners can read/write their own data, since this repo's rules aren't included here.
4. Double-check `local.properties` (already `.gitignore`d) and any other personal SDK paths aren't committed.
5. Consider providing a `google-services.json.example` / `local.properties.example` for contributors instead of a real config file.

## Permissions

| Permission | Used For |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Firebase, Cloudinary, webhook calls |
| `RECORD_AUDIO` | Voice notes in chat/journal |
| `READ_MEDIA_IMAGES`, `READ_MEDIA_AUDIO`, `READ_EXTERNAL_STORAGE` | Attaching photos/audio to journal entries and messages |
| `WRITE_EXTERNAL_STORAGE` (≤ SDK 28) | Legacy media write support |
| `POST_NOTIFICATIONS` | FCM push notifications (Android 13+) |

## Known Limitations / Roadmap

- No in-app partner invite/pairing flow — `partnerId` must be set manually in Firestore today.
- Push relay endpoints are hardcoded to the original author's personal Vercel/Pipedream URLs and are split across two different services; these need to be replaced and consolidated.
- `journal_entries` vs. `journals/{id}/replies` collection naming is inconsistent and worth normalizing.
- `isMinifyEnabled` is `false` for release builds — consider enabling R8/ProGuard with the included `proguard-rules.pro` before shipping.
- No automated test coverage beyond the default generated `ExampleUnitTest` / `ExampleInstrumentedTest`.

## Contributing

Issues and pull requests are welcome. Please avoid committing real Firebase/Cloudinary credentials in any PR — use the placeholder/example config approach described above.

## License

No license has been specified yet for this project. Add a `LICENSE` file (MIT, Apache-2.0, etc.) to clarify how others may use this code.

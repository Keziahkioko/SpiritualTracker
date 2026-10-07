# Spiritual Tracker relay (Vercel)

A tiny free backend that replaces both the old Pipedream workflow and the old Vercel relay.

| Endpoint | What it does |
|---|---|
| `POST /api/notify` | Sends a push notification to the caller's **linked partner**. The app proves who it is with its Firebase sign-in token; the server looks up the partner and their device itself, so nobody can send notifications to arbitrary phones. |
| `POST /api/cloudinary-signature` | Signs voice-note uploads for linked partners, so your Cloudinary account no longer needs an open "unsigned" upload preset. |

Runs on Vercel's free **Hobby** plan (no card needed).

## Deploy (about 15 minutes)

**Do this before installing the new app version**: the new app talks to these endpoints, not the old ones.

1. **Firebase service-account key**
   Firebase Console → ⚙️ Project settings → **Service accounts** → **Generate new private key**.
   A JSON file downloads. Treat it like a password: never commit it or send it to anyone.

2. **Cloudinary API key and secret**
   Cloudinary Console → **Settings → API Keys**. Copy the *API Key* and *API Secret*.

3. **Vercel project**
   - Sign in at [vercel.com](https://vercel.com) with GitHub (Hobby plan is free, no card).
   - **Add New → Project** → import the `SpiritualTracker` repo.
   - Set **Root Directory** to `server`. Leave the framework as "Other".
   - Under **Environment Variables** add:
     | Name | Value |
     |---|---|
     | `FIREBASE_SERVICE_ACCOUNT` | the entire contents of the JSON file from step 1 |
     | `CLOUDINARY_API_KEY` | from step 2 |
     | `CLOUDINARY_API_SECRET` | from step 2 |
   - **Deploy**. Note the URL, e.g. `https://spiritual-tracker-relay.vercel.app`.

   (Already have the old `spiritual-tracker-webhook` project? You can instead point that project at this repo with Root Directory `server` and the same variables, and keep its URL.)

4. **Point the app at it**
   In the app's `local.properties` set `relay.url=` to the URL from step 3 (no trailing slash), then rebuild the app.

5. **Check it works**
   Opening `https://<your-url>/api/notify` in a browser should show `{"error":"POST only"}`.

## After both phones run the new app

- **Cloudinary**: Settings → Upload → Upload presets → delete the old unsigned preset (`journal_upload`). Until you do, strangers can still upload through it.
- **Pipedream**: export your workflow if you want a copy, then delete it. (Pipedream Workflows shuts down on 31 March 2027 anyway.)
- **Old Vercel relay**: if you created a new project in step 3, delete the old one.

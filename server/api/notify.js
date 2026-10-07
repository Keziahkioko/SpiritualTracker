import { FieldValue } from 'firebase-admin/firestore';
import { messaging, mutualPartner, verifyCaller } from '../lib/firebase.js';

// Notification types the app knows how to route (see MyFirebaseMessagingService).
const TYPES = new Set([
  'bible_nudge', 'bible_update', 'journal_new', 'journal_chat',
  'prayer_nudge', 'prayer_chat', 'prayer_update', 'memorize', 'reminder',
]);

const clip = (value, max) => String(value ?? '').slice(0, max);

/**
 * POST /api/notify   Authorization: Bearer <Firebase ID token>
 * Body: { title, body, type, docId }
 *
 * Sends a push to the caller's partner. The recipient is never taken from the request:
 * it's looked up from Firestore and must be a mutual partner, so this endpoint can't be
 * used to message arbitrary phones.
 */
export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' });

  const uid = await verifyCaller(req);
  if (!uid) return res.status(401).json({ error: 'Sign-in required' });

  const { title, body, type, docId } = req.body || {};
  if (!title || !body) return res.status(400).json({ error: 'title and body are required' });
  if (!TYPES.has(type)) return res.status(400).json({ error: 'Unknown notification type' });

  const partner = await mutualPartner(uid);
  if (!partner) return res.status(403).json({ error: 'No linked partner' });

  const token = partner.get('fcmToken');
  if (!token) return res.status(200).json({ sent: false, reason: 'Partner has no device registered' });

  try {
    // Data-only message so the app always builds the notification itself and routes the tap.
    await messaging().send({
      token,
      data: {
        title: clip(title, 120),
        body: clip(body, 500),
        type,
        docId: clip(docId, 200),
      },
      android: { priority: 'high' },
    });
    return res.status(200).json({ sent: true });
  } catch (err) {
    if (err?.code === 'messaging/registration-token-not-registered'
        || err?.code === 'messaging/invalid-registration-token') {
      await partner.ref.update({ fcmToken: FieldValue.delete() });
      return res.status(200).json({ sent: false, reason: 'Partner device token expired' });
    }
    console.error('FCM send failed', err);
    return res.status(502).json({ error: 'Push delivery failed' });
  }
}

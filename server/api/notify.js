import { FieldValue } from 'firebase-admin/firestore';
import { db, messaging, mutualPartner, verifyCaller } from '../lib/firebase.js';

// Notification types the app knows how to route (see MyFirebaseMessagingService).
const TYPES = new Set([
  'bible_nudge', 'bible_update', 'journal_new', 'journal_chat',
  'prayer_nudge', 'prayer_chat', 'prayer_update', 'memorize', 'reminder', 'home_photo',
]);

const clip = (value, max) => String(value ?? '').slice(0, max);

/**
 * POST /api/notify   Authorization: Bearer <Firebase ID token>
 * Body: { title, body, type, docId }
 *
 * Sends a push to the caller's partner. The recipient is never taken from the request:
 * it's looked up from Firestore and must be a mutual partner, so this endpoint can't be
 * used to message arbitrary phones.
 *
 * Every outcome says why ({ sent, reason }) and is logged, so a missing notification can be
 * traced to the partner's device token, a stale token, or delivery.
 */
export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' });

  const uid = await verifyCaller(req);
  if (!uid) return res.status(401).json({ error: 'Sign-in required' });

  const { title, body, type, docId } = req.body || {};
  if (!title || !body) return res.status(400).json({ error: 'title and body are required' });
  if (!TYPES.has(type)) return res.status(400).json({ error: 'Unknown notification type' });

  let partner, token;
  try {
    partner = await mutualPartner(uid);
    if (!partner) {
      console.warn('notify: no mutual partner', uid);
      return res.status(403).json({ error: 'No linked partner' });
    }
    token = partner.get('fcmToken');
    if (!token) {
      console.warn('notify: partner has no device token', partner.id);
      return res.status(200).json({ sent: false, reason: 'Partner has no device registered' });
    }
    // One phone signed into both accounts at some point leaves both profiles holding that
    // phone's token; the push would land on the sender's own phone instead of the partner's.
    const me = await db().collection('users').doc(uid).get();
    if (me.get('fcmToken') === token) {
      console.warn('notify: partner token is the sender\'s own device', uid, partner.id);
      await partner.ref.update({ fcmToken: FieldValue.delete() });
      return res.status(200).json({ sent: false, reason: 'Partner token belonged to this phone; cleared' });
    }
  } catch (err) {
    console.error('notify: Firestore lookup failed', err);
    return res.status(500).json({ error: 'Could not look up partner' });
  }

  try {
    // Data-only message so the app always builds the notification itself and routes the tap.
    const id = await messaging().send({
      token,
      data: {
        title: clip(title, 120),
        body: clip(body, 500),
        type,
        docId: clip(docId, 200),
      },
      android: { priority: 'high', ttl: 24 * 60 * 60 * 1000 },
    });
    console.log('notify: sent', type, 'to', partner.id, id);
    return res.status(200).json({ sent: true });
  } catch (err) {
    if (err?.code === 'messaging/registration-token-not-registered'
        || err?.code === 'messaging/invalid-registration-token') {
      console.warn('notify: partner token expired', partner.id, err.code);
      await partner.ref.update({ fcmToken: FieldValue.delete() });
      return res.status(200).json({ sent: false, reason: 'Partner device token expired' });
    }
    console.error('notify: FCM send failed', err?.code, err);
    return res.status(502).json({ error: 'Push delivery failed', code: err?.code || '' });
  }
}

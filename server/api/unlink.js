import { FieldValue } from 'firebase-admin/firestore';
import { db, messaging, verifyCaller } from '../lib/firebase.js';

/**
 * POST /api/unlink   Authorization: Bearer <Firebase ID token>
 *
 * Ends the caller's partnership. Both people's partnerId is cleared together, so neither
 * side is left pointing at someone who no longer points back. Done here (not in the app)
 * because the security rules only let each person change their own profile.
 */
export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' });

  const uid = await verifyCaller(req);
  if (!uid) return res.status(401).json({ error: 'Sign-in required' });

  const meRef = db().collection('users').doc(uid);
  const me = await meRef.get();
  const partnerId = me.get('partnerId');
  if (!partnerId) return res.status(200).json({ unlinked: false, reason: 'Not linked' });

  const partnerRef = db().collection('users').doc(partnerId);
  const partner = await partnerRef.get();
  const clear = { partnerId: FieldValue.delete(), partnerLinkedAt: FieldValue.delete() };

  const batch = db().batch();
  batch.update(meRef, clear);
  // Only touch the other profile if it really points back at the caller.
  if (partner.exists && partner.get('partnerId') === uid) batch.update(partnerRef, clear);
  await batch.commit();

  // Let the other phone know, so it isn't left waiting on a partnership that has ended.
  const token = partner.exists ? partner.get('fcmToken') : null;
  if (token) {
    const name = String(me.get('name') || 'Your partner').slice(0, 60);
    try {
      await messaging().send({
        token,
        data: {
          title: 'Partnership ended',
          body: `${name} unlinked your accounts. Your own entries are still yours.`,
          type: 'unlinked',
          docId: '',
        },
        android: { priority: 'high' },
      });
    } catch (err) {
      console.warn('Unlink push failed', err?.code || err);
    }
  }
  return res.status(200).json({ unlinked: true });
}

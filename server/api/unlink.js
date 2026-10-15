import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db, messaging, verifyCaller } from '../lib/firebase.js';

// If the other person never answers, the one who asked may unlink alone after this long,
// so nobody can be kept linked against their will.
const WAIT_MS = 7 * 24 * 60 * 60 * 1000;
const ACTIONS = new Set(['request', 'cancel', 'accept', 'decline', 'force']);

/**
 * POST /api/unlink   Authorization: Bearer <Firebase ID token>
 * Body: { action: "request" | "cancel" | "accept" | "decline" | "force" }
 *
 * Unlinking needs both people: one asks, the other agrees (or says no). The request itself
 * lives in unlink_requests/{pairId}, which only this relay can write, so neither phone can
 * fake the other's agreement. Each profile also gets an `unlinkRequest` copy ({by, at})
 * purely so the apps can show the banner.
 */
export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' });

  const uid = await verifyCaller(req);
  if (!uid) return res.status(401).json({ error: 'Sign-in required' });

  const action = (req.body || {}).action || 'request';
  if (!ACTIONS.has(action)) return res.status(400).json({ error: 'Unknown action' });

  const meRef = db().collection('users').doc(uid);
  const me = await meRef.get();
  const partnerId = me.get('partnerId');
  if (!partnerId) return res.status(200).json({ done: false, reason: 'Not linked' });

  const partnerRef = db().collection('users').doc(partnerId);
  const partner = await partnerRef.get();
  const mutual = partner.exists && partner.get('partnerId') === uid;
  const myName = String(me.get('name') || 'Your partner').slice(0, 60);

  // Half-linked (the other side already let go): nobody to ask, just tidy up my side.
  if (!mutual) {
    await meRef.update(cleared());
    return res.status(200).json({ done: true, unlinked: true });
  }

  const requestRef = db().collection('unlink_requests').doc([uid, partnerId].sort().join('_'));
  const request = await requestRef.get();
  const by = request.exists ? request.get('requestedBy') : null;
  const at = request.exists ? request.get('requestedAt') : null;

  switch (action) {
    case 'request': {
      if (by) return res.status(200).json({ done: false, reason: by === uid ? 'Already asked' : 'They already asked' });
      const now = Timestamp.now();
      const batch = db().batch();
      batch.set(requestRef, { requestedBy: uid, requestedAt: now });
      batch.update(meRef, { unlinkRequest: { by: uid, at: now } });
      batch.update(partnerRef, { unlinkRequest: { by: uid, at: now } });
      await batch.commit();
      await push(partner, `${myName} asked to unlink`,
        'Open the app to read their request. Nothing changes unless you agree.', 'unlink_request');
      return res.status(200).json({ done: true });
    }

    case 'cancel': {
      if (by !== uid) return res.status(409).json({ error: 'No request of yours to cancel' });
      await clearRequest(requestRef, meRef, partnerRef);
      return res.status(200).json({ done: true });
    }

    case 'decline': {
      if (!by || by === uid) return res.status(409).json({ error: 'No request to answer' });
      await clearRequest(requestRef, meRef, partnerRef);
      await push(partner, `${myName} wants to stay linked`,
        'They said no to unlinking. You are still partners in the app.', 'unlink_request');
      return res.status(200).json({ done: true });
    }

    case 'accept': {
      if (!by || by === uid) return res.status(409).json({ error: 'No request to answer' });
      await unlinkBoth(requestRef, meRef, partnerRef);
      await push(partner, 'Partnership ended', `${myName} agreed to unlink. Your own entries are still yours.`, 'unlinked');
      return res.status(200).json({ done: true, unlinked: true });
    }

    case 'force': {
      if (by !== uid) return res.status(409).json({ error: 'Ask first' });
      if (!at || Date.now() - at.toMillis() < WAIT_MS) return res.status(409).json({ error: 'Still waiting for an answer' });
      await unlinkBoth(requestRef, meRef, partnerRef);
      await push(partner, 'Partnership ended', `${myName} unlinked your accounts. Your own entries are still yours.`, 'unlinked');
      return res.status(200).json({ done: true, unlinked: true });
    }
  }
}

function cleared() {
  return { partnerId: FieldValue.delete(), partnerLinkedAt: FieldValue.delete(), unlinkRequest: FieldValue.delete() };
}

async function clearRequest(requestRef, meRef, partnerRef) {
  const batch = db().batch();
  batch.delete(requestRef);
  batch.update(meRef, { unlinkRequest: FieldValue.delete() });
  batch.update(partnerRef, { unlinkRequest: FieldValue.delete() });
  await batch.commit();
}

async function unlinkBoth(requestRef, meRef, partnerRef) {
  const batch = db().batch();
  batch.delete(requestRef);
  batch.update(meRef, cleared());
  batch.update(partnerRef, cleared());
  await batch.commit();
}

async function push(person, title, body, type) {
  const token = person.get('fcmToken');
  if (!token) return;
  try {
    await messaging().send({
      token,
      data: { title, body, type, docId: '' },
      android: { priority: 'high' },
    });
  } catch (err) {
    console.warn('Unlink push failed', err?.code || err);
  }
}

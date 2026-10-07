import { cert, getApps, initializeApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { getFirestore } from 'firebase-admin/firestore';
import { getMessaging } from 'firebase-admin/messaging';

// FIREBASE_SERVICE_ACCOUNT holds the whole service-account JSON (Vercel → Settings → Environment Variables).
function app() {
  if (getApps().length) return getApps()[0];
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (!raw) throw new Error('FIREBASE_SERVICE_ACCOUNT is not set');
  return initializeApp({ credential: cert(JSON.parse(raw)) });
}

export const auth = () => getAuth(app());
export const db = () => getFirestore(app());
export const messaging = () => getMessaging(app());

/** Returns the caller's uid from "Authorization: Bearer <Firebase ID token>", or null. */
export async function verifyCaller(req) {
  const header = req.headers.authorization || '';
  const match = header.match(/^Bearer (.+)$/);
  if (!match) return null;
  try {
    const decoded = await auth().verifyIdToken(match[1]);
    return decoded.uid;
  } catch {
    return null;
  }
}

/** The caller's partner, only if both user docs point at each other. */
export async function mutualPartner(uid) {
  const me = await db().collection('users').doc(uid).get();
  const partnerId = me.get('partnerId');
  if (!partnerId) return null;
  const partner = await db().collection('users').doc(partnerId).get();
  if (!partner.exists || partner.get('partnerId') !== uid) return null;
  return partner;
}

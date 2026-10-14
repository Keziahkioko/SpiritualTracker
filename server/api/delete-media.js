import { createHash } from 'node:crypto';
import { db, verifyCaller } from '../lib/firebase.js';

const FOLDER = 'spiritual-tracker/';

/**
 * POST /api/delete-media   Authorization: Bearer <Firebase ID token>
 * Body: { docId, urls? }
 *
 * Removes a journal entry's photos and voice notes from Cloudinary. Only the entry's author
 * may call it, and only files that the entry actually references are deleted.
 *  - with `urls`: just those (photos removed or audio replaced while editing);
 *  - without: everything on the entry plus voice notes in its discussion, whose
 *    messages are deleted too (the app deletes the entry itself afterwards).
 */
export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' });

  const uid = await verifyCaller(req);
  if (!uid) return res.status(401).json({ error: 'Sign-in required' });

  const { docId, urls } = req.body || {};
  if (!docId || typeof docId !== 'string') return res.status(400).json({ error: 'docId is required' });

  const { CLOUDINARY_API_KEY: apiKey, CLOUDINARY_API_SECRET: apiSecret } = process.env;
  if (!apiKey || !apiSecret) return res.status(500).json({ error: 'Cloudinary is not configured' });

  const entry = await db().collection('journal_entries').doc(docId).get();
  if (!entry.exists) return res.status(404).json({ error: 'Entry not found' });
  if (entry.get('userId') !== uid) return res.status(403).json({ error: 'Not your entry' });

  const onEntry = new Set([
    ...(Array.isArray(entry.get('imageUrls')) ? entry.get('imageUrls') : []),
    entry.get('imageUrl'),
    entry.get('audioUrl'),
  ].filter((u) => typeof u === 'string' && u));

  let targets;
  const replies = [];
  if (Array.isArray(urls)) {
    targets = urls.filter((u) => onEntry.has(u));
  } else {
    targets = [...onEntry];
    const snap = await db().collection('journals').doc(docId).collection('replies').get();
    snap.forEach((r) => {
      const audio = r.get('audioUrl');
      if (typeof audio === 'string' && audio) targets.push(audio);
      replies.push(r.ref);
    });
  }

  let deleted = 0;
  for (const url of targets) {
    if (await destroy(url, apiKey, apiSecret)) deleted++;
  }
  // Discussion messages go with the entry (batches hold up to 500 writes).
  for (let i = 0; i < replies.length; i += 400) {
    const batch = db().batch();
    replies.slice(i, i + 400).forEach((ref) => batch.delete(ref));
    await batch.commit();
  }
  return res.status(200).json({ deleted, requested: targets.length });
}

/** Deletes one file by its delivery URL; only files in the app's own folder. */
async function destroy(url, apiKey, apiSecret) {
  // https://res.cloudinary.com/<cloud>/<image|video|raw>/upload/[transformations/][v123/]<public_id>.<ext>
  const m = url.match(/^https:\/\/res\.cloudinary\.com\/([^/]+)\/(image|video|raw)\/upload\/(.+)$/);
  if (!m) return false;
  const [, cloud, resourceType, rest] = m;
  const fromFolder = rest.indexOf(FOLDER);
  if (fromFolder < 0) return false;
  let publicId = rest.slice(fromFolder);
  // Raw files keep their extension in the public id; images and audio don't.
  if (resourceType !== 'raw') publicId = publicId.replace(/\.[^./]+$/, '');

  const timestamp = Math.floor(Date.now() / 1000);
  const signature = createHash('sha1')
    .update(`invalidate=true&public_id=${publicId}&timestamp=${timestamp}${apiSecret}`)
    .digest('hex');
  try {
    const r = await fetch(`https://api.cloudinary.com/v1_1/${cloud}/${resourceType}/destroy`, {
      method: 'POST',
      body: new URLSearchParams({
        public_id: publicId, timestamp: String(timestamp), invalidate: 'true', api_key: apiKey, signature,
      }),
    });
    const body = await r.json().catch(() => ({}));
    if (body.result !== 'ok' && body.result !== 'not found') console.warn('Destroy failed', publicId, body);
    return body.result === 'ok';
  } catch (err) {
    console.warn('Destroy request failed', publicId, err);
    return false;
  }
}

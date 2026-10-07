import { createHash } from 'node:crypto';
import { mutualPartner, verifyCaller } from '../lib/firebase.js';

// Parameters Cloudinary leaves out of the signature.
const UNSIGNED_KEYS = new Set(['file', 'cloud_name', 'resource_type', 'api_key', 'signature']);
// The only upload options the app is allowed to have signed.
const ALLOWED_KEYS = new Set(['folder', 'timestamp']);
const FOLDER = 'spiritual-tracker';

/**
 * POST /api/cloudinary-signature   Authorization: Bearer <Firebase ID token>
 * Body: { options: { folder: "spiritual-tracker", ... } }
 *
 * Signs an upload for a signed-in app user, so the Cloudinary API secret never ships in
 * the app and the account no longer needs an open "unsigned" upload preset.
 */
export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' });

  const uid = await verifyCaller(req);
  if (!uid) return res.status(401).json({ error: 'Sign-in required' });
  // Anyone can create an account, so only linked couples may upload.
  if (!(await mutualPartner(uid))) return res.status(403).json({ error: 'No linked partner' });

  const { CLOUDINARY_API_KEY: apiKey, CLOUDINARY_API_SECRET: apiSecret } = process.env;
  if (!apiKey || !apiSecret) return res.status(500).json({ error: 'Cloudinary is not configured' });

  const requested = (req.body && req.body.options) || {};
  const params = {};
  for (const [key, value] of Object.entries(requested)) {
    if (UNSIGNED_KEYS.has(key) || value === null || value === undefined || value === '') continue;
    if (!ALLOWED_KEYS.has(key)) {
      console.warn('Rejected upload option', key);
      return res.status(400).json({ error: `Option not allowed: ${key}` });
    }
    params[key] = String(value);
  }
  if (params.folder !== FOLDER) return res.status(400).json({ error: `folder must be "${FOLDER}"` });
  params.timestamp = String(Math.floor(Date.now() / 1000));

  const toSign = Object.keys(params).sort().map((k) => `${k}=${params[k]}`).join('&');
  const signature = createHash('sha1').update(toSign + apiSecret).digest('hex');

  return res.status(200).json({ signature, apiKey, timestamp: Number(params.timestamp) });
}

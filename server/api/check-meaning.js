import { verifyCaller } from '../lib/firebase.js';

const GROQ_URL = 'https://api.groq.com/openai/v1/chat/completions';
const MODEL = 'llama-3.3-70b-versatile';
const VERDICTS = new Set(['same', 'changed', 'missing']);

const SYSTEM = `You check Bible memorization. You get the real verses (numbered) and what the person
recited from memory. For EACH verse decide:
- "same": the recitation contains this verse with the same meaning. Different wording is fine:
  synonyms, word order, older or modern forms ("shall"/"will", "thee"/"you"), small words added
  or dropped, a different translation.
- "changed": the verse is there but the meaning differs: a key word swapped (a different name,
  object, place or number, e.g. "silver" for "gold", "earth" for "heaven"), a negation added or
  removed, two verses mixed together, or an important phrase left out.
- "missing": the verse, or most of it, is not in the recitation.
Be strict about meaning and relaxed about wording. Ignore spelling, capitals and punctuation.
For "changed" and "missing" give a short note (max 15 words) saying what differs, quoting words.
Reply with JSON only: {"verses":[{"n":1,"verdict":"same","note":""}]} with one entry per verse.`;

/**
 * POST /api/check-meaning   Authorization: Bearer <Firebase ID token>
 * Body: { verses: [{ n, text }], typed }
 *
 * Asks Groq whether a typed recitation keeps the meaning of each verse. Only signed-in app
 * users can call it, and sizes are capped, so the Groq key can't be used for anything else.
 */
export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' });

  const uid = await verifyCaller(req);
  if (!uid) return res.status(401).json({ error: 'Sign-in required' });

  const key = process.env.GROQ_API_KEY;
  if (!key) return res.status(503).json({ error: 'Meaning check is not set up' });

  const { verses, typed } = req.body || {};
  if (!Array.isArray(verses) || verses.length === 0 || verses.length > 80 || typeof typed !== 'string') {
    return res.status(400).json({ error: 'verses and typed are required' });
  }
  const clean = verses.map((v, i) => ({ n: Number(v?.n) || i + 1, text: String(v?.text ?? '').slice(0, 1200) }));
  const recited = typed.slice(0, 12000).trim();
  if (!recited) return res.status(400).json({ error: 'Nothing typed' });

  const user = 'VERSES:\n' + clean.map((v) => `${v.n}. ${v.text}`).join('\n') + '\n\nRECITED:\n' + recited;

  let reply;
  try {
    const r = await fetch(GROQ_URL, {
      method: 'POST',
      headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: MODEL,
        temperature: 0,
        response_format: { type: 'json_object' },
        messages: [{ role: 'system', content: SYSTEM }, { role: 'user', content: user }],
      }),
    });
    if (!r.ok) {
      const detail = (await r.text()).slice(0, 300);
      console.warn('Groq refused', r.status, detail);
      return res.status(502).json({ error: r.status === 429 ? 'Daily limit reached' : 'Meaning check failed', groqStatus: r.status, detail });
    }
    reply = JSON.parse((await r.json()).choices?.[0]?.message?.content || '{}');
  } catch (err) {
    console.warn('Groq call failed', err);
    return res.status(502).json({ error: 'Meaning check failed', detail: String(err?.message || err).slice(0, 300) });
  }

  // Keep only well-formed answers for the verses we asked about.
  const byN = new Map();
  for (const v of Array.isArray(reply?.verses) ? reply.verses : []) {
    const n = Number(v?.n);
    if (VERDICTS.has(v?.verdict)) byN.set(n, { n, verdict: v.verdict, note: String(v.note ?? '').slice(0, 160) });
  }
  return res.status(200).json({ verses: clean.map((v) => byN.get(v.n) || { n: v.n, verdict: 'unknown', note: '' }) });
}

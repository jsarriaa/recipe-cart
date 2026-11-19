// Publishing door of the community recipe book (docs/COMMUNITY.md, phase B).
//
// The app POSTs {"recipe": "<cpl-recipe/1 text>"} to /publish. The Worker checks the basics,
// limits how often one IP can publish, and commits the file to recipes/ in the book's GitHub
// repository with a token that only this Worker knows. The book's own Action then rebuilds
// index.json. The owner removes anything unwanted on GitHub.
//
// Settings (Cloudflare dashboard → the Worker → Settings → Variables and Secrets):
//   GITHUB_TOKEN  secret: fine-grained token, only the book repository, Contents: read and write
//   REPO          text:   owner/name of the book, for example jsarriaa/recetario
// Optional binding PUBLISH_LIMITER (Rate Limiting); without it a per-instance limit applies.

const MAX_CHARS = 60000;
const MAX_TITLE = 120;
const MAX_AUTHOR = 60;
const PER_MINUTE = 3;

const recent = new Map(); // ip -> timestamps (fallback limiter, per Worker instance)

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname !== "/publish") return json(404, { ok: false, error: "not_found" });
    if (request.method !== "POST") return json(405, { ok: false, error: "method" });

    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    if (!(await allowed(env, ip))) return json(429, { ok: false, error: "rate_limited" });

    let recipe;
    try {
      recipe = (await request.json()).recipe;
    } catch {
      return json(400, { ok: false, error: "bad_request" });
    }
    const checked = check(recipe);
    if (checked.error) return json(422, { ok: false, error: checked.error });

    const path = `recipes/${slug(checked.title)}-${randomHex(3)}.md`;
    const response = await fetch(`https://api.github.com/repos/${env.REPO}/contents/${path}`, {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${env.GITHUB_TOKEN}`,
        Accept: "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "recetario-publish-worker",
      },
      body: JSON.stringify({
        message: `feat: publish ${checked.title} (por ${checked.author})`,
        content: base64(checked.text),
      }),
    });
    if (!response.ok) {
      console.log("GitHub refused", response.status, await response.text());
      return json(502, { ok: false, error: "storage" });
    }
    return json(201, { ok: true, path });
  },
};

/** Basic shape checks; the book's index script does the full parsing and skips invalid files. */
function check(recipe) {
  if (typeof recipe !== "string") return { error: "bad_request" };
  const text = recipe.replace(/^﻿/, "").replace(/\r\n?/g, "\n");
  if (text.length > MAX_CHARS) return { error: "too_large" };
  if (!text.startsWith("---\n")) return { error: "not_a_recipe" };
  const end = text.indexOf("\n---", 4);
  if (end < 0) return { error: "not_a_recipe" };
  const front = text.slice(4, end);
  if (!/^format: *["']?cpl-recipe\/1["']? *$/m.test(front)) return { error: "not_a_recipe" };
  if (!/^ingredients:/m.test(front)) return { error: "not_a_recipe" };
  const title = field(front, "title");
  const author = field(front, "author");
  if (!title || title.length > MAX_TITLE) return { error: "title" };
  if (!author || author.length > MAX_AUTHOR) return { error: "author" };
  return { text, title, author };
}

function field(front, name) {
  const match = front.match(new RegExp(`^${name}: *(.+)$`, "m"));
  if (!match) return null;
  let value = match[1].trim();
  if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
    value = value.slice(1, -1);
  }
  return value.trim() || null;
}

async function allowed(env, ip) {
  if (env.PUBLISH_LIMITER) {
    const { success } = await env.PUBLISH_LIMITER.limit({ key: ip });
    return success;
  }
  const now = Date.now();
  const times = (recent.get(ip) || []).filter((t) => now - t < 60000);
  if (times.length >= PER_MINUTE) return false;
  times.push(now);
  recent.set(ip, times);
  return true;
}

function slug(title) {
  const s = title
    .normalize("NFD")
    .replace(/\p{M}+/gu, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 50)
    .replace(/-+$/g, "");
  return s || "receta";
}

function randomHex(bytes) {
  return [...crypto.getRandomValues(new Uint8Array(bytes))].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function base64(text) {
  let binary = "";
  for (const byte of new TextEncoder().encode(text)) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function json(status, body) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

// Reference plugin (tests only, never published): an HTML site with a login form, a session
// cookie, pages with a "next" link, and video links hidden with AES-128-CBC. PluginReferenceTest
// replaces BASE with its MockWebServer.
const BASE = 'https://scraper.example';
const KEY = '0123456789abcdef';
const IV = 'abcdef9876543210';

// Logs in once per session: the cookie jar keeps the session (and survives the runtime closing).
async function login() {
  const probe = await kino.fetch(BASE + '/session', { redirect: 'manual' });
  if (probe.status === 200) return;
  const r = await kino.fetch(BASE + '/login', {
    method: 'POST',
    body: { form: { user: kino.config.get('user'), password: kino.config.get('password') } },
    redirect: 'manual',
  });
  if (r.status === 401) throw kino.error('auth_required', 'usuario o contraseña incorrectos');
  if (r.status !== 302) throw kino.error('unavailable', 'el sitio respondió ' + r.status);
}

function cards(html) {
  return kino.html.select(html, 'a.card').map((a) => ({
    id: a.attrs['data-id'],
    ref: a.attrs['data-link'],
    title: a.text,
    kind: 'movie',
  }));
}

async function page(path) {
  await login();
  const r = await kino.fetch(BASE + path);
  if (r.status === 429) throw kino.error('rate_limited', 'demasiadas peticiones');
  if (!r.ok) throw kino.error('unavailable', 'el sitio respondió ' + r.status);
  const html = r.text();
  const next = kino.html.select(html, 'a.next').map((a) => a.attrs.href)[0];
  return { items: cards(html), next: next || undefined };
}

export async function search(query) {
  return (await page('/buscar?q=' + encodeURIComponent(query.q))).items;
}

export async function home() {
  const first = await page('/catalogo');
  return [{ id: 'catalogo', title: 'Catálogo', ref: '/catalogo', items: first.items }];
}

export async function browse(ref, cursor) {
  return page(cursor || ref);
}

export async function resolve(ref) {
  await null;
  const url = kino.crypto.decrypt('aes-128-cbc', { key: KEY, iv: IV, data: ref });
  return { url, mime: 'video/mp4' };
}

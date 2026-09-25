// Reference plugin (tests only, never published): a Jellyfin-like JSON API on the person's own
// server. The server address, user and password come from the plugin's settings; the token is
// kept in kino.storage. Each typed error comes from one HTTP status.
function base() {
  return String(kino.config.get('server')).replace(/\/+$/, '');
}

// The token belongs to one user on one server: after the person changes either in Configurar
// (which closes the runtime and clears the cookies, but not kino.storage), the old one is ignored.
const tokenKey = () => 'token:' + kino.config.get('user') + '@' + base();

async function token() {
  await null; // checks below may throw: never before the first await (engine rule)
  const saved = kino.storage.get(tokenKey());
  if (saved) return saved;
  const r = await kino.fetch(base() + '/auth', {
    method: 'POST',
    body: { json: { user: kino.config.get('user'), password: kino.config.get('password') } },
  });
  if (r.status === 401) throw kino.error('auth_required', 'usuario o contraseña incorrectos');
  if (!r.ok) throw kino.error('unavailable', 'el servidor respondió ' + r.status);
  const t = r.json().token;
  kino.storage.set(tokenKey(), t);
  return t;
}

const ERRORS = { 404: 'not_found', 429: 'rate_limited', 451: 'geo_blocked', 503: 'unavailable' };

async function api(path) {
  const r = await kino.fetch(base() + path, { headers: { 'X-Token': await token() } });
  if (r.status === 401) {
    kino.storage.remove(tokenKey());
    throw kino.error('auth_required', 'la sesión venció');
  }
  if (ERRORS[r.status]) throw kino.error(ERRORS[r.status], 'el servidor respondió ' + r.status);
  if (!r.ok) throw kino.error('unavailable', 'el servidor respondió ' + r.status);
  return r.json();
}

function item(x) {
  return {
    id: x.id,
    ref: x.id,
    title: x.title,
    kind: 'movie',
    year: x.year,
    poster: base() + '/img/' + encodeURIComponent(x.id),
    genres: x.genres,
    ids: x.tmdb ? { tmdb: x.tmdb } : undefined,
    badges: kino.config.get('hd') ? ['HD'] : undefined,
  };
}

export async function home() {
  const p = await api('/items?limit=10');
  return [{ id: 'all', title: 'En tu servidor', ref: 'all', items: p.items.map(item) }];
}

export async function browse(ref, cursor) {
  const p = await api('/items?limit=10' + (cursor ? '&cursor=' + encodeURIComponent(cursor) : ''));
  return { items: p.items.map(item), next: p.next || undefined };
}

export async function search(query) {
  const p = await api('/items?q=' + encodeURIComponent(query.q));
  return p.items.map(item);
}

export async function resolve(ref) {
  const x = await api('/items/' + encodeURIComponent(ref));
  return { url: base() + x.stream, mime: 'video/mp4', expiresInSeconds: 600 };
}

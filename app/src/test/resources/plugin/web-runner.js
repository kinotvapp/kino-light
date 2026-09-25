// Runs one web-corpus case (a function body) and returns its result as ASCII-only JSON text.
// Evaluated as source in every realm -- Node, a vm context, QuickJS -- so only realm-independent
// checks. ASCII only: on the JVM (unit tests) quickjs-kt mangles a non-BMP character on its way out
// of QuickJS (HotSpot's NewStringUTF); Android's runtime doesn't (measured), the tests do run there.
(function (body) {
  const normalize = (value) => {
    const tag = Object.prototype.toString.call(value);
    if (tag === '[object Uint8Array]') return { u8: Array.from(value) };
    if (Array.isArray(value)) return value.map(normalize);
    if (value && typeof value === 'object' && typeof value.decode === 'function') return { decoder: value.encoding };
    return value === undefined ? { undefined: true } : value;
  };
  const ascii = (s) => s.replace(/[\u007f-￿]/g, (c) => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0'));
  try {
    return ascii(JSON.stringify({ ok: normalize(new Function(body)()) }));
  } catch (e) {
    return ascii(JSON.stringify({ throws: e && e.name }));
  }
})

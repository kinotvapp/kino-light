#!/usr/bin/env node
// Parity corpus for app/src/main/resources/plugin/web.js (Kino's URL, URLSearchParams, atob, btoa,
// TextEncoder, TextDecoder). Each case is a function body; its return value is normalized to JSON.
//   node web-corpus.mjs            -> checks web.js (in a fresh vm context) against Node's own
//                                     implementations and rewrites web-corpus.json with Node's answers
// PluginWebGlobalsTest (JVM) runs the same bodies with web-runner.js inside QuickJS and compares to
// web-corpus.json.
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));

export const CASES = [
  // --- URL: absolute ---
  "const u = new URL('https://User:Pa ss@Example.COM:8443/a/./b/../c d?x=1 2&y=ä#frag ment'); return [u.href, u.origin, u.protocol, u.username, u.password, u.host, u.hostname, u.port, u.pathname, u.search, u.hash];",
  "return new URL('HTTP://example.com:80/').href;",
  "return new URL('https://example.com:443').href;",
  "return new URL('https://example.com:0443/x').port;",
  "return new URL('http://example.com/%7Efoo/%2e%2E/bar').pathname;",
  "return new URL('http://example.com/a/b/c/./../../g').pathname;",
  "return new URL('http://example.com/..').pathname;",
  "return new URL('http:\\\\\\\\example.com\\\\a\\\\b').href;",
  "return new URL('  https://example.com/x\\t\\n  ').href;",
  "return new URL('https://example.com/?q=\"<>\\'`').href;",
  "return new URL('https://example.com/#\"<>`{}').href;",
  "return new URL('https://example.com/p{a}^|').pathname;",
  "return new URL('https://ex%41mple.com/').host;",
  "return new URL('http://192.168.0.1:8096/web').host;",
  "return new URL('http://0x7f.1/').hostname;",
  "return new URL('http://3232235777/').hostname;",
  "return new URL('http://010.0.0.1/').hostname;",
  "return new URL('http://[::1]:8080/').host;",
  "return new URL('http://[2001:db8:0:0:0:0:2:1]/').hostname;",
  "return new URL('http://[0:0:0:0:0:ffff:192.168.1.1]/').hostname;",
  "return new URL('http://[fe80::1:0:0:1]/').hostname;",
  "return new URL('https://example.com./').hostname;",
  "return new URL('https://a.b.example.com/x?').href;",
  "return new URL('https://example.com/x#').href;",
  "return [new URL('https://example.com?a=b').pathname, new URL('https://example.com?a=b').href];",
  "return new URL('file:///C:/x/../y').href;",
  "return new URL('file://localhost/etc/hosts').href;",
  "return new URL('data:text/plain,hello world').href;",
  "const u = new URL('mailto:someone@example.com?subject=hi'); return [u.href, u.protocol, u.pathname, u.search, u.origin, u.host];",
  "const u = new URL('foo://host:99/p/../q?r#s'); return [u.href, u.host, u.port, u.pathname, u.origin];",
  "return new URL('wss://example.com:443/chat').href;",
  // --- URL: relative ---
  "return new URL('/b/c?d#e', 'https://example.com/a/x').href;",
  "return new URL('b/../c', 'https://example.com/a/x').href;",
  "return new URL('?q=1', 'https://example.com/a/x?old#h').href;",
  "return new URL('#top', 'https://example.com/a/x?old').href;",
  "return new URL('', 'https://example.com/a/x?old#h').href;",
  "return new URL('//cdn.example.org/lib.js', 'https://example.com/a').href;",
  "return new URL('../../../z', 'https://example.com/a/b/c').href;",
  "return new URL('http:foo', 'http://example.com/a/b').href;",
  "return new URL('https://other.example/q', 'https://example.com/a').href;",
  "return new URL('./?page=2', 'https://example.com/list/').href;",
  "return new URL('\\\\x', 'https://example.com/a/b').href;",
  // --- URL: invalid ---
  "return new URL('not a url').href;",
  "return new URL('/relative').href;",
  "return new URL('https://').href;",
  "return new URL('https://exa mple.com/').href;",
  "return new URL('https://example.com:99999/').href;",
  "return new URL('https://example.com:12a/').href;",
  "return new URL('http://[::1/').href;",
  "return new URL('http://1.2.3.4.5/').href;",
  "return new URL('http://256.0.0.1/').href;",
  "return new URL('x', 'mailto:a@b').href;",
  "return [URL.canParse('https://a.example'), URL.canParse('nope'), URL.canParse('p', 'https://a.example/')];",
  // --- URL: setters, searchParams, JSON ---
  "const u = new URL('https://example.com/a?x=1'); u.searchParams.append('y', 'dos tres'); u.searchParams.set('x', 'ñ'); return [u.href, u.search];",
  "const u = new URL('https://example.com/a?x=1&x=2'); u.searchParams.delete('x'); return [u.href, u.search];",
  "const u = new URL('https://example.com/a?x=1'); u.search = '?z=9 9'; return [u.href, u.searchParams.get('z')];",
  "const u = new URL('https://example.com/a?x=1'); u.search = ''; return u.href;",
  "const u = new URL('https://example.com/a'); u.hash = 'part two'; return u.href;",
  "const u = new URL('https://example.com/a#h'); u.hash = ''; return u.href;",
  "const u = new URL('https://example.com/a?q'); u.pathname = '/b c/d?e'; return u.href;",
  "const u = new URL('https://example.com/a'); u.href = 'https://other.example/z?k=v'; return [u.host, u.searchParams.get('k')];",
  "return JSON.stringify({ u: new URL('https://example.com/a b') });",
  "return String(new URL('https://example.com/p'));",
  // --- URLSearchParams ---
  "return new URLSearchParams('?a=1&b=2&a=3').getAll('a');",
  "return new URLSearchParams('a=%zz&b=%41&c=%E2%82%AC&d=%FF').toString();",
  "const p = new URLSearchParams('a=%E2%82%AC&b=%FF'); return [p.get('a'), p.get('b')];",
  "return new URLSearchParams('x=a+b&y=a%2Bb').get('x') + '|' + new URLSearchParams('x=a+b&y=a%2Bb').get('y');",
  "return new URLSearchParams({ q: 'a b&c', n: 5, u: undefined }).toString();",
  "return new URLSearchParams([['a', '1'], ['b', 'ñ']]).toString();",
  "return new URLSearchParams([['a']]).toString();",
  "const p = new URLSearchParams('b=2&a=1&c=3&a=0'); p.sort(); return p.toString();",
  "const p = new URLSearchParams('a=1&b=2&a=3'); p.set('a', 'x'); return p.toString();",
  "const p = new URLSearchParams('a=1&b=2&a=3'); p.delete('a', '3'); return [p.toString(), p.has('a'), p.has('a', '3'), p.size];",
  "const p = new URLSearchParams('a=1&b=2'); const out = []; p.forEach((v, k) => out.push(k + '=' + v)); return out;",
  "const p = new URLSearchParams('a=1&b=2'); return [[...p], [...p.keys()], [...p.values()], [...p.entries()]];",
  "return new URLSearchParams('=x&y=&&z').toString();",
  "return new URLSearchParams('a=*-._~!\\'()').toString();",
  "return new URLSearchParams('q=\\uD800').get('q').charCodeAt(0);",
  "return new URLSearchParams(new URLSearchParams('a=1')).toString();",
  "return new URLSearchParams('a=1').get('missing');",
  // --- atob / btoa ---
  "return btoa('Hello, Kino!');",
  "return [btoa(''), btoa('a'), btoa('ab'), btoa('abc'), btoa('\\u00ff\\u00fe\\u0000')];",
  "return btoa('ñ');",
  "return btoa('€');",
  "return atob('SGVsbG8sIEtpbm8h');",
  "return [atob('YQ'), atob('YWI='), atob(' Y W I = '), atob('')];",
  "return atob('YQ=').length;",
  "return atob('Y');",
  "return atob('Y*==');",
  "return atob('YQ===');",
  "return [...atob('/+8=')].map((c) => c.charCodeAt(0));",
  // --- TextEncoder / TextDecoder ---
  "return Array.from(new TextEncoder().encode('añ€😀'));",
  "return Array.from(new TextEncoder().encode('\\uD800x'));",
  "return [new TextEncoder().encoding, new TextDecoder().encoding, new TextDecoder('UTF8').encoding];",
  "return new TextDecoder().decode(new Uint8Array([0x61, 0xc3, 0xb1, 0xe2, 0x82, 0xac, 0xf0, 0x9f, 0x98, 0x80]));",
  "return new TextDecoder().decode(new Uint8Array([0xef, 0xbb, 0xbf, 0x68, 0x69]));",
  "return new TextDecoder('utf-8', { ignoreBOM: true }).decode(new Uint8Array([0xef, 0xbb, 0xbf, 0x68])).length;",
  "return new TextDecoder().decode(new Uint8Array([0xff, 0x61, 0xc3, 0x28, 0xe2, 0x82, 0x61, 0xf0, 0x9f, 0x98, 0xed, 0xa0, 0x80, 0xc0, 0xaf]));",
  "return new TextDecoder().decode(new Uint8Array([0x68, 0x69]).buffer);",
  "return new TextDecoder().decode(new DataView(new Uint8Array([0x6f, 0x6b]).buffer));",
  "return new TextDecoder().decode();",
  "return new TextDecoder('utf-8', { fatal: true }).decode(new Uint8Array([0x61, 0xff]));",
  "return new TextDecoder('x-nope');",
  "return new TextDecoder().decode('not bytes');",
  "return [new TextDecoder('utf-8', { fatal: true }).fatal, new TextDecoder().ignoreBOM];",
];

export const RUNNER = readFileSync(join(here, "web-runner.js"), "utf8");

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const nodeRun = (0, eval)(RUNNER);
  const context = vm.createContext({});
  vm.runInContext(readFileSync(join(here, "../../../main/resources/plugin/web.js"), "utf8"), context);
  const polyRun = vm.runInContext(RUNNER, context);
  let failed = 0;
  const expected = CASES.map((body, i) => {
    const want = nodeRun(body);
    const got = polyRun(body);
    if (got !== want) {
      failed++;
      console.error(`#${i} ${body}\n  node:   ${want}\n  web.js: ${got}`);
    }
    return { body, result: JSON.parse(want) };
  });
  writeFileSync(join(here, "web-corpus.json"), JSON.stringify(expected, null, 1) + "\n");
  console.error(`${CASES.length - failed}/${CASES.length} cases match Node`);
  process.exitCode = failed ? 1 : 0;
}

// 안드로이드 core 가 만든 QR 을 웹 검표 코드(live-core.js)로 검사하고, 웹이 만든 ET1 을 내보낸다.
import { readFileSync } from 'node:fs';
const L = await import(process.argv[2]);
const v = JSON.parse(readFileSync(process.argv[3], 'utf8'));
const out = {};
const shop = await L.parseShopQr(v.es1); out.es1 = !!shop;
const tk = await L.openTicket(v.et1); out.et1 = tk.ok ? 'ok' : tk.code;
const lr = await L.readLockQr(v.el2, { shopOf: (id) => (id === shop.id ? shop : null) }); out.el2 = lr.ok ? 'ok' : lr.code;
const er = await L.verifyEntryQr(v.eq2, { lockOf: (ck) => (ck === lr.lock.ck ? lr.lock : null), entered: () => null, gate: '1', now: v.now });
out.eq2 = er.ok ? 'ok' : er.code;
const er2 = await L.verifyEntryQr(v.eq2, { lockOf: () => lr.lock, entered: () => null, gate: '1', now: v.now + 11 }); out.eq2_expired = er2.code;
const er3 = await L.verifyEntryQr(v.eq2, { lockOf: () => lr.lock, entered: () => ({ at: 1 }), gate: '1', now: v.now }); out.eq2_again = er3.code;
// 웹 예매처가 표 발급 → 안드로이드가 열 수 있어야 함
const kp = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
const pk = L.b64u(new Uint8Array(await crypto.subtle.exportKey('raw', kp.publicKey)));
const now = Math.floor(Date.now() / 1000);
const terms = { v: 1, ck: 'tk_' + L.b64u(crypto.getRandomValues(new Uint8Array(16))), no: 'WEB-1', show: '웹 공연', venue: '웹 장소', seat: 'B-1', gate: '2', qty: 1,
  lat: 37.5714, lon: 127.0017, r: 300, from: now - 60, to: now + 3600, shop: await L.shopId(pk) };
out.webEt1 = await L.signTicket(terms, { priv: kp.privateKey, pk });
console.log(JSON.stringify(out));

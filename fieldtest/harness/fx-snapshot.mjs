#!/usr/bin/env node
// Writes the data API responses Fieldmart asks for in its JVM tests, from the vendored fixture generator (no server,
// no pictures): node fieldtest/harness/fx-snapshot.mjs --out fieldtest/ui/build/fx-snapshot
// File names are the request path and query with every non-alphanumeric run as '_' (see ui DataScreensTest).
import { mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const D = await import(join(here, '../shared/fixtures/data.mjs'));
const out = resolve(process.argv[process.argv.indexOf('--out') + 1] || join(here, '../ui/build/fx-snapshot'));
mkdirSync(out, { recursive: true });
const data = D.buildData();
const key = (p) => p.replace(/[^A-Za-z0-9]+/g, '_');
let n = 0;
const put = (path, body) => {
  writeFileSync(join(out, `${key(path)}.json`), JSON.stringify(body));
  n++;
};

put('/fx/api/categories', data.categories);
put('/fx/api/deals', data.deals);
put('/fx/api/hero', data.hero);
put('/fx/api/symbols', data.symbols);
put('/fx/api/countries', data.countries);
put('/fx/api/terms', data.terms);
for (let page = 0; page < 3; page++) put(`/fx/api/products?page=${page}&size=40`, D.productPage(data, { page, size: 40 }));
for (let c = 0; c < data.categories.length; c++) put(`/fx/api/products?page=0&size=40&category=${c}`, D.productPage(data, { page: 0, size: 40, category: c }));
const ids = new Set([3, 17, 42, ...data.deals.map((d) => d.productId), ...D.search(data, 'trail').items.map((p) => p.id)]);
for (const id of ids) put(`/fx/api/products/${id}`, { ...data.products[id - 1], ...data.details[id - 1] });
for (const id of ids) for (let page = 0; page < (id === 17 ? 10 : 1); page++) put(`/fx/api/products/${id}/reviews?page=${page}&size=50`, D.reviewPage(data, id, { page, size: 50 }));
for (let cursor = 0; cursor < D.FEED_COUNT; cursor += 100) put(`/fx/api/feed?cursor=${cursor}&size=100`, D.feedPage(data, { cursor, size: 100 }));
for (const tf of Object.keys(D.TIMEFRAMES)) put(`/fx/api/candles?symbol=${data.symbols[0].symbol}&tf=${tf}`, D.candles(data.symbols, 0, tf, data.seed, data.candleEnd));
for (const q of ['t', 'tr', 'tra', 'trai', 'trail']) put(`/fx/api/search?q=${q}`, D.search(data, q));
for (const q of ['F', 'FI', 'FIE', 'FIEL', 'FIELD', 'FIELD1']) put(`/fx/api/promos?q=${q}`, D.promos(data, q));
console.log(`${n} responses in ${out}`);

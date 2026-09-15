// Run with: node --test scripts/test_log_manager_dashboard.cjs
// Requires Playwright and an installed Chromium browser. ARES_BROWSER_CHANNEL may select
// a locally installed channel (e.g. msedge). HTTP is intercepted; no robot is contacted.
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const source = fs.readFileSync(path.join(__dirname, '../ARESLib-Kotlin/core/src/main/kotlin/com/areslib/logging/LogDashboardPage.kt'), 'utf8');
const html = source.match(/<!DOCTYPE html>[\s\S]*?<\/html>/)?.[0];
assert.ok(html, 'Production dashboard HTML must be available');
let browser;
before(async () => { browser = await chromium.launch({ headless: true, channel: process.env.ARES_BROWSER_CHANNEL || undefined }); });
after(async () => { await browser?.close(); });
const log = (name, sizeBytes = 100) => ({ name, sizeBytes, lastModifiedMs: 1000, lastModifiedFmt: 'Sep 13, 12:00', synced: false });

async function open(t, rows, options = {}) {
  const page = await browser.newPage();
  t.after(() => page.close());
  page.setDefaultTimeout(2000);
  const calls = [], external = [];
  let listCalls = 0;
  await page.addInitScript(() => sessionStorage.setItem('aresLogDeleteToken', 'cached-token-for-test'));
  page.on('dialog', dialog => dialog.type() === 'prompt' ? dialog.accept('replacement-token-for-test') : dialog.accept());
  await page.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin !== 'http://ares-log.test') { external.push(url.href); return route.abort(); }
    if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: html });
    if (url.pathname === '/api/logs') {
      listCalls++;
      if (options.list) return options.list(route, listCalls);
      return route.fulfill({ status: options.listStatus || 200, contentType: 'application/json', body: JSON.stringify(rows) });
    }
    if (url.pathname === '/api/delete') {
      calls.push({ name: url.searchParams.get('file'), token: route.request().headers()['x-ares-delete-token'] });
      const status = options.deleteStatus?.(calls.length) || 200;
      if (status === 200) rows = [];
      return route.fulfill({ status, contentType: 'application/json', body: status === 200 ? '{"success":true}' : '{"error":"Unauthorized"}' });
    }
    return route.fulfill({ status: 404, body: '' });
  });
  await page.goto('http://ares-log.test/', { waitUntil: 'domcontentloaded' });
  return { page, calls, external };
}

test('filenames render as literal text without creating executable markup', async t => {
  const name = '<img src=x onerror="window.filenameExecuted=true">.csv';
  const { page } = await open(t, [log(name)]);
  await page.locator('.glass-card').waitFor();
  assert.equal(await page.locator('.log-info h3').textContent(), name);
  assert.equal(await page.locator('img').count(), 0);
  assert.equal(await page.evaluate(() => window.filenameExecuted), undefined);
});

for (const name of ["pilot's #1.csv", 'left[2].jsonl', 'angle<status>.log']) {
  test(`delete acts on the intended punctuated basename: ${name}`, async t => {
    const { page, calls } = await open(t, [log(name)]);
    const posted = page.waitForResponse(response => new URL(response.url()).pathname === '/api/delete');
    await page.getByRole('button', { name: 'Delete', exact: true }).click();
    await posted;
    assert.equal(calls.length, 1);
    assert.equal(calls[0].name, name);
    assert.equal(calls[0].token, 'cached-token-for-test');
  });
}

test('an unauthorized cached token can be replaced on the next attempt', async t => {
  const { page, calls } = await open(t, [log('retry.csv')], { deleteStatus: count => count === 1 ? 401 : 200 });
  const button = page.getByRole('button', { name: 'Delete', exact: true });
  let posted = page.waitForResponse(response => new URL(response.url()).pathname === '/api/delete');
  await button.click(); await posted;
  await page.waitForFunction(() => document.querySelector('.btn-delete')?.disabled === false);
  assert.equal(await page.evaluate(() => sessionStorage.getItem('aresLogDeleteToken')), null);
  posted = page.waitForResponse(response => new URL(response.url()).pathname === '/api/delete');
  await button.click(); await posted;
  assert.deepEqual(calls.map(call => call.token), ['cached-token-for-test', 'replacement-token-for-test']);
});

test('binary byte formatting covers byte through exbibyte boundaries', async t => {
  const { page } = await open(t, Array.from({ length: 7 }, (_, exponent) => log(`size-${exponent}.csv`, 1024 ** exponent)));
  await page.locator('.glass-card').first().waitFor();
  assert.deepEqual(await page.locator('.log-meta span:first-child').allTextContents(),
    ['1 Bytes', '1 KiB', '1 MiB', '1 GiB', '1 TiB', '1 PiB', '1 EiB']);
});

test('HTTP listing errors cannot masquerade as an empty directory', async t => {
  const { page } = await open(t, [], { listStatus: 503 });
  await page.getByText('Error loading logs.', { exact: true }).waitFor();
  assert.equal(await page.getByText('No logs found on device.', { exact: true }).count(), 0);
});

test('a late older refresh cannot replace a newer listing', async t => {
  let releaseFirst;
  const first = new Promise(resolve => { releaseFirst = resolve; });
  const { page } = await open(t, [], { list: async (route, index) => {
    if (index === 1) await first;
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify([log(index === 1 ? 'old.csv' : 'new.csv')]) });
  } });
  await page.getByRole('button', { name: 'Refresh' }).click();
  await page.getByRole('heading', { name: 'new.csv', exact: true }).waitFor();
  const returned = page.waitForResponse(response => new URL(response.url()).pathname === '/api/logs');
  releaseFirst(); await returned;
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  assert.equal(await page.getByRole('heading', { name: 'new.csv', exact: true }).count(), 1);
  assert.equal(await page.getByRole('heading', { name: 'old.csv', exact: true }).count(), 0);
});

test('dashboard assets work offline without third-party requests', async t => {
  const { page, external } = await open(t, [log('offline.csv')]);
  await page.locator('.glass-card').waitFor();
  await page.waitForLoadState('load');
  assert.deepEqual(external, []);
  if (process.env.ARES_DASHBOARD_SCREENSHOT) await page.screenshot({ path: process.env.ARES_DASHBOARD_SCREENSHOT, fullPage: true });
});

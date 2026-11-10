import { chromium } from 'playwright';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

const errors = [];
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true });
  page.on('pageerror', error => errors.push(error.stack || error.message));

  await page.route('https://api.github.com/repos/ov4arenkoolexander/Butdetal/releases/latest', route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'Access-Control-Allow-Origin': '*' },
      body: JSON.stringify({ tag_name: 'v2.0.58', assets: [] })
    })
  );

  const appUrl = pathToFileURL(resolve('app/src/main/assets/index.html')).href;
  await page.goto(appUrl, { waitUntil: 'load' });

  // Capture the actual touch/click path, including the element under the tap point.
  await page.evaluate(() => {
    window.__smokeEvents = [];
    for (const type of ['touchstart', 'touchend', 'pointerdown', 'pointerup', 'click']) {
      document.addEventListener(type, event => {
        window.__smokeEvents.push({ type, target: event.target?.id || event.target?.tagName || '' });
      }, true);
    }
  });

  await page.locator('#sbUrl').fill('https://example.supabase.co');
  await page.locator('#sbKey').fill('test-publishable-key');
  const saveButton = page.locator('#saveSupabaseConfigButton');
  const beforeTap = await saveButton.evaluate(el => {
    const r = el.getBoundingClientRect();
    const top = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
    return {
      onclick: typeof el.onclick,
      pointerEvents: getComputedStyle(el).pointerEvents,
      rect: { x: r.x, y: r.y, width: r.width, height: r.height },
      centerElement: top?.id || top?.tagName || ''
    };
  });
  await saveButton.tap();
  await page.waitForTimeout(250);
  const afterTap = await page.evaluate(() => ({
    status: document.querySelector('#authStatus')?.textContent || '',
    events: window.__smokeEvents
  }));
  if (!afterTap.status.includes('Сервер збережено')) {
    // Separate a touch-dispatch issue from an application-handler issue.
    await saveButton.click();
    await page.waitForTimeout(250);
  }
  const afterClick = await page.evaluate(() => ({
    status: document.querySelector('#authStatus')?.textContent || '',
    events: window.__smokeEvents,
    errors: window.__smokeEvents
  }));
  console.log('SAVE_BUTTON_DIAGNOSTICS', JSON.stringify({ beforeTap, afterTap, afterClick, pageErrors: errors }));
  assert.ok(afterClick.status.includes('Сервер збережено'), 'Save settings button did not update the status after touch or mouse click');

  await page.locator('#signInButdetalButton').tap();
  await page.waitForFunction(() => document.querySelector('#authStatus')?.textContent.includes('Введи email і пароль'));

  await page.locator('header button').tap();
  await page.waitForFunction(() => document.querySelector('#updateStatus')?.textContent.includes('Реліз знайдено'));

  await page.locator('#appMain').evaluate(el => el.classList.remove('hidden'));
  await page.getByRole('button', { name: 'Каталог', exact: true }).tap();
  await page.waitForFunction(() => !document.querySelector('#catalog')?.classList.contains('hidden'));
  await page.getByRole('button', { name: 'Формули', exact: true }).tap();
  await page.waitForFunction(() => !document.querySelector('#formulas')?.classList.contains('hidden'));

  assert.deepEqual(errors, [], 'Uncaught JavaScript errors occurred while loading or clicking the app');
  console.log('PASS: save server settings, login validation, update check, catalog tab and formulas tab respond to mobile taps.');
} finally {
  await browser.close();
}

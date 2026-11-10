import { chromium } from 'playwright';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

const errors = [];
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage();
  page.on('pageerror', error => errors.push(error.stack || error.message));

  await page.route('https://api.github.com/repos/ov4arenkoolexander/Butdetal/releases/latest', route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ tag_name: 'v2.0.58', assets: [] })
    })
  );

  const appUrl = pathToFileURL(resolve('app/src/main/assets/index.html')).href;
  await page.goto(appUrl, { waitUntil: 'load' });

  // Check the three controls the user reported as unresponsive.
  await page.locator('#sbUrl').fill('https://example.supabase.co');
  await page.locator('#sbKey').fill('test-publishable-key');
  await page.locator('#saveSupabaseConfigButton').click();
  await page.waitForFunction(() =>
    document.querySelector('#authStatus')?.textContent.includes('Сервер збережено')
  );

  await page.locator('#signInButdetalButton').click();
  await page.waitForFunction(() =>
    document.querySelector('#authStatus')?.textContent.includes('Введи email і пароль')
  );

  await page.locator('header button').click();
  await page.waitForFunction(() =>
    document.querySelector('#updateStatus')?.textContent.includes('Реліз знайдено')
  );

  // Exercise ordinary in-app navigation after exposing the signed-in area.
  await page.locator('#appMain').evaluate(el => el.classList.remove('hidden'));
  await page.getByRole('button', { name: 'Каталог', exact: true }).click();
  await page.waitForFunction(() => !document.querySelector('#catalog')?.classList.contains('hidden'));
  await page.getByRole('button', { name: 'Формули', exact: true }).click();
  await page.waitForFunction(() => !document.querySelector('#formulas')?.classList.contains('hidden'));

  assert.deepEqual(errors, [], 'Uncaught JavaScript errors occurred while loading or clicking the app');
  console.log('PASS: save server settings, login validation, update check, catalog tab and formulas tab respond to clicks.');
} finally {
  await browser.close();
}

import assert from 'node:assert/strict';
import test from 'node:test';
import { chromium } from 'playwright';
const baseURL = process.env.LABFLOW_BASE_URL ?? 'http://127.0.0.1:5181';
test('sidebar-hit-area', async () => {
const browser=await chromium.launch({channel:'msedge',headless:true});
const page=await browser.newPage({viewport:{width:1440,height:900}});
try{
await page.goto(`${baseURL}/#/projects/vision/runs`);
const nav=page.getByRole('navigation',{name:'프로젝트 바로가기'});
await nav.waitFor();
const row=page.locator('.project-nav-item').nth(1);
let box=await row.boundingBox();
assert.ok(box);
const visitLanguage=()=>page.waitForURL('**/#/projects/language/overview');
const home=page.locator('.project-nav-item').first();
await page.mouse.click(box.x+box.width-35,box.y+22);
await visitLanguage();
await home.locator('.project-initial').click();
await page.waitForURL('**/#/projects/vision/overview');
await row.locator('.project-nav-count').click({delay:650});
await visitLanguage();
await home.focus();
await page.keyboard.press('Enter');
await page.waitForURL('**/#/projects/vision/overview');
box=await nav.boundingBox();
assert.ok(box);
await page.mouse.move(box.x+box.width-34,box.y+220);
await page.mouse.down();
await page.mouse.move(box.x+box.width-34,box.y+70,{steps:12});
await page.mouse.up();
assert.ok(await nav.evaluate(el=>el.scrollTop)>100);
assert.ok(page.url().endsWith('/vision/overview'));
await nav.evaluate(el=>el.scrollTop=0);
await row.click();
await visitLanguage();
console.log('PASS: row click, stationary hold, keyboard, drag and subsequent click');
}finally{await browser.close()}
});

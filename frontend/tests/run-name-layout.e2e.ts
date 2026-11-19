import assert from 'node:assert/strict';
import test from 'node:test';
import { chromium } from 'playwright';
const baseURL = process.env.LABFLOW_BASE_URL ?? 'http://127.0.0.1:5181';
test('run-name-layout', async () => {
 const browser=await chromium.launch({channel:'msedge',headless:true});
 const page=await browser.newPage({viewport:{width:1440,height:900}});
 try{
   await page.route('**/src/sampleData.ts*',async route=>{
     const response=await route.fetch();
     const body=(await response.text()).replace(/(['"])resnet50-cosine\1/,JSON.stringify('resnet50-'+ 'long-experiment-name-'.repeat(20)));
     await route.fulfill({response,body});
   });
   await page.goto(`${baseURL}/#/projects/vision/runs`);
   const first=page.locator('.runs-table .run-link').first();
   await first.waitFor();
   assert.ok((await first.getAttribute('title'))?.includes('long-experiment-name-'),'Long-name fixture was injected');
   assert.ok(await first.evaluate(el=>el.scrollWidth>el.clientWidth),'Long name is constrained');
   assert.equal(await first.evaluate(el=>getComputedStyle(el).textOverflow),'ellipsis');
   assert.equal(await first.getAttribute('title'),await first.innerText());
   const positions=()=>page.locator('.runs-table tbody tr').first().locator('td').evaluateAll(cells=>cells.slice(2).map(cell=>cell.getBoundingClientRect().x));
   const expected=await positions();
   for(const state of ['진행 중','완료','실패','전체']){
     await page.getByRole('button',{name:new RegExp('^'+state+' ')}).click();
     assert.deepEqual(await positions(),expected,state+' column positions');
   }
   await page.setViewportSize({width:390,height:844});
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
   console.log('PASS: long name truncation and full title; identical column positions in all four filters; mobile scrolling stays within table.');
 } finally {await browser.close()}
});

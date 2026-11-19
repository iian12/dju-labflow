import assert from 'node:assert/strict';
import test from 'node:test';
import { chromium } from 'playwright';
const baseURL = process.env.LABFLOW_BASE_URL ?? 'http://127.0.0.1:5181';
test('run-scroll', async () => {
 const browser=await chromium.launch({channel:'msedge',headless:true});
 const page=await browser.newPage({viewport:{width:1440,height:900}});
 try{
 await page.goto(`${baseURL}/#/projects/stress-demo-1/runs`);
 const region=page.getByRole('region',{name:'Run 실험 기록 목록'});
 await region.waitFor();
 assert.equal(await page.locator('.run-link').count(),240);
 assert.equal(await page.getByRole('button',{name:'다음',exact:true}).count(),0);
 assert.equal(await region.evaluate(el=>getComputedStyle(el).scrollbarWidth),'none');
 await region.hover();await page.mouse.wheel(0,700);
 await page.waitForFunction(()=>(document.querySelector('.runs-scroll')?.scrollTop ?? 0)>0);
 await region.evaluate(el=>el.scrollTop=0);
 const box=await region.boundingBox();
 assert.ok(box);
 const x=box.x+370,y=box.y+220;
 await page.mouse.move(x,y);await page.mouse.down();await page.mouse.move(x,y-130,{steps:12});await page.mouse.up();
 assert.ok(await region.evaluate(el=>el.scrollTop)>100,'Drag scroll moved list');
 assert.ok(page.url().endsWith('/runs'),'Dragging must not open details');
 await region.evaluate(el=>el.scrollTop=0);
 await page.locator('.runs-table input[type=checkbox]').first().check();
 assert.equal(await page.locator('.runs-table input:checked').count(),1);
 await page.locator('.run-link').first().click();
 await page.locator('.run-heading').waitFor();
 await page.getByRole('link',{name:'← 실험 기록으로'}).click();
 await page.getByRole('button',{name:/실패 /}).click();
 assert.equal(await region.evaluate(el=>el.scrollTop),0);
 await page.setViewportSize({width:390,height:844});
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
 console.log('PASS: 240 rows without pagination; hidden scrollbar; wheel and hold-drag scrolling; checkbox and Run links; filter reset and mobile.');
 }finally{await browser.close()}
});

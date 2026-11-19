import assert from 'node:assert/strict';
import test from 'node:test';
import { chromium } from 'playwright';
const baseURL = process.env.LABFLOW_BASE_URL ?? 'http://127.0.0.1:5181';
test('demo-volume', async () => {
 const {demoProjects,includeDemoProjects}=await import('../src/demoProjects.ts');
 const {getRuns}=await import('../src/sampleData.ts');
 assert.equal(demoProjects.length,240);
 assert.equal(includeDemoProjects(demoProjects).length,240);
 for(const project of demoProjects){
 assert.ok(project.name.length>=1&&project.name.length<=32);
 const runs=getRuns(project);
 assert.ok(runs.length>=1&&runs.length<=240);
 assert.ok(runs.every(run=>run.name.length>=1&&run.name.length<=32));
 }
 const browser=await chromium.launch({channel:'msedge',headless:true});
 try{
 const page=await browser.newPage({viewport:{width:1440,height:900}});
 const errors: string[] = [];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`${baseURL}/#/projects/stress-demo-1/runs`);
 await page.locator('.run-link').first().waitFor();
 assert.equal(await page.locator('.project-nav-item').count(),243);
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
 assert.ok(await page.locator('.project-nav').evaluate(el=>el.scrollHeight>el.clientHeight));
 assert.equal(await page.locator('.run-link').count(),240);
 await page.locator('.project-nav-item').last().click();
 await page.waitForURL('**/#/projects/stress-demo-240/overview');
 await page.getByRole('navigation',{name:'프로젝트 화면'}).getByRole('link',{name:/실험 기록/}).click();
 await page.locator('.run-link').first().waitFor();
 for(const width of [1024,390]){
 await page.setViewportSize({width,height:844});
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
 }
 assert.deepEqual(errors,[]);
 console.log('PASS: 240 projects; 1–32 character project and Run names; maximum 240 Runs; sidebar scroll; scroll list; desktop/tablet/mobile overflow.');
 }finally{await browser.close()}
});

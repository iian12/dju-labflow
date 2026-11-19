import assert from 'node:assert/strict';
import test from 'node:test';
import { chromium } from 'playwright';
const base = process.env.LABFLOW_BASE_URL ?? 'http://127.0.0.1:5181';
const project = '9223372036854775800', first = '9223372036854775801', second = '9223372036854775802';
const when = '2026-11-19T00:00:00Z';
const runs = [first,second].map((runId,index) => ({ runId, projectId: project, name: ['recorded-baseline','recorded-candidate'][index], status:'COMPLETED', metadata:{framework:'actual-framework'}, startedAt:when, endedAt:when, error:null }));
const saved = { projectId:project, createdBy:'1', name:'Stored project', description:'서버에서 불러온 설명', visibility:'PRIVATE', createdAt:when, updatedAt:null, version:0 };
const point = (value:number, step=10) => ({id:'2',key:'validation_score',step,value,epoch:1,elapsedTime:1,recordedAt:when});

test('workspace uses API data for list, dynamic metrics, comparisons and session logout', async () => {
  const browser=await chromium.launch({channel:'msedge',headless:true});
  const page=await browser.newPage({viewport:{width:1440,height:900}});
  const calls:string[]=[], errors:string[]=[];
  page.on('pageerror',error=>errors.push(error.message));
  let authenticated=true;
  await page.route('**/api/v1/**', async route => {
    const url=new URL(route.request().url()); calls.push(url.pathname+url.search);
    if(url.pathname.endsWith('/auth/me')) return route.fulfill({status:authenticated?200:401,json:authenticated?{userId:'1',name:'Actual reader',email:'reader@example.com'}:{}});
    if(url.pathname.endsWith('/auth/csrf')) return route.fulfill({headers:{'Set-Cookie':'XSRF-TOKEN=csrf; Path=/'},json:{}});
    if(url.pathname.endsWith('/auth/logout')) {authenticated=false;return route.fulfill({status:204});}
    if(url.pathname.endsWith('/auth/refresh')) return route.fulfill({status:401,json:{}});
    if(url.pathname==='/api/v1/projects') return route.fulfill({json:{items:[saved],page:0,size:100,totalPages:1,totalElements:1}});
    if(url.pathname.endsWith(`/projects/${project}`)) return route.fulfill({json:saved});
    if(url.pathname.endsWith('/runs')) return route.fulfill({json:{items:runs,page:0,size:100,totalPages:1,totalElements:2,counts:{COMPLETED:2,RUNNING:0,FAILED:0}}});
    if(url.pathname.endsWith('/summaries')) return route.fulfill({json:runs.map((run,index)=>({runId:run.runId,lastStep:10,metrics:[point(index?.7:.8)]}))});
    if(url.pathname.endsWith('/metric-series')) return route.fulfill({json:{latestMetric:point(.8),series:{points:[point(.5,0),point(.8)],sampled:false,totalPoints:2}}});
    if(url.pathname.endsWith('/compare')) {
      assert.equal(url.searchParams.get('metricKey'),'validation_score');
      assert.equal(url.searchParams.get('runIds'),`${first},${second}`);
      const baselineId=url.searchParams.get('baselineRunId');
      return route.fulfill({json:{metricKey:'validation_score',baselineRunId:baselineId,commonStep:10,availableMetrics:['validation_score'],runs:runs.map((run,index)=>({...run,lastStep:10,latestMetric:point(index?.7:.8),commonStepMetric:point(index?.7:.8),deltaFromBaseline:run.runId===baselineId?0:baselineId===first?-.1:.1,series:{points:[point(.5,0),point(index?.7:.8)],sampled:false,totalPoints:2}})),parameters:url.searchParams.get('differencesOnly')==='true'?[]:[{key:'optimizer',values:['Adam','Adam'],different:false}]}});
    }
    return route.fulfill({status:404,json:{message:'unexpected request'}});
  });
  try {
    await page.addInitScript(()=>localStorage.setItem('lf-projects-v1',JSON.stringify([{id:'vision',name:'STALE DEMO'}])));
    await page.goto(`${base}/#/projects`);
    await page.getByRole('heading',{name:'Stored project',exact:true}).waitFor();
    assert.equal(await page.getByText('STALE DEMO',{exact:true}).count(),0);
    await page.locator('.project-card').click();
    await page.getByRole('link',{name:'recorded-baseline',exact:true}).waitFor();
    assert.equal(await page.getByLabel('표시 지표',{exact:true}).inputValue(),'validation_score');
    await page.getByLabel('Run 검색').fill('candidate');
    assert.equal(await page.locator('tbody tr').count(),1);
    await page.getByLabel('Run 검색').fill('');
    await page.getByLabel('recorded-baseline 선택',{exact:true}).check();
    await page.getByLabel('recorded-candidate 선택',{exact:true}).check();
    await page.getByRole('button',{name:'결과 비교 →'}).click();
    await page.getByRole('cell',{name:'-0.1',exact:true}).waitFor();
    await page.getByLabel('기준 Run',{exact:true}).selectOption(second);
    await page.getByRole('cell',{name:'+0.1',exact:true}).waitFor();
    await page.getByLabel('다른 설정만 보기').check();
    await page.getByText('선택한 Run의 설정이 모두 같습니다.').waitFor();
    await page.setViewportSize({width:390,height:844});
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
    await page.getByRole('button',{name:'로그아웃',exact:true}).click();
    await page.waitForURL('**/#/login');
    assert.equal(authenticated,false);
    assert.ok(calls.some(path=>path.includes('/runs/compare?')));
    assert.deepEqual(errors,[]);
  } finally {await browser.close();}
});

test('API failures never fall back to demo projects and recovery loads real empty state', async () => {
  const browser=await chromium.launch({channel:'msedge',headless:true});
  const page=await browser.newPage();let fail=true;
  await page.route('**/api/v1/**',route=>{
    if(route.request().url().endsWith('/auth/me'))return route.fulfill({json:{userId:'1',name:'Reader',email:'a@example.com'}});
    return route.fulfill({status:fail?503:200,json:fail?{message:'일시적인 서버 오류'}:{items:[],page:0,size:100,totalPages:1,totalElements:0}});
  });
  try {
    await page.goto(`${base}/#/projects`);
    await page.getByRole('alert').waitFor();
    assert.equal(await page.locator('.project-card').count(),0);
    fail=false;await page.getByRole('button',{name:'다시 불러오기'}).click();
    await page.getByRole('heading',{name:'첫 프로젝트를 만들어 보세요'}).waitFor();
  } finally{await browser.close();}
});

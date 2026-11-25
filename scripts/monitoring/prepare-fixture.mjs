import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile, access } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root=fileURLToPath(new URL('../../',import.meta.url));
const base=process.argv[2]??process.env.LABFLOW_BASE_URL;
assert.ok(base,'Pass the deployment HTTPS base URL as the first argument');
const url=new URL(base);assert.equal(url.protocol,'https:');assert.ok(!url.username&&!url.password&&!url.search&&!url.hash);
const directory=path.join(root,'.external-access','deployment-test');
await mkdir(directory,{recursive:true,mode:0o700});
try{await access(path.join(directory,'credentials.json'));throw new Error('Existing test credentials must be cleaned up first');}catch(error){if(error.code!=='ENOENT')throw error;}
const executable=name=>process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',name+(process.platform==='win32'?'.exe':'')):name;
const jar=path.join(root,'backend/build/libs/labflow-0.0.1-SNAPSHOT.jar');
const entries=execFileSync(executable('jar'),['tf',jar],{encoding:'utf8'}).split(/\r?\n/)
  .filter(name=>/^BOOT-INF\/lib\/(spring-security-crypto|commons-logging)-[^/]+\.jar$/.test(name));
assert.equal(entries.length,2);
execFileSync(executable('jar'),['xf',jar,...entries],{cwd:directory});
const password=randomBytes(24).toString('base64url');
const hash=execFileSync(executable('java'),['-cp',entries.map(entry=>path.join(directory,entry)).join(path.delimiter),fileURLToPath(new URL('HashPassword.java',import.meta.url))],{input:password,encoding:'utf8'}).trim();
assert.match(hash,/^\$2[aby]\$/);
const id='9876543210001',email=`deployment-smoke-${Date.now()}@example.invalid`;
await writeFile(path.join(directory,'credentials.json'),JSON.stringify({id,email,password,base:url.origin}),{mode:0o600});
await writeFile(path.join(directory,'seed.sql'),`INSERT INTO users(id,version,email,encoded_password,name,role,account_status) VALUES(${id},0,'${email}','${hash}','Deployment smoke test','USER','ACTIVE');\n`,{mode:0o600});
const parts=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Seoul',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).formatToParts(new Date());
const part=name=>parts.find(value=>value.type===name).value;
const cycleId=part('year')+part('month')+part('day')+'-'+part('hour')+part('minute')+part('second');
const cycleDirectory=path.join(root,'.external-access','load-tests',cycleId);
await mkdir(cycleDirectory,{recursive:true});
await mkdir(path.join(root,'.external-access','monitoring'),{recursive:true});
await writeFile(path.join(root,'.external-access','monitoring','current-cycle.txt'),cycleDirectory);
console.log('Prepared isolated test credentials and seed.sql. No plaintext credentials printed.');
console.log('Cycle directory: '+cycleDirectory);

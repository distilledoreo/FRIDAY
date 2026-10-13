const {test}=require('node:test');
const assert=require('node:assert/strict');
const {JSDOM}=require('jsdom');
const fs=require('node:fs');
const path=require('node:path');
const root=path.join(__dirname,'..');
async function client(){
  const dom=new JSDOM(fs.readFileSync(path.join(root,'index.html'),'utf8'),{url:'https://friday.test',runScripts:'outside-only',pretendToBeVisual:true});
  const w=dom.window, db=new Map(),timers=[],requests=[];
  let settings={use_memories:true,use_history:true,archive_chats:true}, facts=[];
  w.structuredClone=structuredClone;w.AbortSignal=AbortSignal;
  w.matchMedia=()=>({matches:false,addEventListener(){}});
  w.setInterval=fn=>{timers.push(fn);return timers.length;};w.clearInterval=()=>{};
  let intercept=null;
  w.fetch=async(url,options={})=>{
    requests.push({url,options});
    if(intercept){const response=await intercept(url,options);if(response)return response;}
    let result;
    if(url==='/workspace/items')result=[...db.values()];
    else if(url==='/workspace/jobs')result=[];
    else if(url==='/workspace/memory')result={settings,memories:facts.length};
    else if(url.startsWith('/workspace/memory/memories?'))result=facts;
    else if(url==='/workspace/memory/settings'){settings={...settings,...JSON.parse(options.body)};result=settings;}
    else if(url==='/workspace/memory/memories'){facts.push({id:'one',...JSON.parse(options.body)});result=facts.at(-1);}
    else if(url.startsWith('/workspace/items/')&&options.method==='PUT'){
      const id=url.split('/').at(-1),body=JSON.parse(options.body),old=db.get(id);
      if((old?.revision||0)!==body.revision)return {ok:false,status:409,text:async()=>''};
      result={id,...body,revision:body.revision+1};db.set(id,result);
    }else throw new Error('Unexpected route '+url);
    return {ok:true,status:200,json:async()=>structuredClone(result)};
  };
  w.eval(fs.readFileSync(path.join(root,'ui.js'),'utf8'));
  const click=async id=>w.document.getElementById(id).onclick();
  const input=(id,value)=>{w.document.getElementById(id).value=value;};
  const connect=async()=>{input('token','test');await click('connect');};
  return {w,db,timers,requests,click,input,connect,intercept:fn=>{intercept=fn;},close:()=>w.close()};
}
const chat=(id='chat',title='Original',revision=1)=>({id,revision,payload:{chat:JSON.stringify({version:1,summary:{id,title,createdAt:1,updatedAt:1},messages:[{role:'user',content:'Hello'}]})}});
const flush=()=>new Promise(resolve=>setImmediate(resolve));
test('automatic refresh updates history and tombstones without discarding a draft',async()=>{
  const c=await client();try{
    c.db.set('chat',chat());await c.connect();c.w.document.querySelector('.chat-link').click();c.input('draft','Unsent draft');
    c.db.set('chat',chat('chat','Renamed on Android',2));await c.timers[0]();await flush();
    assert.equal(c.w.document.getElementById('title').textContent,'Renamed on Android');
    assert.equal(c.w.document.getElementById('draft').value,'Unsent draft');
    c.db.set('chat',{id:'chat',revision:3,deleted:true,payload:{}});await c.click('refresh');
    assert.equal(c.w.document.getElementById('title').textContent,'New chat');
    assert.equal(c.w.document.getElementById('draft').value,'Unsent draft');
  }finally{c.close();}
});
test('remote preferences apply and saving a field preserves unrelated preferences',async()=>{
  const c=await client();try{
    c.db.set('preferences-theme',{id:'preferences-theme',revision:2,payload:{preferences:'{"value":"LIGHT"}'}});
    c.db.set('preferences-display_name',{id:'preferences-display_name',revision:3,payload:{preferences:'{"value":"Alex"}'}});
    await c.connect();assert.equal(c.w.document.body.dataset.theme,'light');
    const input=c.w.document.getElementById('pref-theme');input.value='DARK';await input.onchange();
    assert.equal(c.db.get('preferences-theme').revision,3);assert.equal(c.db.get('preferences-display_name').revision,3);
    assert.equal(c.w.document.body.dataset.theme,'dark');
    assert.equal(c.requests.some(r=>r.options.body?.includes('test')),false);
  }finally{c.close();}
});
test('memory writes and controls use the PC store',async()=>{
  const c=await client();try{
    await c.connect();c.input('memory','Prefer concise replies');await c.click('saveMemory');
    assert.match(c.w.document.getElementById('memories').textContent,/Prefer concise replies/);
    const checkbox=c.w.document.querySelector('#memorySettings input');checkbox.checked=false;await checkbox.onchange();
    assert(c.requests.some(r=>r.url==='/workspace/memory/settings'&&JSON.parse(r.options.body).use_memories===false));
    assert.equal(c.db.has('knowledge'),false);
  }finally{c.close();}
});
test('stale writes fail visibly and preserve the local chat and draft',async()=>{
  const c=await client();try{
    c.db.set('chat',chat());await c.connect();c.w.document.querySelector('.chat-link').click();c.input('draft','Keep me');
    c.db.set('chat',chat('chat','Remote edit',2));c.w.prompt=()=> 'My rename';await c.click('rename');
    assert.match(c.w.document.getElementById('status').textContent,/Changed on another client/);
    assert.equal(c.w.document.getElementById('title').textContent,'Original');
    assert.equal(c.w.document.getElementById('draft').value,'Keep me');
  }finally{c.close();}
});
test('locking during refresh prevents late responses from repopulating personal data',async()=>{
  const c=await client();try{
    c.db.set('chat',chat());await c.connect();let release;
    c.intercept(async url=>{if(url==='/workspace/items')await new Promise(resolve=>{release=resolve;});});
    const pending=c.click('refresh');await flush();await c.click('lock');release();await pending;
    assert.equal(c.w.document.getElementById('chats').textContent,'');
    assert.equal(c.w.document.getElementById('memories').textContent,'');
    assert.equal(c.w.document.getElementById('status').textContent,'Locked');
    assert.equal(c.w.document.getElementById('workspace').hidden,true);
    assert.equal(c.w.document.getElementById('title').textContent,'New chat');
    assert.equal(c.w.document.getElementById('pref-display_name').value,'');
  }finally{c.close();}
});

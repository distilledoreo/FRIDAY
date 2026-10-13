'use strict';
let token='', items=[], current=null, knowledge=null, pendingFiles=[], busy=false;
let session=0, timer=null, activeJob=null, refreshing=false, memoryAvailable=false;
let preferences={};
const defaults={theme:'DARK',accent:'E89980',proactivity:'THOUGHTFUL',reduced_motion:false,display_name:'',interactive_answers:true};
const el=id=>document.getElementById(id);
const status=message=>{el('status').textContent=message;};
function updateSend(){el('send').disabled=busy||!!activeJob;}
async function api(path,method='GET',body=null){
  const identity=session;
  const headers={Authorization:'Bearer '+token};
  if(body)headers['Content-Type']='application/json';
  const r=await fetch(path,{method,headers,body:body?JSON.stringify(body):null,signal:AbortSignal.timeout(10000)});
  if(identity!==session)throw new Error('Session changed');
  if(!r.ok)throw new Error(r.status===409?'Changed on another client. Refresh and review before trying again; your draft is retained.':'Request failed ('+r.status+'): '+(await r.text()).slice(0,300));
  const result=await r.json();
  if(identity!==session)throw new Error('Session changed');
  return result;
}
function safe(action){return async()=>{
  if(busy)return;
  const identity=session;
  busy=true;updateSend();
  try{await action();}catch(e){if(identity===session)status(e.message);}
  finally{if(identity===session){busy=false;updateSend();}}
};}
function node(tag,text,parent,cls){const n=document.createElement(tag);n.textContent=text;if(cls)n.className=cls;parent.appendChild(n);return n;}
function renderContent(text,parent){
  const re=/assistant:\/\/artifact\/([a-f0-9]{32})/g;let last=0;
  for(const m of text.matchAll(re)){
    parent.appendChild(document.createTextNode(text.slice(last,m.index)));
    const a=node('a','Download file',parent);a.href='#';
    a.onclick=safe(async()=>{
      const identity=session;
      const r=await fetch('/workspace/files/'+m[1],{headers:{Authorization:'Bearer '+token}});
      if(!r.ok)throw new Error('Download failed');
      const blob=await r.blob();if(identity!==session)return;
      const url=URL.createObjectURL(blob),d=document.createElement('a');
      d.href=url;d.download=(r.headers.get('Content-Disposition')||'').match(/filename="([^"]+)"/)?.[1]||'assistant-file';
      d.click();setTimeout(()=>URL.revokeObjectURL(url),10000);
    });last=m.index+m[0].length;
  }
  parent.appendChild(document.createTextNode(text.slice(last)));
}
function chats(){
  el('chats').replaceChildren();const q=el('search').value.toLowerCase();
  const list=items.filter(i=>!i.deleted&&i.payload.chat).map(i=>{try{return {...i,chat:JSON.parse(i.payload.chat)};}catch{return null;}})
    .filter(Boolean).sort((a,b)=>b.chat.summary.updatedAt-a.chat.summary.updatedAt);
  for(const i of list){
    if(!JSON.stringify(i.chat).toLowerCase().includes(q))continue;
    const b=node('button',i.chat.summary.title,el('chats'),'chat-link');
    b.onclick=()=>{if(busy||activeJob)return;current=i;pendingFiles=[];render(true);};
  }
}
function render(resetProject=false){
  el('messages').replaceChildren();el('title').textContent=current?.chat.summary.title||'New chat';
  el('rename').disabled=el('deleteChat').disabled=!current||!!activeJob;
  const selected=resetProject?current?.chat.summary.projectId||'':el('project').value||'';
  el('project').replaceChildren();let o=node('option','General chat',el('project'));o.value='';
  for(const p of knowledge?.value.projects||[]){o=node('option',p.name,el('project'));o.value=p.id;}
  el('project').value=selected;
  for(const m of current?.chat.messages||[]){
    if(!['user','assistant'].includes(m.role))continue;
    const box=node('div','',el('messages'),'message');
    node('div',m.role==='user'?'You':'FRIDAY',box,'role');renderContent(m.content||'',box);
    for(const a of m.attachments||[])node('div','Attachment: '+a.name,box);
  }
  el('messages').scrollTop=el('messages').scrollHeight;
  el('attachments').textContent=pendingFiles.map(a=>a.name).join(', ');
}
function applyPreferences(){
  const values={...defaults};
  for(const key of Object.keys(defaults)){
    const item=items.find(i=>i.id==='preferences-'+key&&!i.deleted);
    if(item)values[key]=JSON.parse(item.payload.preferences).value;
  }
  preferences=values;
  const light=values.theme==='LIGHT'||(values.theme==='SYSTEM'&&matchMedia('(prefers-color-scheme: light)').matches);
  document.body.dataset.theme=light?'light':'dark';
  document.body.dataset.reducedMotion=String(values.reduced_motion);
  if(/^[0-9A-F]{6}$/.test(values.accent))document.documentElement.style.setProperty('--accent','#'+values.accent);
  for(const [key,value] of Object.entries(values)){
    const input=el('pref-'+key);
    if(document.activeElement===input)continue;
    if(input.type==='checkbox')input.checked=value;
    else input.value=key==='accent'?'#'+value:value;
  }
}
const memoryLabels={use_memories:'Use approved memories',use_history:'Recall past chats',suggest_from_chats:'Suggest memories from chats',archive_chats:'Archive chats for recall',track_situations:'Track recent situations',allow_checkins:'Allow check-ins'};
async function refreshMemory(){
  const identity=session;
  try{
    const [summary,facts]=await Promise.all([api('/workspace/memory'),api('/workspace/memory/memories?limit=200')]);
    memoryAvailable=true;el('memories').replaceChildren();el('memorySettings').replaceChildren();
    if(knowledge?.value.memories?.length){
      node('button','Import older saved memories',el('memories')).onclick=safe(async()=>{
        const next=structuredClone(knowledge.value),revision=knowledge.revision;
        const result=await api('/workspace/memory/legacy','POST',{memories:next.memories});
        if(result.skipped)throw new Error('Some older memories could not be imported; the originals were retained.');
        next.memories=[];
        await api('/workspace/items/knowledge','PUT',{revision,payload:{knowledge:JSON.stringify(next)}});
        await refresh();
      });
    }
    for(const m of facts){
      const box=node('div',m.text,el('memories'),'memory');
      node('button','Forget',box).onclick=safe(async()=>{await api('/workspace/memory/memories/'+m.id,'DELETE');await refreshMemory();});
    }
    if(summary.memories>facts.length)node('p',`Showing ${facts.length} of ${summary.memories} memories. All remain available for recall.`,el('memories'));
    for(const [key,label] of Object.entries(memoryLabels)){
      const row=node('label','',el('memorySettings')),input=node('input','',row);input.type='checkbox';input.checked=summary.settings[key];
      row.appendChild(document.createTextNode(label));
      input.onchange=safe(async()=>{await api('/workspace/memory/settings','PATCH',{[key]:input.checked});await refreshMemory();});
    }
    el('saveMemory').disabled=false;
  }catch(e){if(identity!==session)return;memoryAvailable=false;el('saveMemory').disabled=true;el('memories').replaceChildren();el('memorySettings').replaceChildren();node('p','Shared PC memory unavailable: '+e.message,el('memories'));}
}
async function refresh(){
  if(refreshing)return;
  refreshing=true;const identity=session;
  try{
    const [next,jobs]=await Promise.all([api('/workspace/items'),api('/workspace/jobs')]);
    if(identity!==session)return;
    const changed=JSON.stringify(items)!==JSON.stringify(next);items=next;
    const k=items.find(i=>i.id==='knowledge'&&!i.deleted);
    knowledge=k?{revision:k.revision,value:JSON.parse(k.payload.knowledge)}:null;
    if(current){const latest=items.find(i=>i.id===current.id&&!i.deleted);current=latest?{...latest,chat:JSON.parse(latest.payload.chat)}:null;}
    chats();if(changed)render();applyPreferences();
    el('jobs').replaceChildren();
    for(const j of jobs.slice(0,15)){
      const box=node('div',j.prompt+'\n'+j.status,el('jobs'),'task');
      if(j.result)renderContent('\n'+j.result,box);if(j.error)node('div',j.error,box);
      for(const action of (['scheduled','running'].includes(j.status)?['pause','cancel']:['paused','failed'].includes(j.status)?['resume','cancel','delete']:['delete'])){
        node('button',action,box).onclick=safe(async()=>{await api('/workspace/jobs/'+j.id+(action==='delete'?'':'/'+action),action==='delete'?'DELETE':'POST');await refresh();});
      }
    }
    if(activeJob){const j=jobs.find(x=>x.id===activeJob);if(!j||!['running','scheduled'].includes(j.status)){activeJob=null;status(j?.error||'Reply ready');}else status('Working on the computer…');}
    else status('Connected · Changes sync automatically while clients are open.');
    updateSend();await refreshMemory();
  }finally{if(identity===session)refreshing=false;}
}
el('connect').onclick=safe(async()=>{
  token=el('token').value.trim();await refresh();el('token').value='';el('login').hidden=true;el('workspace').hidden=false;render();
  clearInterval(timer);timer=setInterval(()=>{if(token&&!busy&&!refreshing&&!document.hidden)safe(refresh)();},5000);
});
el('refresh').onclick=safe(refresh);el('search').oninput=chats;
el('new').onclick=()=>{if(busy||activeJob)return;current=null;pendingFiles=[];el('draft').value='';el('project').value='';render();};
el('lock').onclick=()=>{
  session++;token='';items=[];current=null;knowledge=null;pendingFiles=[];activeJob=null;busy=false;refreshing=false;memoryAvailable=false;
  clearInterval(timer);timer=null;el('draft').value='';el('file').value='';el('memory').value='';el('search').value='';
  for(const id of ['messages','jobs','memories','memorySettings','chats','attachments'])el(id).replaceChildren();
  render(true);applyPreferences();el('workspace').hidden=true;el('login').hidden=false;status('Locked');
};
el('send').onclick=safe(async()=>{
  const draft=el('draft').value.trim();if(activeJob||(!draft&&!pendingFiles.length))return;
  const now=Date.now(),id=current?.id||crypto.randomUUID();
  const c=structuredClone(current?.chat||{version:1,summary:{id,title:draft.slice(0,70)||'Files',createdAt:now,updatedAt:now},messages:[]});
  c.summary.updatedAt=now;c.summary.projectId=el('project').value||null;
  c.messages.push({role:'user',content:draft,attachments:pendingFiles});
  const job=await api('/workspace/items/'+id+'/continue','POST',{revision:current?.revision||0,payload:{chat:JSON.stringify(c)}});
  current={id,revision:job.revision,chat:c};activeJob=job.id;pendingFiles=[];el('draft').value='';render();status('Working on the computer…');
});
el('rename').onclick=safe(async()=>{
  if(!current||activeJob)return;const title=prompt('Chat title',current.chat.summary.title)?.trim();if(!title)return;
  const c=structuredClone(current.chat);c.summary.title=title.slice(0,200);c.summary.updatedAt=Date.now();
  await api('/workspace/items/'+current.id,'PUT',{revision:current.revision,payload:{chat:JSON.stringify(c)}});await refresh();
});
el('deleteChat').onclick=safe(async()=>{
  if(!current||activeJob||!confirm('Delete this chat from synced history on all clients? Its recall archive is managed separately in Memory.'))return;
  await api('/workspace/items/'+current.id,'PUT',{revision:current.revision,deleted:true,payload:{}});current=null;await refresh();render();
});
el('schedule').onclick=safe(async()=>{const prompt=el('draft').value.trim();if(!prompt)return;await api('/workspace/jobs','POST',{prompt});el('draft').value='';await refresh();});
el('saveMemory').onclick=safe(async()=>{
  const text=el('memory').value.trim();if(!text||!memoryAvailable)return;
  await api('/workspace/memory/memories','POST',{text,category:/prefer/i.test(text)?'preference':'other',pinned:true,scope:current?.chat.summary.projectId||''});
  el('memory').value='';await refreshMemory();
});
for(const key of Object.keys(defaults))el('pref-'+key).onchange=safe(async()=>{
  const input=el('pref-'+key),value=input.type==='checkbox'?input.checked:key==='accent'?input.value.slice(1).toUpperCase():input.value;
  const item=items.find(i=>i.id==='preferences-'+key);
  await api('/workspace/items/preferences-'+key,'PUT',{revision:item?.revision||0,payload:{preferences:JSON.stringify({value})}});await refresh();
});
matchMedia('(prefers-color-scheme: light)').addEventListener('change',applyPreferences);
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&token&&!busy&&!refreshing)safe(refresh)();});
el('file').onchange=safe(async()=>{
  const f=el('file').files[0];if(!f)return;if(f.size>25*1024*1024)throw new Error('File exceeds 25 MB');
  const identity=session;
  const r=await fetch('/workspace/files',{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':f.type||'application/octet-stream','X-File-Name':encodeURIComponent(f.name)},body:f});
  if(!r.ok)throw new Error('Upload failed ('+r.status+')');const info=await r.json();if(identity!==session)return;
  const a={id:crypto.randomUUID(),name:f.name,mimeType:f.type||'application/octet-stream',remoteFileId:info.id,kind:f.type.startsWith('image/')?'image':'document',path:'remote:'+info.id};
  if(a.kind==='document'){
    const e=await fetch('/extract',{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':a.mimeType,'X-Filename':encodeURIComponent(f.name)},body:f});
    if(e.ok){const extracted=await e.json();a.text=extracted.text||'';a.note=extracted.note||'';}
  }
  if(identity!==session)return;pendingFiles.push(a);render();el('file').value='';
});

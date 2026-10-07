import asyncio
import contextlib
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import uuid
import zipfile

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

from .fact_prompt import INSTRUCTION as FACT_INSTRUCTION, standalone as fact_standalone, source_text as fact_source_text, parse_reply as fact_parse_reply
from .importer import read_export
from .store import MemoryStore

class Fact(BaseModel):
    text: str = Field(min_length=1,max_length=2000)
    category: str = 'other'
    scope: str = Field(default='',max_length=100)
    pinned: bool = True
class Recall(BaseModel):
    query: str = Field(default='',max_length=4000)
    scope: str = Field(default='',max_length=100)
    current_id: str = Field(default='',max_length=100)
class Commit(BaseModel):
    extract: bool = True
class Review(BaseModel):
    accept: bool
    text: str | None = Field(default=None,max_length=2000)
class Exclude(BaseModel):
    excluded: bool
class Chat(BaseModel):
    id: str = Field(max_length=100)
    title: str = Field(max_length=200)
    scope: str = Field(default='',max_length=100)
    messages: list[dict] = Field(max_length=10000)
    created: float = 0
    updated: float = 0

def data(body):return body.model_dump() if hasattr(body,'model_dump') else body.dict()
def checked(action):
    try:return action()
    except (ValueError,KeyError) as error:raise HTTPException(400,str(error)[:300])

def enable(app,auth,root,llm,gate):
    store=MemoryStore(root);index_worker=None;router=APIRouter(prefix='/workspace/memory',dependencies=auth);worker=None
    @router.get('')
    def summary():return store.summary()
    @router.patch('/settings')
    def settings(body:dict):return checked(lambda:store.set_settings(body))
    @router.get('/situations')
    def situations():
        from .continuity import list_situations
        return list_situations(store)
    @router.delete('/situations/{sid}')
    def forget_situation(sid:str):
        from .continuity import remove
        remove(store,sid);return {'forgotten':True}
    @router.patch('/situations/{sid}')
    def resolve_situation(sid:str,body:dict):
        from .continuity import resolve
        return checked(lambda:resolve(store,sid,body.get('status')))
    @router.get('/memories')
    def memories(q:str='',offset:int=0,limit:int=100):return store.memories(q[:2000],max(0,min(offset,100000)),max(1,min(limit,200)))
    @router.post('/memories')
    def remember(body:Fact):return checked(lambda:store.remember(**data(body)))
    @router.put('/memories/{mid}')
    def edit(mid:str,body:Fact):
        # Preserve provenance when correcting an approved fact.
        with store.db() as db:old=db.execute('SELECT source_id,quote FROM memories WHERE id=?',(mid,)).fetchone()
        if not old:raise HTTPException(404,'Memory not found')
        return checked(lambda:store.remember(**data(body),source_id=old['source_id'],quote=old['quote'],memory_id=mid))
    @router.delete('/memories/{mid}')
    def forget(mid:str):store.forget(mid);return {'forgotten':True}
    @router.post('/legacy')
    def legacy(body:dict):
        items=body.get('memories',[])
        if not isinstance(items,list) or len(items)>200:raise HTTPException(400,'At most 200 legacy facts')
        copied=skipped=0
        for item in items:
            try:
                text=item['text']
                store.remember(text,'preference' if 'prefer' in text.lower() else 'other');copied+=1
            except (ValueError,KeyError,TypeError):skipped+=1
        return {'copied':copied,'skipped':skipped}
    @router.post('/context')
    def context(body:Recall):return store.context(body.query,body.scope,body.current_id)
    @router.get('/tool-search')
    def tool_search(q:str='',kind:str='memory',scope:str=''):
        if kind not in ('memory','history'):raise HTTPException(400,'Unknown search kind')
        enabled=store.settings()['use_memories' if kind=='memory' else 'use_history']
        sources=store.context(q[:4000],scope[:100],include_situations=False)['sources'] if enabled else []
        return {'enabled':enabled,'results':[item for item in sources if item['kind']==kind][:20]}
    @router.get('/suggestions')
    def suggestions():return store.suggestions()
    @router.post('/suggestions/{sid}')
    def review(sid:str,body:Review):checked(lambda:store.review(sid,body.accept,body.text));return {'reviewed':True}
    @router.get('/sources')
    def sources(q:str='',offset:int=0):return store.sources(q[:2000],max(0,min(offset,20000)))
    @router.get('/sources/{sid}')
    def source(sid:str):
        value=checked(lambda:store.source(sid))
        messages=value['messages'];recent=messages[-20:]
        value['truncated']=len(messages)>20 or any(len(m['content'])>2000 for m in recent)
        value['message_count']=len(messages)
        value['messages']=[{'role':m['role'],'content':m['content'][:2000]} for m in recent]
        return value
    @router.patch('/sources/{sid}')
    def exclude(sid:str,body:Exclude):store.exclude(sid,body.excluded);return {'excluded':body.excluded}
    @router.delete('/sources/{sid}')
    def delete_source(sid:str):store.delete_source(sid);return {'deleted':True}
    @router.post('/sources/{sid}/extract')
    def extract(sid:str):checked(lambda:store.queue_extraction(sid));return {'queued':True}
    @router.post('/chats')
    def chat(body:Chat):
        try:cid='native-'+uuid.UUID(body.id.removeprefix('native-')).hex
        except ValueError:raise HTTPException(400,'Invalid conversation id')
        messages=[]
        for m in body.messages:
            if m.get('role') not in ('user','assistant') or not isinstance(m.get('content'),str):raise HTTPException(400,'Only inert user and assistant text is accepted')
            messages.append({'role':m['role'],'content':m['content']})
        encoded=json.dumps(messages,ensure_ascii=False)
        if len(encoded.encode())>8*1024*1024:raise HTTPException(413,'Conversation exceeds 8 MB')
        archived=store.native({'id':cid,'title':body.title,'origin':'Local assistant','scope':body.scope,'messages':messages,'created':body.created,'updated':body.updated,'digest':hashlib.sha256(encoded.encode()).hexdigest()})
        return {'archived':archived,'id':cid}
    @router.post('/imports')
    async def stage(request:Request):
        path=None
        try:
            with tempfile.NamedTemporaryFile(dir=store.root,prefix='upload-',delete=False) as f:
                path=Path(f.name);size=0
                async for chunk in request.stream():
                    size+=len(chunk)
                    if size>256*1024*1024:raise HTTPException(413,'Export upload exceeds 256 MB')
                    await asyncio.to_thread(f.write,chunk)
            sources,skipped,warnings=await asyncio.to_thread(read_export,path)
            return await asyncio.to_thread(store.stage,sources,skipped,warnings)
        except (ValueError,KeyError,zipfile.BadZipFile,RuntimeError) as error:raise HTTPException(400,str(error)[:300])
        finally:
            if path:path.unlink(missing_ok=True)
    @router.post('/imports/{sid}')
    def commit(sid:str,body:Commit):return checked(lambda:store.commit(sid,body.extract))
    @router.delete('/imports/{sid}')
    def discard(sid:str):store.discard(sid);return {'discarded':True}

    async def extract_loop():
        while True:
            if gate.readers or gate.exclusive:
                await asyncio.sleep(3);continue
            try:
                sid=await asyncio.to_thread(store.next_extraction)
            except sqlite3.OperationalError:
                # The index worker or an import holds the database briefly; a lock must not end this loop.
                await asyncio.sleep(3);continue
            if not sid:
                await asyncio.sleep(3);continue
            try:
                s=await asyncio.to_thread(store.source,sid)
                text='\n'.join(m['content'] for m in s['messages'] if m['role']=='user')
                if len(text)>12000:text=text[:4000]+'\n[Middle omitted]\n'+text[-8000:]
                if not text.strip():store.finish_extraction(sid);continue
                instruction=FACT_INSTRUCTION
                from .continuity import extraction_prompt, apply_updates
                live=sid.startswith('native-') and s['updated'] > __import__('time').time()-86400 and store.settings()['track_situations']
                if live:instruction += extraction_prompt(store,s['scope'])
                response=await llm.post('/v1/chat/completions',json={'model':'qwen3.8-27b','messages':[{'role':'system','content':instruction},{'role':'user','content':fact_source_text(s['title'],text)}], 'stream':False,'temperature':0.1,'max_tokens':1200,'chat_template_kwargs':{'enable_thinking':False}})
                response.raise_for_status();raw=response.json()['choices'][0]['message']['content']
                # Parse one JSON object; no evaluation or execution of source/model output.
                obj=fact_parse_reply(raw)
                values=obj.get('memories',[])
                if not isinstance(values,list):raise ValueError('Invalid memory suggestions response')
                # Keep only facts that make sense without the chat, at most three.
                values=[v for v in values if isinstance(v,dict) and fact_standalone(str(v.get('text','')))][:3]
                if not sid.startswith('native-') or store.settings()['suggest_from_chats']:
                    await asyncio.to_thread(store.suggest,sid,values)
                if live:await asyncio.to_thread(apply_updates,store,sid,obj.get('situations',[]))
                await asyncio.to_thread(store.finish_extraction,sid)
            except asyncio.CancelledError:
                store.queue_extraction(sid);raise
            except Exception as error:await asyncio.to_thread(store.finish_extraction,sid,str(error)[:400])
            await asyncio.sleep(2)
    async def index_loop():
        while True:
            try:
                worked=await asyncio.to_thread(store.index_batch)
                await asyncio.sleep(.1 if worked else 3)
            except asyncio.CancelledError:raise
            except Exception as error:
                store.semantic_error=str(error)[:300];await asyncio.sleep(10)
    @app.on_event('startup')
    async def start():
        nonlocal worker,index_worker
        worker=asyncio.create_task(extract_loop())
        index_worker=asyncio.create_task(index_loop())
    @app.on_event('shutdown')
    async def stop():
        for task in [worker,index_worker]:
            if task:
                task.cancel()
                with contextlib.suppress(asyncio.CancelledError):await task
    app.include_router(router)
    return store

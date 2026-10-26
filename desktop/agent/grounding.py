"""Bounded, stateless public evidence reads. Retrieval is not truth verification."""
import asyncio
from datetime import datetime, timezone
import hashlib
from html.parser import HTMLParser
import json
import re
from urllib.parse import urljoin, urlsplit

from fastapi import APIRouter, HTTPException
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from pydantic import BaseModel, ConfigDict, Field

from .public_web import read, MAX_BODY, READ_POOL, PageHTTPError


def page_http_detail(error):
    missing = error.status in (404, 410)
    return {'reason': 'page_not_found' if missing else 'publisher_http_error',
            'upstream_status': error.status, 'requested_url': error.url,
            'retryable': error.status == 429 or error.status >= 500,
            'message': (f'The requested page returned HTTP {error.status}. Use an exact link from '
                        'search results or the user; do not invent an article address or conclude '
                        'the whole website is unavailable.' if missing else
                        f'The publisher returned HTTP {error.status} for this page.')}


class Request(BaseModel):
    model_config=ConfigDict(extra='forbid',strict=True)
    query: str = Field(min_length=1,max_length=500)
    limit: int = Field(default=5,ge=1,le=10)
    fetch_pages: bool = False


class PageRequest(BaseModel):
    model_config=ConfigDict(extra='forbid',strict=True)
    url: str = Field(min_length=1,max_length=4096)
    query: str = Field(default='',max_length=500)


def public_shape(url):
    try:
        parsed=urlsplit(url)
        return isinstance(url,str) and len(url)<=4096 and not any(ord(c)<33 for c in url) and '\\' not in url and parsed.scheme=='https' and parsed.hostname and not parsed.username and not parsed.password and parsed.port in (None,443)
    except (ValueError,TypeError,AttributeError):return False


def utc():return datetime.now(timezone.utc).isoformat()


def references(query):
    values=[]
    for match in re.finditer(r'https://',query):
        end=match.start();depth=0
        while end<len(query):
            char=query[end]
            if char.isspace() or char in '<>"`]' or (char==')' and depth==0):break
            if char=='(':depth+=1
            if char==')':depth-=1
            end+=1
        value=query[match.start():end].rstrip('.,;!')
        if value not in values:values.append(value)
    return values[:3]



class Text(HTMLParser):
    hidden={'script','style','nav','header','footer','aside','form','noscript','template','svg','iframe','object'}
    blocks={'p','div','article','section','li','h1','h2','h3','h4','h5','h6','br','tr','blockquote','pre'}
    def __init__(self):
        super().__init__(convert_charrefs=True);self.parts=[];self.depth=0;self.in_title=False;self.title=[];self.dates={};self.length=0;self.truncated=False
        self.links=[];self.anchor=None;self.article_depth=0;self.hidden_marks={}
    def handle_starttag(self,tag,attrs):
        if tag in ('article','main'):self.article_depth+=1
        if tag in self.hidden:
            # A publisher's article directory is content too (e.g. WOL meeting cards).
            # Only outer navigation is page chrome. Track which opens actually hid text.
            hidden=not(tag=='nav' and self.article_depth and not self.depth)
            self.hidden_marks.setdefault(tag,[]).append(hidden)
            if hidden:self.depth+=1
        if self.depth:return
        if tag=='title':self.in_title=True
        if tag in self.blocks:self.append('\n')
        values=dict(attrs)
        if tag=='a' and values.get('href') and len(self.links)<64:
            self.anchor={'href':str(values['href'])[:4096],'parts':[],'length':0,
                         'label':str(values.get('aria-label') or values.get('title') or '')[:160]}
        if tag=='meta':
            key=values.get('property') or values.get('name')
            if key in ('article:published_time','article:modified_time','datePublished','dateModified'):
                self.dates[key]=str(values.get('content',''))[:100]
    def handle_endtag(self,tag):
        if tag in ('article','main'):self.article_depth=max(0,self.article_depth-1)
        if tag in self.hidden:
            marks=self.hidden_marks.get(tag,[])
            if marks and marks.pop():self.depth=max(0,self.depth-1)
            return
        if self.depth:return
        if tag=='a' and self.anchor is not None:
            self.links.append({'href':self.anchor['href'],'text':
                              ' '.join(''.join(self.anchor['parts']).split())[:160] or self.anchor['label']})
            self.anchor=None
        if tag=='title':self.in_title=False
        if tag in self.blocks:self.append('\n')
    def handle_data(self,text):
        if self.depth:return
        if self.in_title:self.title.append(text[:300]);return
        if self.anchor is not None and self.anchor['length']<160:
            self.anchor['parts'].append(text[:160-self.anchor['length']]);self.anchor['length']+=min(len(text),160-self.anchor['length'])
        self.append(text)
    def append(self,text):
        remaining=100000-self.length
        if len(text)>remaining:self.truncated=True
        if remaining>0:self.parts.append(text[:remaining]);self.length+=min(len(text),remaining)
    def result(self):
        paragraphs=[' '.join(line.split()) for line in ''.join(self.parts).splitlines()]
        return '\n\n'.join(line for line in paragraphs if line), ' '.join(self.title)[:300]


STOP=set('read summarize summary source sources check review tell me give explain latest current article page website paper document the a an of to in for on with is are was were and or this that what who how when why please about from'.split())

def passages(text,query,count=3):
    tokens=set(re.findall(r'[^\W_]{3,}',query.lower()))-STOP
    values=[];position=0
    # Inspect every paragraph in the bounded document, including text past the opening.
    for paragraph in text.split('\n\n'):
        start=text.find(paragraph,position);position=start+len(paragraph)
        if not paragraph.strip():continue
        for offset in range(0,len(paragraph),1000):
            value=paragraph[offset:offset+1400]
            terms=re.findall(r'[^\W_]{3,}',value.lower())
            score=sum(min(terms.count(token),3) for token in tokens)
            values.append((score,start+offset,value))
    chosen=sorted((item for item in values if not tokens or item[0]>0),key=lambda item:(-item[0],item[1]))[:count]
    return [{'id':f'P{index+1}','start':start,'end':start+len(value),'text':value,'matched_terms':score} for index,(score,start,value) in enumerate(sorted(chosen,key=lambda item:item[1]))]


def page_evidence(document,url,query=''):
    if not isinstance(document,dict) or not isinstance(document.get('content'),str) or document.get('mime') not in ('text/html','text/plain','application/xhtml+xml'):raise ValueError('Reader returned invalid static text')
    if not public_shape(document.get('url')):raise ValueError('Reader returned an unsupported final URL')
    raw=document['content']
    if len(raw.encode())>MAX_BODY:raise ValueError('Page exceeds its wire limit')
    links=[];links_truncated=False
    if document.get('mime')=='text/plain':text=raw[:100000];title='';metadata={};truncated=len(raw)>100000
    else:
        parser=Text();parser.feed(raw);text,title=parser.result();metadata=parser.dates;truncated=parser.truncated
        seen=set();link_chars=0
        for link in parser.links:
            if link['href'].startswith('#'):continue
            target=urljoin(document['url'],link['href'])
            if not public_shape(target) or target in seen:continue
            if len(links)>=32 or link_chars+len(target)+len(link['text'])>6000:
                links_truncated=True;continue
            seen.add(target);links.append({'url':target,'text':link['text']});link_chars+=len(target)+len(link['text'])
        links_truncated=links_truncated or len(parser.links)>=64
    if not text.strip():raise ValueError('Page has no readable static text')
    selected=passages(text,query)
    return {'url':document['url'],'requested_url':url,'title':title,'status':'retrieved','checked_at':utc(),
            'document_sha256':hashlib.sha256(text.encode()).hexdigest(),'document_chars':len(text),'document_truncated':truncated,
            'publisher_date_metadata':metadata,'passages':selected,'untrusted':True,
            'links':links,'links_truncated':links_truncated,
            'truth_verified':False,'detail':'Selected exact passages from bounded static text. Dates are publisher metadata, not independently verified. Retrieval does not prove a claim.'}

class Grounding:
    def __init__(self,search=None,reader=read,timeout=18,empty_retry_delay=2.5):
        self.search=search;self.reader=reader;self.timeout=timeout;self.jobs=READ_POOL.pending;self.searches=0
        self.empty_retry_delay=empty_retry_delay
    @property
    def active(self):return len(self.jobs)+self.searches
    async def page(self,url,query=''):
        if not public_shape(url):raise ValueError('Only public HTTPS pages are supported')
        # The shared pool retains canceled/timed-out threads until completion.
        document=await READ_POOL.fetch(self.reader,url,self.timeout)
        return page_evidence(document,url,query)
    async def research(self,request):
        query=request.query.strip()
        if not query:raise ValueError('Query is blank')
        urls=references(query)
        results=[];search_status='not_needed' if urls else 'unavailable'
        if urls:
            results=[{'url':url,'title':'Explicit source','snippet':''} for url in urls]
        elif self.search and self.searches<3:
            self.searches+=1
            try:
                response=await asyncio.wait_for(self.search(query),12)
                candidates=response.get('results',[])
                # The engines behind search throttle bursts (CAPTCHAs, "too many requests") and then answer nothing;
                # the same query found five results minutes later in Codex's assessment, so an empty answer is retried once.
                if not candidates:
                    await asyncio.sleep(self.empty_retry_delay)
                    response=await asyncio.wait_for(self.search(query),12)
                    candidates=response.get('results',[])
                for hit in candidates[:request.limit]:
                    if not isinstance(hit,dict) or not public_shape(hit.get('url')):continue
                    if any(item['url']==hit['url'] for item in results):continue
                    results.append({'url':hit['url'],'title':str(hit.get('title') or '')[:300],'snippet':str(hit.get('snippet') or hit.get('content') or '')[:700]})
                search_status='available' if results else 'empty'
            except asyncio.CancelledError:raise
            except Exception:search_status='unavailable'
            finally:self.searches-=1
        fetch=request.fetch_pages or bool(urls)
        query_terms=re.sub(r'https://\S+','',query).strip()
        async def fetch_one(item,index):
            if not fetch or index>=3:return {**item,'id':f'S{index+1}','status':'snippet_only','untrusted':True,'detail':'Search snippet only; original page was not read.'}
            try:
                page=await self.page(item['url'],query_terms)
                return {**item,**page,'title':page['title'] or item['title'],'id':f'S{index+1}'}
            except asyncio.CancelledError:raise
            except PageHTTPError as error:
                return {**item,'id':f'S{index+1}','status':'not_found' if error.status in (404,410) else 'unavailable',
                        'upstream_status':error.status,'untrusted':True,'detail':page_http_detail(error)}
            except Exception:return {**item,'id':f'S{index+1}','status':'unavailable','untrusted':True,'detail':'Static page could not be retrieved safely. Do not treat its snippet as verified evidence.'}
        evidence=await asyncio.gather(*(fetch_one(item,index) for index,item in enumerate(results)))
        # At most three documents are read; the complete wire/model context is capped.
        shortened=False
        while len(json.dumps(evidence,ensure_ascii=False).encode())>24000:
            shortened=True
            linked=[item for item in evidence if item.get('links')]
            if linked:
                item=max(linked,key=lambda value:len(json.dumps(value['links'],ensure_ascii=False).encode()));item['links'].pop();item['links_truncated']=True
                continue
            values=[item for item in evidence if item.get('passages')]
            if not values:
                candidates=[item for item in evidence if len(item.get('snippet',''))>150 or len(item.get('title',''))>100]
                if candidates:
                    item=max(candidates,key=lambda value:len(json.dumps(value,ensure_ascii=False).encode()));item['snippet']=item.get('snippet','')[:150];item['title']=item.get('title','')[:100];item['snippet_shortened']=True
                elif evidence:evidence.pop()
                else:break
                continue
            largest=max(values,key=lambda item:len(json.dumps(item,ensure_ascii=False).encode()));largest['passages'].pop();largest['passages_shortened']=True
        usable=sum(bool(item.get('passages')) for item in evidence)
        empty={'search_detail':'The search engines returned nothing for this query, probably because they are throttling '
               'requests. That does not mean no source exists: try different words, or fetch a known official page directly.'} if search_status=='empty' else {}
        return {**empty,'query':query,'checked_at':utc(),'search_status':search_status,'evidence':evidence,'usable_pages':usable,'page_limit':3,'evidence_shortened':shortened,
                'untrusted':True,'truth_verified':False,'detail':'Cite only passages that support each material claim, using the source URL. Check source dates, primary authority and conflicts. State missing/uncertain evidence; do not infer truth from rankings or snippets. Larger research must be proposed to Activity and explicitly approved, never automatically started.'}
    async def close(self):
        # Shutdown is normally idle-checked. These are reads only; no mutation can replay.
        for future in self.jobs:future.cancel()
        if self.jobs:await asyncio.gather(*tuple(self.jobs),return_exceptions=True)


class PrivateErrors(APIRoute):
    def get_route_handler(self):
        handler=super().get_route_handler()
        async def call(request):
            try:return await handler(request)
            except RequestValidationError:return JSONResponse(status_code=422,content={'detail':'Invalid public research request; check fields and limits'})
        return call


def install(app,auth,search=None,reader=read):
    grounding=Grounding(search,reader)
    router=APIRouter(prefix='/grounding',dependencies=auth,route_class=PrivateErrors)
    @router.post('')
    async def research(body:Request):
        try:return await grounding.research(body)
        except ValueError:raise HTTPException(409,'Public research request is unavailable or invalid') from None
    @router.post('/page')
    async def page(body:PageRequest):
        try:return await grounding.page(body.url,body.query)
        except PageHTTPError as error:
            raise HTTPException(error.status if error.status in (404,410) else 502,page_http_detail(error)) from None
        except (ValueError,TimeoutError,OSError):raise HTTPException(502,'Public static page could not be retrieved safely') from None
    app.router.add_event_handler('shutdown',grounding.close)
    app.include_router(router)
    return grounding

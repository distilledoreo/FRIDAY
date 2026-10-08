"""Deterministic private daily brief and proposal-only, opt-in notices.

No inference, outgoing action, background account read, or persisted snapshot.
"""
import asyncio
import hashlib
import json
import math
from datetime import datetime, time as day_time, timedelta, timezone
from pathlib import Path
import re
import sqlite3
import time
import uuid
from zoneinfo import ZoneInfo

import httpx
from fastapi import HTTPException
from pydantic import BaseModel, ConfigDict, Field


class Strict(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)


class WeatherLocation(Strict):
    label: str = Field(min_length=1,max_length=200)
    latitude: float = Field(ge=-90,le=90,allow_inf_nan=False)
    longitude: float = Field(ge=-180,le=180,allow_inf_nan=False)


class Preferences(Strict):
    timezone: str = Field(default='UTC',max_length=100)
    calendar_account_id: str | None = Field(default=None,pattern=r'^[a-f0-9]{32}$')
    weather: WeatherLocation | None = None
    temperature_unit: str = 'celsius'
    include_situations: bool = False
    include_followups: bool = True
    morning_enabled: bool = False
    morning_time: str = Field(default='08:00',pattern=r'^[0-2][0-9]:[0-5][0-9]$',max_length=5)
    proactive_enabled: bool = False
    cadence_hours: int = Field(default=72,ge=72,le=720)
    quiet_start: str = Field(default='21:00',pattern=r'^[0-2][0-9]:[0-5][0-9]$',max_length=5)
    quiet_end: str = Field(default='08:00',pattern=r'^[0-2][0-9]:[0-5][0-9]$',max_length=5)


class Scope(Strict):
    scope: str = Field(default='',max_length=100)


class LocationQuery(Strict):
    query: str = Field(min_length=2,max_length=100)


class Followup(Strict):
    title: str = Field(min_length=1,max_length=800)
    scope: str = Field(default='',max_length=100)
    due: str | None = Field(default=None,max_length=100)


class FollowupState(Strict):
    status: str
    due: str | None = Field(default=None,max_length=100)


def digest(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,ensure_ascii=False,allow_nan=False).encode()).hexdigest()


def clock(value):
    if not re.fullmatch(r'[0-2][0-9]:[0-5][0-9]',value): raise ValueError('Use HH:MM clock times')
    try: return day_time.fromisoformat(value)
    except ValueError: raise ValueError('Use valid 24-hour clock times') from None


def quiet(preferences, now):
    local=datetime.fromtimestamp(now,ZoneInfo(preferences['timezone'])).time().replace(tzinfo=None)
    start,end=clock(preferences['quiet_start']),clock(preferences['quiet_end'])
    if start==end: return True # Equal times deliberately mean quiet all day.
    return start<=local<end if start<end else local>=start or local<end


def memory_policy(path):
    defaults={'use_history':False,'track_situations':False,'allow_checkins':False}
    if not path.is_file(): return defaults
    try:
        with sqlite3.connect(f'file:{path}?mode=ro',uri=True,timeout=2) as db:
            for key,value in db.execute("SELECT key,value FROM settings WHERE key IN ('use_history','track_situations','allow_checkins')"):
                defaults[key]=json.loads(value) is True
            # Match MemoryStore defaults, but only if the database/schema exists.
            present={key for key, in db.execute('SELECT key FROM settings')}
            for key in defaults:
                if key not in present: defaults[key]=True
    except (sqlite3.Error,ValueError,TypeError): return {key:False for key in defaults}
    return defaults


def situations(path,scope,now):
    policy=memory_policy(path)
    if not policy['use_history'] or not policy['track_situations']: return []
    try:
        with sqlite3.connect(f'file:{path}?mode=ro',uri=True,timeout=2) as db:
            db.row_factory=sqlite3.Row
            rows=db.execute("SELECT a.id,a.topic,a.summary,a.quote,a.source_id,a.updated,a.last_offered,s.title source_title FROM situations a JOIN sources s ON s.id=a.source_id WHERE s.excluded=0 AND a.status='open' AND a.expires>? AND (a.scope='' OR a.scope=?) ORDER BY a.updated DESC LIMIT 10",(now,scope))
            return [{**dict(row),'tentative':True} for row in rows]
    except sqlite3.Error: return []


class Weather:
    def __init__(self,client=None):
        self.client=client or httpx.AsyncClient(timeout=httpx.Timeout(10),follow_redirects=False,trust_env=False,limits=httpx.Limits(max_connections=2))
    async def close(self): await self.client.aclose()
    async def get(self,url,params):
        # Callers choose fixed HTTPS endpoints; there is no arbitrary URL field.
        async with asyncio.timeout(15):
            async with self.client.stream('GET',url,params=params,headers={'Accept':'application/json'}) as response:
                if response.status_code!=200: raise ValueError('Weather source unavailable; retry later')
                body=bytearray()
                async for chunk in response.aiter_bytes():
                    body.extend(chunk)
                    if len(body)>262144: raise ValueError('Weather source response exceeded its limit')
        try: value=json.loads(body)
        except (ValueError,UnicodeError): raise ValueError('Weather source returned invalid data') from None
        if not isinstance(value,dict) or value.get('error'): raise ValueError('Weather source returned invalid data')
        return value
    async def locations(self,query):
        data=await self.get('https://geocoding-api.open-meteo.com/v1/search',{'name':query,'count':5,'format':'json'})
        values=[]
        results=data.get('results',[])
        if not isinstance(results,list):raise ValueError('Location source returned invalid data')
        for item in results[:5]:
            if not isinstance(item,dict):continue
            try:
                label=', '.join(str(item[key])[:80] for key in ('name','admin1','country') if item.get(key))[:200]
                location=WeatherLocation(label=label,latitude=float(item['latitude']),longitude=float(item['longitude']))
                values.append(location.model_dump())
            except (ValueError,KeyError,TypeError): continue
        return {'locations':values,'source':'https://open-meteo.com/en/docs/geocoding-api','attribution':'Open-Meteo / GeoNames','detail':'Select the intended city; no location is chosen automatically.'}
    async def forecast(self,location,zone,unit,date):
        url='https://api.open-meteo.com/v1/forecast'
        params={'latitude':location['latitude'],'longitude':location['longitude'],'timezone':zone,'temperature_unit':unit,
                'start_date':date,'end_date':date,'daily':'weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max'}
        data=await self.get(url,params)
        try:
            daily=data['daily']; units=data['daily_units']
            if daily['time']!=[date]: raise ValueError()
            values={key:daily[key][0] for key in ('weather_code','temperature_2m_max','temperature_2m_min','precipitation_probability_max')}
            if any(type(value) not in (int,float) or not math.isfinite(value) for value in values.values()): raise ValueError()
            if type(values['weather_code']) is not int or not 0<=values['weather_code']<=99:raise ValueError()
            if not 0<=values['precipitation_probability_max']<=100 or not -200<=values['temperature_2m_min']<=values['temperature_2m_max']<=200: raise ValueError()
            if units['temperature_2m_max']!=('°C' if unit=='celsius' else '°F') or units['temperature_2m_min']!=units['temperature_2m_max']: raise ValueError()
            source=str(httpx.URL(url,params=params))
            conditions={0:'Clear sky',1:'Mostly clear',2:'Partly cloudy',3:'Overcast',45:'Fog',48:'Freezing fog',51:'Light drizzle',53:'Drizzle',55:'Heavy drizzle',56:'Freezing drizzle',57:'Freezing drizzle',61:'Light rain',63:'Rain',65:'Heavy rain',66:'Freezing rain',67:'Freezing rain',71:'Light snow',73:'Snow',75:'Heavy snow',77:'Snow grains',80:'Light rain showers',81:'Rain showers',82:'Heavy rain showers',85:'Snow showers',86:'Heavy snow showers',95:'Thunderstorms',96:'Thunderstorms with hail',99:'Thunderstorms with hail'}
            return {**values,'conditions':conditions.get(values['weather_code'],'Conditions unavailable'),'date':date,'location':location['label'],'temperature_unit':units['temperature_2m_max'],'source':source,'attribution':'Weather data by Open-Meteo (CC BY 4.0)','forecast':True}
        except (ValueError,KeyError,IndexError,TypeError): raise ValueError('Weather source returned incomplete or stale data') from None


class Briefing:
    def __init__(self,root,accounts,weather=None,now=time.time):
        self.root=Path(root);self.path=self.root/'briefing.sqlite';self.memory=self.root.parent/'memory/memory.sqlite'
        self.accounts=accounts;self.weather=weather or Weather();self.now=now;self.busy=0
        self.root.mkdir(parents=True,exist_ok=True)
        with self.db() as db:
            db.executescript('''PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS brief_settings(id INTEGER PRIMARY KEY,value TEXT,updated REAL);
                CREATE TABLE IF NOT EXISTS followups(id TEXT PRIMARY KEY,title TEXT,scope TEXT,due REAL,status TEXT,updated REAL);
                CREATE TABLE IF NOT EXISTS checkins(id TEXT PRIMARY KEY,kind TEXT,source_id TEXT,version TEXT,status TEXT,created REAL,expires REAL);
                CREATE TABLE IF NOT EXISTS notice_state(id INTEGER PRIMARY KEY,last_offer REAL);''')
        self.path.chmod(0o600)
    def db(self):
        db=sqlite3.connect(self.path,timeout=3);db.row_factory=sqlite3.Row;return db
    def settings(self):
        with self.db() as db: row=db.execute('SELECT value,updated FROM brief_settings WHERE id=1').fetchone()
        value=json.loads(row['value']) if row else {**Preferences().model_dump(),'calendar_account':None}
        return {**value,'revision':digest(value)}
    def configure(self,body):
        value=body.model_dump();ZoneInfo(value['timezone'])
        for key in ('morning_time','quiet_start','quiet_end'):clock(value[key])
        if value['temperature_unit'] not in ('celsius','fahrenheit'):raise ValueError('Choose Celsius or Fahrenheit')
        account=value['calendar_account_id'];metadata=None
        if account:
            if not self.accounts:raise ValueError('Account vault unavailable')
            metadata=self.accounts.vault.metadata(account)
            if metadata['provider'] not in ('google','microsoft'):raise ValueError('Selected account has no primary calendar')
        value['calendar_account']=metadata
        with self.db() as db:
            db.execute('INSERT OR REPLACE INTO brief_settings VALUES(1,?,?)',(json.dumps(value,allow_nan=False),self.now()))
            if not value['proactive_enabled']:db.execute("UPDATE checkins SET status='cancelled' WHERE kind<>'morning' AND status='pending'")
            if not value['morning_enabled']:db.execute("UPDATE checkins SET status='cancelled' WHERE kind='morning' AND status='pending'")
        return self.settings()
    def followups(self,scope=''):
        with self.db() as db:
            return [dict(row) for row in db.execute("SELECT * FROM followups WHERE status='open' AND (scope='' OR scope=?) ORDER BY due IS NULL,due,updated DESC LIMIT 100",(scope,))]
    def add_followup(self,body):
        title=body.title.strip()
        if not title or any(ord(char)<32 and char not in '\n\t' for char in title):raise ValueError('Use a readable follow-up title')
        if re.search(r'(?i)(password\s*[:=]|api[_ -]?key\s*[:=]|bearer\s+[a-z0-9]|sk-[a-z0-9_-]{15,}|-----BEGIN .*PRIVATE KEY)',title):raise ValueError('Do not save credentials as follow-ups')
        due=self.due(body.due)
        with self.db() as db:
            if db.execute("SELECT count(*) FROM followups WHERE status='open'").fetchone()[0]>=200:raise ValueError('Resolve an existing follow-up first (200 open limit)')
            identifier=uuid.uuid4().hex
            db.execute('INSERT INTO followups VALUES(?,?,?,?,?,?)',(identifier,title,body.scope,due,'open',self.now()))
        return {'id':identifier,'saved':True,'detail':'Follow-up saved only; no task, message or notification was executed.'}
    @staticmethod
    def due(text):
        if not text:return None
        parsed=datetime.fromisoformat(text.replace('Z','+00:00'))
        if parsed.tzinfo is None:raise ValueError('Follow-up due time needs an explicit timezone offset')
        due=parsed.timestamp()
        if not math.isfinite(due):raise ValueError('Invalid follow-up due time')
        return due
    def change_followup(self,identifier,status,due=None):
        if status not in ('done','deleted','open'):raise ValueError('Follow-up state must be open, done or deleted')
        with self.db() as db:
            if status=='open':
                # Rescheduling (snooze): a new due time; a pending check-in for the old time is cancelled below.
                changed=db.execute("UPDATE followups SET due=?,updated=? WHERE id=? AND status='open'",(self.due(due),self.now(),identifier)).rowcount
            elif status=='deleted':changed=db.execute('DELETE FROM followups WHERE id=?',(identifier,)).rowcount
            else:changed=db.execute("UPDATE followups SET status='done',updated=? WHERE id=?",(self.now(),identifier)).rowcount
            db.execute("UPDATE checkins SET status='cancelled' WHERE kind='followup' AND source_id=? AND status='pending'",(identifier,))
        if not changed:raise ValueError('Follow-up not found')
        return {'status':status}
    async def locations(self,query):
        if self.busy:raise ValueError('A brief/location read is already active')
        self.busy+=1
        try:return await self.weather.locations(query.strip())
        finally:self.busy-=1
    async def build(self,scope=''):
        if self.busy:raise ValueError('A brief/location read is already active')
        value=self.settings();now=self.now();zone=ZoneInfo(value['timezone']);local=datetime.fromtimestamp(now,zone)
        first=datetime.combine(local.date(),day_time(),zone);last=datetime.combine(local.date()+timedelta(days=1),day_time(),zone)
        sections=[];self.busy+=1
        try:
            account=value['calendar_account_id']
            if account:
                try:
                    if not self.accounts or self.accounts.vault.metadata(account)!=value['calendar_account']:raise ValueError()
                    result=await self.accounts.read_calendar(account,first.isoformat(),last.isoformat(),30)
                    if self.accounts.vault.metadata(account)!=value['calendar_account']:raise ValueError()
                    events=[]
                    for event in result['events'][:30]:
                        text=lambda key: str(event.get(key,''))[:500]
                        dates=lambda key:{name:str(item)[:100] for name,item in event.get(key,{}).items() if name in ('date','dateTime','timeZone') and isinstance(item,str)} if isinstance(event.get(key),dict) else {}
                        events.append({'id':str(event.get('id',''))[:1024],'title':text('title'),'location':text('location'),'start':dates('start'),'end':dates('end'),'truncated':any(len(str(event.get(key,'')))>500 for key in ('title','location'))})
                    sections.append({'kind':'calendar','status':'available','account':value['calendar_account'],'events':events,'limit':30,'possibly_truncated':len(result['events'])>=30,'untrusted':True,'source':'Selected account primary calendar'})
                except asyncio.CancelledError:raise
                except Exception:sections.append({'kind':'calendar','status':'unavailable','detail':'Primary calendar could not be checked; reconnect/unlock the selected account and verify read permission.'})
            else:sections.append({'kind':'calendar','status':'not_configured','detail':'Select a calendar account in brief settings.'})
            if value['weather']:
                try:sections.append({'kind':'weather','status':'available',**await self.weather.forecast(value['weather'],value['timezone'],value['temperature_unit'],local.date().isoformat())})
                except asyncio.CancelledError:raise
                except Exception:sections.append({'kind':'weather','status':'unavailable','detail':'Current forecast could not be verified; try again later.'})
            else:sections.append({'kind':'weather','status':'not_configured','detail':'Choose a weather city; location is never inferred from your calendar or chats.'})
            policy=memory_policy(self.memory)
            available=value['include_situations'] and policy['track_situations'] and policy['use_history']
            sections.append({'kind':'situations','status':'available' if available else 'disabled','items':situations(self.memory,scope,now) if available else [],'detail':'Tentative machine summaries with user evidence; current status is unconfirmed. Only global and selected-project sources.'})
            followups=self.followups(scope) if value['include_followups'] else []
            sections.append({'kind':'followups','status':'available' if value['include_followups'] else 'disabled','items':followups[:10],'possibly_truncated':len(followups)>10,'detail':'Explicit saved follow-ups; no automatic action.'})
            if self.settings()['revision']!=value['revision']:raise ValueError('Brief settings changed during the read; build again')
            if account and self.accounts.vault.metadata(account)!=value['calendar_account']:raise ValueError('Calendar selection is no longer available')
            snapshot={'date':local.date().isoformat(),'timezone':value['timezone'],'checked_at':now,'scope':scope,'sections':sections,'private':True,'untrusted':True,'detail':'Source snapshot only. No model inference, outgoing action or saved brief. Weather is a forecast; missing sources are shown explicitly.'}
            while len(json.dumps(snapshot,ensure_ascii=False).encode())>98304:
                candidates=[(len(json.dumps(section,ensure_ascii=False).encode()),section,key) for section in sections for key in ('events','items') if section.get(key)]
                if not candidates:raise ValueError('Brief source limit exceeded')
                _,section,key=max(candidates,key=lambda item:item[0]);section[key].pop();section['possibly_truncated']=True
            return snapshot
        finally:self.busy-=1
    def source_candidates(self,scope,now):
        value=self.settings();policy=memory_policy(self.memory);rows=[]
        if not policy['allow_checkins']:return rows
        if value['include_followups']:
            for row in self.followups(scope):
                if row['due'] is not None and row['due']<=now:rows.append(('followup',row['id'],digest(row),row))
        if value['include_situations'] and policy['allow_checkins']:
            for row in situations(self.memory,scope,now):
                if now-row['updated']<=7*86400 and now-row['last_offered']>=value['cadence_hours']*3600:
                    rows.append(('situation',row['id'],digest(row),row))
        return rows
    def candidate(self,kind,identifier,now):
        value=self.settings();policy=memory_policy(self.memory)
        if not value['proactive_enabled'] or not policy['allow_checkins']:return None
        if kind=='followup' and value['include_followups']:
            with self.db() as db:row=db.execute("SELECT * FROM followups WHERE id=? AND status='open' AND due<=?",(identifier,now)).fetchone()
            return dict(row) if row else None
        if kind=='situation' and value['include_situations'] and policy['use_history'] and policy['track_situations']:
            try:
                with sqlite3.connect(f'file:{self.memory}?mode=ro',uri=True,timeout=2) as db:
                    db.row_factory=sqlite3.Row
                    row=db.execute("SELECT a.id,a.topic,a.summary,a.quote,a.source_id,a.updated,a.last_offered,s.title source_title,a.scope FROM situations a JOIN sources s ON s.id=a.source_id WHERE a.id=? AND s.excluded=0 AND a.status='open' AND a.expires>? AND a.updated>=?",(identifier,now,now-7*86400)).fetchone()
                    if row:
                        result=dict(row);scope=result.pop('scope');return {**result,'tentative':True,'scope':scope}
            except sqlite3.Error:pass
        return None
    def proposals(self,scope='',refresh=False):
        now=self.now();value=self.settings();local=datetime.fromtimestamp(now,ZoneInfo(value['timezone']));silent=quiet(value,now)
        candidates=self.source_candidates(scope,now) if value['proactive_enabled'] else []
        eligible={(kind,identifier): (version,row) for kind,identifier,version,row in candidates}
        today=local.date().isoformat();until=datetime.combine(local.date()+timedelta(days=1),day_time(),ZoneInfo(value['timezone'])).timestamp()
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            current=db.execute('SELECT value FROM brief_settings WHERE id=1').fetchone()
            if current and digest(json.loads(current['value']))!=value['revision']:
                return {'items':[],'quiet':True,'notify':False,'timezone':value['timezone'],'detail':'Preferences changed; no proposal was created.'}
            db.execute("UPDATE checkins SET status='expired' WHERE status='pending' AND expires<=?",(now,))
            pending=list(db.execute("SELECT * FROM checkins WHERE status='pending'"))
            for row in pending:
                if row['kind']=='morning':valid=value['morning_enabled'] and row['source_id']==today
                else:
                    current=self.candidate(row['kind'],row['source_id'],now)
                    valid=current is not None and digest({key:item for key,item in current.items() if key!='scope'} if row['kind']=='situation' else current)==row['version']
                if not valid:db.execute("UPDATE checkins SET status='cancelled' WHERE id=?",(row['id'],))
            if refresh and not silent:
                morning=datetime.combine(local.date(),clock(value['morning_time']),ZoneInfo(value['timezone'])).timestamp()
                if value['morning_enabled'] and morning<=now<morning+2*3600 and not db.execute("SELECT 1 FROM checkins WHERE kind='morning' AND source_id=?",(today,)).fetchone():
                    db.execute("INSERT INTO checkins VALUES(?,?,?,?,'pending',?,?)",(uuid.uuid4().hex,'morning',today,value['revision'],now,until))
                state=db.execute('SELECT last_offer FROM notice_state WHERE id=1').fetchone();last_offer=state['last_offer'] if state else 0
                try:
                    with sqlite3.connect(f'file:{self.memory}?mode=ro',uri=True,timeout=2) as memory_db:
                        latest=memory_db.execute('SELECT max(last_offered) FROM situations').fetchone()[0]
                        last_offer=max(last_offer,latest or 0)
                except sqlite3.Error:pass
                if value['proactive_enabled'] and now-last_offer>=value['cadence_hours']*3600 and not db.execute("SELECT 1 FROM checkins WHERE status='pending' AND kind<>'morning'").fetchone():
                    for kind,identifier,version,row in candidates:
                        current=self.candidate(kind,identifier,now)
                        if current is None or digest({key:item for key,item in current.items() if key!='scope'} if kind=='situation' else current)!=version:continue
                        # Dismissal survives source changes and later polling. Explicit new follow-ups have new IDs.
                        if db.execute("SELECT 1 FROM checkins WHERE kind=? AND source_id=? AND status IN ('pending','dismissed')",(kind,identifier)).fetchone():continue
                        db.execute("INSERT INTO checkins VALUES(?,?,?,?,'pending',?,?)",(uuid.uuid4().hex,kind,identifier,version,now,min(until+7*86400,now+3*86400)))
                        db.execute('INSERT OR REPLACE INTO notice_state VALUES(1,?)',(now,));break
            rows=list(db.execute("SELECT * FROM checkins WHERE status='pending' ORDER BY created DESC LIMIT 10"))
        items=[]
        for row in rows:
            if row['kind']=='morning':source={'title':'Open your morning brief','detail':'Assemble selected sources when you open FRIDAY. Nothing has been read in the background.'}
            elif (row['kind'],row['source_id']) in eligible:source=eligible[(row['kind'],row['source_id'])][1]
            else:continue
            items.append({**dict(row),'source':source,'proposal_only':True})
        return {'items':items,'quiet':silent,'notify':not silent,'timezone':value['timezone'],'detail':'Opt-in proposals only; no private account fetch, inference or outgoing action.'}
    def dismiss(self,identifier):
        with self.db() as db:
            changed=db.execute("UPDATE checkins SET status='dismissed' WHERE id=? AND status='pending'",(identifier,)).rowcount
        if not changed:raise ValueError('Proposal is no longer pending')
        return {'dismissed':True}


def routes(router,briefing):
    def checked(method,*args):
        try:return method(*args)
        except (ValueError,KeyError,TypeError):raise HTTPException(409,'Brief/follow-up request is invalid or unavailable; check configuration and limits') from None
    @router.get('/briefing/settings')
    async def preferences():return briefing.settings()
    @router.put('/briefing/settings')
    async def configure(body:Preferences):return checked(briefing.configure,body)
    @router.post('/briefing/locations')
    async def locations(body:LocationQuery):
        try:return await briefing.locations(body.query)
        except (ValueError,httpx.HTTPError,TimeoutError):raise HTTPException(502,'Location search unavailable; try again later') from None
    @router.post('/briefing/build')
    async def build(body:Scope):
        try:return await briefing.build(body.scope)
        except ValueError:raise HTTPException(409,'Brief preferences changed; build again') from None
    @router.get('/briefing/followups')
    async def followups(scope:str=''):return briefing.followups(scope[:100])
    @router.post('/briefing/followups')
    async def add(body:Followup):return checked(briefing.add_followup,body)
    @router.patch('/briefing/followups/{identifier}')
    async def change(identifier:str,body:FollowupState):return checked(briefing.change_followup,identifier,body.status,body.due)
    @router.delete('/briefing/followups/{identifier}')
    async def remove(identifier:str):return checked(briefing.change_followup,identifier,'deleted')
    @router.post('/briefing/proposals/refresh')
    async def proposals(body:Scope):return briefing.proposals(body.scope,True)
    @router.get('/briefing/proposals')
    async def list_proposals(scope:str=''):return briefing.proposals(scope[:100])
    @router.post('/briefing/proposals/{identifier}/dismiss')
    async def dismiss(identifier:str):return checked(briefing.dismiss,identifier)

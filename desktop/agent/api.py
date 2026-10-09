"""Authenticated proposals and Activity. Execution requires a configured engine.

Approval routes belong to the phone UI, not model tools. Cloud inference must be
configured separately; lack of a free model must not fall back to the local GPU.
"""
import asyncio
import contextlib
from pathlib import Path

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import FileResponse
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from fastapi.routing import APIRoute
from pydantic import BaseModel, Field, ConfigDict, SecretStr

from .approvals import Approvals


class Schedule(BaseModel):
    run_at: float
    interval_seconds: int = 0
    max_runs: int = Field(default=1, ge=1, le=100)
    timezone: str = Field(default='UTC', max_length=100)


class Proposal(BaseModel):
    prompt: str = Field(min_length=1, max_length=8000)
    plan: list[str] = Field(min_length=1, max_length=20)
    schedule: Schedule | None = None
    data_scopes: list[dict] = Field(default_factory=list, max_length=5)


class Approval(BaseModel):
    fingerprint: str = Field(pattern=r'^[a-f0-9]{64}$')


class ActionApproval(Approval):
    model_config = ConfigDict(extra='forbid')
    review_fingerprint: str | None = Field(default=None,pattern=r'^[a-f0-9]{64}$')


class OutgoingDraft(BaseModel):
    model_config = ConfigDict(extra='forbid')
    kind: str = Field(max_length=40)
    destination: str = Field(max_length=2048)
    payload: dict


class PrivateAccountRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()
        async def private_errors(request):
            try: return await handler(request)
            except RequestValidationError:
                if request.url.path.startswith(('/workspace/agent/accounts','/workspace/agent/outgoing','/workspace/agent/actions','/workspace/agent/briefing')):
                    return JSONResponse(status_code=422, content={'detail':'Invalid private account/action request; check required fields and limits'})
                raise
        return private_errors


def install(app, auth, root, engine=None, unavailable_detail=None, poll_seconds=30, outgoing_enabled=False, outgoing_factory=None, grounding_search=None, grounding_reader=None):
    store = Approvals(Path(root) / 'agent.sqlite')
    if engine is not None and hasattr(engine, 'bind'): engine.bind(store)
    router = APIRouter(prefix='/workspace/agent', dependencies=auth, route_class=PrivateAccountRoute)
    from .review import OutgoingReview
    try:
        from .vault import Vault
        vault = Vault(Path(root) / 'vault')
    except ImportError: vault = None
    from .accounts import Accounts, AccountError
    accounts = Accounts(vault) if vault else None
    from .outgoing import Executors, EffectRejected, EffectUncertain
    executors=(outgoing_factory or Executors)(accounts,outgoing_enabled) if accounts else None
    reviewer = OutgoingReview(vault,executors)
    from .briefing import Briefing, routes as briefing_routes
    briefing=Briefing(root,accounts)
    briefing_routes(router,briefing)
    from .grounding import install as install_grounding
    from .public_web import read as public_reader
    grounding=install_grounding(app,auth,grounding_search,grounding_reader or public_reader)
    running = {}
    outgoing_running = {}
    scheduler = None

    def guarded(function, *args):
        try: return function(*args)
        except ValueError as error: raise HTTPException(409, str(error)) from error

    @router.get('/health')
    async def health():
        ready = engine is not None and (not hasattr(engine, 'available') or await engine.available())
        return {'ready': ready, 'gpu': False, 'outgoing_ready':bool(executors and executors.enabled),
                'active_web_reads':grounding.active,'active_outgoing':len(outgoing_running)+(len(accounts.mail_tasks) if accounts else 0)+briefing.busy,
                'detail': ('FRIDAY can research and review outgoing drafts. Exact outgoing approval is available for configured accounts.' if executors and executors.enabled else 'FRIDAY can research and review outgoing drafts. Outgoing activation remains off until account/device verification and explicit setup.') if ready else unavailable_detail or 'Cloud agent unavailable; proposals can be saved while its setup is checked'}

    class OAuthConfig(BaseModel):
        model_config = ConfigDict(extra='forbid')
        client_id: str = Field(max_length=300)
        client_secret: SecretStr | None = Field(default=None, max_length=500)

    class OAuthStart(BaseModel):
        model_config = ConfigDict(extra='forbid')
        provider: str
        features: list[str] = Field(default_factory=lambda: ['mail_read','calendar_read'], max_length=4)

    class OAuthFinish(BaseModel):
        model_config = ConfigDict(extra='forbid')
        flow_id: str = Field(max_length=100)
        state: str = Field(max_length=128)
        code: SecretStr = Field(min_length=1, max_length=8192)

    class MailConfig(BaseModel):
        model_config = ConfigDict(extra='forbid')
        email: str = Field(min_length=1, max_length=320)
        username: str = Field(min_length=1, max_length=320)
        password: SecretStr = Field(min_length=1, max_length=1024)
        imap_host: str = Field(min_length=1, max_length=253)
        smtp_host: str | None = Field(default=None, min_length=1, max_length=253)
        smtp_port: int | None = Field(default=None, strict=True)
        smtp_username: str | None = Field(default=None, min_length=1, max_length=320)
        smtp_password: SecretStr | None = Field(default=None, min_length=1, max_length=1024)

    async def account_call(method, *args):
        if accounts is None: raise HTTPException(503, 'Install and unlock the desktop credential vault first')
        try: return await method(*args)
        except ValueError as error: raise HTTPException(409, str(error)) from None
        except AccountError as error: raise HTTPException(502, str(error)) from None
        except Exception: raise HTTPException(503, 'Account access unavailable; unlock the vault or reconnect the account') from None

    @router.get('/accounts/providers')
    async def account_providers():
        return accounts.status() if accounts else []

    @router.post('/accounts/config/{provider}')
    async def configure_provider(provider: str, body: OAuthConfig):
        if accounts is None: raise HTTPException(503, 'Credential vault unavailable')
        config = body.model_dump(exclude_none=True)
        if 'client_secret' in config: config['client_secret'] = config['client_secret'].get_secret_value()
        try: accounts.configure(provider, config)
        except ValueError as error: raise HTTPException(409, str(error)) from None
        except Exception: raise HTTPException(503, 'Unlock the desktop credential vault first') from None
        return {'provider':provider,'configured':True}

    @router.post('/accounts/oauth/start')
    async def start_sign_in(body: OAuthStart):
        return await account_call(accounts.begin if accounts else None, body.provider, body.features)

    @router.post('/accounts/imap')
    async def connect_mail(body: MailConfig):
        config = body.model_dump(exclude_none=True)
        for name in ('password', 'smtp_password'):
            if name in config: config[name] = config[name].get_secret_value()
        return await account_call(accounts.connect_mail if accounts else None, config)

    @router.post('/accounts/oauth/complete')
    async def complete_sign_in(body: OAuthFinish):
        return await account_call(accounts.complete if accounts else None, body.flow_id, body.state, body.code.get_secret_value())

    @router.post('/accounts/oauth/{flow_id}/cancel')
    async def cancel_sign_in(flow_id: str):
        return await account_call(accounts.cancel_sign_in if accounts else None, flow_id)

    @router.delete('/accounts/{account_id}')
    async def remove_account(account_id: str):
        if vault is None: raise HTTPException(503, 'Credential vault unavailable')
        guarded(vault.remove, account_id)
        return {'removed':True,'detail':'Removed from FRIDAY. Provider grants can also be revoked in its account settings.'}

    @router.get('/accounts/{account_id}/mail')
    async def read_mail(account_id: str, query: str = Query(default='',max_length=500), limit: int = Query(default=10,ge=1,le=20)):
        return await account_call(accounts.read_messages if accounts else None, account_id, query, limit)

    @router.get('/accounts/{account_id}/mail/{message_id:path}')
    async def read_message(account_id: str, message_id: str):
        return await account_call(accounts.read_message if accounts else None, account_id, message_id)

    @router.get('/accounts/{account_id}/calendar')
    async def read_calendar(account_id: str, start: str, end: str, limit: int = Query(default=30,ge=1,le=100)):
        return await account_call(accounts.read_calendar if accounts else None, account_id, start, end, limit)

    @router.get('/accounts/{account_id}/calendar/events/{event_id:path}')
    async def read_calendar_event(account_id: str,event_id: str):
        return await account_call(accounts.read_calendar_event if accounts else None,account_id,event_id)

    @router.post('/outgoing/drafts')
    async def outgoing_draft(body: OutgoingDraft):
        action=body.model_dump()
        review=guarded(reviewer.inspect,action)
        if not review['allowed']:raise HTTPException(409,{'detail':'Outgoing payload needs changes','issues':review['issues']})
        value=guarded(store.propose_outgoing,action)
        value['review']=review
        return value

    @router.get('/accounts')
    async def list_accounts():
        return {'accounts': vault.accounts() if vault else [], 'vault_installed': vault is not None,
                'detail': 'Google/Microsoft and public TLS IMAP accounts support bounded reads. Credentials have no read/export API. SMTP settings are saved but sending remains disabled.'}

    @router.get('/tasks')
    async def tasks(limit: int = Query(default=50, ge=1, le=100)):
        return store.tasks(limit)

    @router.post('/tasks')
    async def propose(body: Proposal):
        if any(not step.strip() or len(step) > 2000 for step in body.plan):
            raise HTTPException(422, 'Each plan step must contain 1–2000 characters')
        scopes = guarded(accounts.bind_scopes, body.data_scopes) if accounts else []
        if body.data_scopes and not accounts: raise HTTPException(503, 'Account data is unavailable')
        return guarded(store.propose_task, body.prompt, body.plan, body.schedule.model_dump() if body.schedule else None, scopes)

    @router.get('/tasks/{task_id}')
    async def task(task_id: str):
        value = guarded(store.task, task_id)
        value['actions'] = store.actions(task_id)
        for action in value['actions']:
            action['review'] = reviewer.inspect(action['payload'])
        value['result'] = store.result(task_id)
        value['runs'] = store.runs(task_id)
        return value

    @router.get('/tasks/{task_id}/report')
    async def task_report(task_id: str):
        value = guarded(store.task, task_id)
        return {'id': task_id, 'status': value['status'], 'prompt': value['proposal']['prompt'],
                'result': store.result(task_id)}

    @router.get('/tasks/{task_id}/events')
    async def events(task_id: str, after: int = Query(default=0, ge=0), limit: int = Query(default=100, ge=1, le=200)):
        guarded(store.task, task_id)
        return store.events(task_id, after, limit)

    @router.get('/tasks/{task_id}/screenshots/{identifier}')
    async def screenshot(task_id: str, identifier: str):
        target = guarded(store.screenshot, task_id, identifier)
        return FileResponse(target, media_type='image/png', headers={'Cache-Control': 'no-store'})

    async def execute(task_id, fingerprint):
        try:
            store.start_task(task_id, fingerprint)
            async def report(kind, data):
                current = store.task(task_id)
                if current['status'] != 'running': raise asyncio.CancelledError()
                with store.db() as db: store.event(db, task_id, kind, data)
            selected = store.task(task_id)
            scopes = selected['proposal'].get('data_scopes', [])
            if scopes:
                if accounts is None: raise RuntimeError('Account data is unavailable')
                selected['account_context'] = await accounts.scoped_snapshot(scopes)
                selected['account_context_guard'] = lambda: accounts.validate_scopes(scopes)
                await report('account_scopes_read', {'reads':len(scopes), 'untrusted':True, 'public_web_disabled':True})
            result = await engine(selected, report)
            await report('result', result)
            if any(action['status'] in ('proposed','approved','claimed','uncertain') for action in store.actions(task_id)):
                store.await_setup(task_id, fingerprint)
            else:
                store.finish_task(task_id, fingerprint)
        except asyncio.CancelledError:
            # Cancellation has already revoked pending action approvals.
            if store.task(task_id)['status'] == 'running': store.cancel_task(task_id)
            raise
        except Exception as error:
            if store.task(task_id)['status'] == 'running':
                with store.db() as db:
                    store.event(db, task_id, 'error', {'message': type(error).__name__})
                store.finish_task(task_id, fingerprint, False)
        finally: running.pop(task_id, None)

    @router.post('/tasks/{task_id}/approve')
    async def approve(task_id: str, body: Approval):
        if engine is None or hasattr(engine, 'available') and not await engine.available():
            raise HTTPException(503, 'Cloud agent is not ready; no approval was recorded')
        guarded(store.approve_task, task_id, body.fingerprint)
        if store.task(task_id)['status'] == 'approved':
            running[task_id] = asyncio.create_task(execute(task_id, body.fingerprint))
        return store.task(task_id)

    @router.post('/tasks/{task_id}/cancel')
    async def cancel(task_id: str):
        children = guarded(store.cancel_task, task_id)
        for identifier in [task_id, *children]:
            if identifier in running: running[identifier].cancel()
        for action_id,future in list(outgoing_running.items()):
            if store.action(action_id)['task_id'] in [task_id,*children]:future.cancel()
        return store.task(task_id)

    @router.get('/actions/{action_id}/review')
    async def review_action(action_id: str):
        action=guarded(store.action,action_id)
        return reviewer.inspect(action['payload'])

    async def execute_action(action_id,review_fingerprint):
        try:
            action=store.action(action_id)
            def guard():
                current=store.action(action_id)
                if current['status']!='claimed' or store.task(current['task_id'])['status'] not in ('running','awaiting_setup'):
                    raise EffectRejected('Action canceled or interrupted before submission')
                check=reviewer.inspect(current['payload'])
                if not check['executable'] or check['review_fingerprint']!=review_fingerprint:
                    raise EffectRejected('Account/review changed before submission; create a new proposal')
            receipt=await executors.execute(action['payload'],action_id,guard)
            store.finish_action(action_id,'accepted',receipt)
        except EffectRejected as error:
            store.finish_action(action_id,'rejected',{'detail':str(error),'automatic_retry':False})
        except asyncio.CancelledError:
            store.finish_action(action_id,'uncertain',{'detail':'Canceled/interrupted during submission; inspect provider records before proposing again','automatic_retry':False})
            raise
        except Exception:
            store.finish_action(action_id,'uncertain',{'detail':'No reliable provider receipt; inspect provider records before proposing again','automatic_retry':False})
        finally:outgoing_running.pop(action_id,None)

    @router.post('/actions/{action_id}/approve')
    async def approve_action(action_id: str, body: ActionApproval):
        if executors is None or not executors.enabled:raise HTTPException(503,'Outgoing activation is off; no approval recorded')
        action=guarded(store.action,action_id)
        review=guarded(reviewer.inspect,action['payload'])
        if not review['allowed']:raise HTTPException(409,'Outgoing review failed; no approval recorded')
        if not review['executable']:raise HTTPException(503,'Account/permission is unavailable; no approval recorded')
        if body.fingerprint!=review['fingerprint'] or body.review_fingerprint!=review['review_fingerprint']:raise HTTPException(409,'Exact payload/account review changed; review it again before approving')
        guarded(store.approve_action,action_id,body.fingerprint)
        guarded(store.claim_action,action_id,action['payload'])
        outgoing_running[action_id]=asyncio.create_task(execute_action(action_id,body.review_fingerprint))
        return store.action(action_id)

    async def schedule_loop():
        while True:
            try:
                if engine is not None and (not hasattr(engine, 'available') or await engine.available()):
                    for task in store.claim_due():
                        running[task['id']] = asyncio.create_task(execute(task['id'], task['fingerprint']))
            except asyncio.CancelledError: raise
            except Exception: pass  # Retry without granting a new approval or local/paid fallback.
            await asyncio.sleep(poll_seconds)

    async def recover():
        nonlocal scheduler
        store.interrupt_running()
        scheduler = asyncio.create_task(schedule_loop())

    async def stop():
        pending = list(running.values()) + list(outgoing_running.values()) + ([scheduler] if scheduler else [])
        for future in pending: future.cancel()
        for future in pending:
            with contextlib.suppress(asyncio.CancelledError): await future
        if accounts: await accounts.close()
        await briefing.weather.close()

    app.router.add_event_handler('startup', recover)
    app.router.add_event_handler('shutdown', stop)
    app.include_router(router)
    return store

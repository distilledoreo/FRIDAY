"""Authenticated proposals and Activity. Execution requires a configured engine.

Approval routes belong to the phone UI, not model tools. Cloud inference must be
configured separately; lack of a free model must not fall back to the local GPU.
"""
import asyncio
import contextlib
from pathlib import Path

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

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


class Approval(BaseModel):
    fingerprint: str = Field(pattern=r'^[a-f0-9]{64}$')


def install(app, auth, root, engine=None, unavailable_detail=None, poll_seconds=30):
    store = Approvals(Path(root) / 'agent.sqlite')
    if engine is not None and hasattr(engine, 'bind'): engine.bind(store)
    router = APIRouter(prefix='/workspace/agent', dependencies=auth)
    running = {}
    scheduler = None

    def guarded(function, *args):
        try: return function(*args)
        except ValueError as error: raise HTTPException(409, str(error)) from error

    @router.get('/health')
    async def health():
        ready = engine is not None and (not hasattr(engine, 'available') or await engine.available())
        return {'ready': ready, 'gpu': False, 'outgoing_ready': False,
                'detail': 'FRIDAY can research public pages and report back. Account actions are still being connected.' if ready else unavailable_detail or 'Cloud agent unavailable; proposals can be saved while its setup is checked'}

    @router.get('/tasks')
    async def tasks(limit: int = Query(default=50, ge=1, le=100)):
        return store.tasks(limit)

    @router.post('/tasks')
    async def propose(body: Proposal):
        if any(not step.strip() or len(step) > 2000 for step in body.plan):
            raise HTTPException(422, 'Each plan step must contain 1–2000 characters')
        return guarded(store.propose_task, body.prompt, body.plan, body.schedule.model_dump() if body.schedule else None)

    @router.get('/tasks/{task_id}')
    async def task(task_id: str):
        value = guarded(store.task, task_id)
        value['actions'] = store.actions(task_id)
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
            result = await engine(store.task(task_id), report)
            await report('result', result)
            if any(action['status'] == 'proposed' for action in store.actions(task_id)):
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
        return store.task(task_id)

    @router.post('/actions/{action_id}/approve')
    async def approve_action(action_id: str, body: Approval):
        # No executor is installed yet. Do not record an unusable approval or
        # imply that a saved proposal has actually submitted/sent/logged in.
        raise HTTPException(503, 'This action cannot run until its account/executor and outgoing review are connected; no approval recorded')

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
        pending = list(running.values()) + ([scheduler] if scheduler else [])
        for future in pending: future.cancel()
        for future in pending:
            with contextlib.suppress(asyncio.CancelledError): await future

    app.router.add_event_handler('startup', recover)
    app.router.add_event_handler('shutdown', stop)
    app.include_router(router)
    return store

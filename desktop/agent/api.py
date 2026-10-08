"""Authenticated proposals and Activity. Execution requires a configured engine.

Approval routes belong to the phone UI, not model tools. Cloud inference must be
configured separately; lack of a free model must not fall back to the local GPU.
"""
import asyncio
import contextlib
from pathlib import Path

from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel, Field

from .approvals import Approvals


class Proposal(BaseModel):
    prompt: str = Field(min_length=1, max_length=8000)
    plan: list[str] = Field(min_length=1, max_length=20)


class Approval(BaseModel):
    fingerprint: str = Field(pattern=r'^[a-f0-9]{64}$')


def install(app, auth, root, engine=None):
    store = Approvals(Path(root) / 'agent.sqlite')
    router = APIRouter(prefix='/workspace/agent', dependencies=auth)
    running = {}

    def guarded(function, *args):
        try: return function(*args)
        except ValueError as error: raise HTTPException(409, str(error)) from error

    @router.get('/health')
    async def health():
        return {'ready': engine is not None, 'gpu': False,
                'detail': 'Cloud engine ready' if engine else 'Cloud agent setup is in progress; proposals can be saved'}

    @router.get('/tasks')
    async def tasks(limit: int = Query(default=50, ge=1, le=100)):
        return store.tasks(limit)

    @router.post('/tasks')
    async def propose(body: Proposal):
        if any(not step.strip() or len(step) > 2000 for step in body.plan):
            raise HTTPException(422, 'Each plan step must contain 1–2000 characters')
        return guarded(store.propose_task, body.prompt, body.plan)

    @router.get('/tasks/{task_id}')
    async def task(task_id: str):
        value = guarded(store.task, task_id)
        value['actions'] = store.actions(task_id)
        return value

    @router.get('/tasks/{task_id}/events')
    async def events(task_id: str, after: int = Query(default=0, ge=0), limit: int = Query(default=100, ge=1, le=200)):
        guarded(store.task, task_id)
        return store.events(task_id, after, limit)

    async def execute(task_id, fingerprint):
        try:
            store.start_task(task_id, fingerprint)
            async def report(kind, data):
                current = store.task(task_id)
                if current['status'] != 'running': raise asyncio.CancelledError()
                with store.db() as db: store.event(db, task_id, kind, data)
            result = await engine(store.task(task_id), report)
            await report('result', result)
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
        if engine is None: raise HTTPException(503, 'Cloud agent is not ready; no approval was recorded')
        guarded(store.approve_task, task_id, body.fingerprint)
        running[task_id] = asyncio.create_task(execute(task_id, body.fingerprint))
        return store.task(task_id)

    @router.post('/tasks/{task_id}/cancel')
    async def cancel(task_id: str):
        guarded(store.cancel_task, task_id)
        if task_id in running: running[task_id].cancel()
        return store.task(task_id)

    @router.post('/actions/{action_id}/approve')
    async def approve_action(action_id: str, body: Approval):
        guarded(store.approve_action, action_id, body.fingerprint)
        return {'status': 'approved'}

    async def recover(): store.interrupt_running()

    async def stop():
        pending = list(running.values())
        for future in pending: future.cancel()
        for future in pending:
            with contextlib.suppress(asyncio.CancelledError): await future

    app.router.add_event_handler('startup', recover)
    app.router.add_event_handler('shutdown', stop)
    app.include_router(router)
    return store

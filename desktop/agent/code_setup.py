"""New local projects and operator-reviewed environment recipes."""
import json
import os
from pathlib import Path
import re

from fastapi import HTTPException
from pydantic import BaseModel, ConfigDict, Field


class NewProject(BaseModel):
    model_config = ConfigDict(extra='forbid')
    path: str = Field(min_length=1, max_length=4096)
    template: str = Field(default='empty', pattern='^(empty|python|node)$')


class SetupStep(BaseModel):
    model_config = ConfigDict(extra='forbid')
    name: str = Field(min_length=1, max_length=100)
    command: str = Field(min_length=1, max_length=2000)
    cwd: str = Field(default='.', min_length=1, max_length=500)


class EnvironmentRecipe(BaseModel):
    model_config = ConfigDict(extra='forbid')
    steps: list[SetupStep] = Field(default_factory=list, max_length=12)
    environment: dict[str, str] = Field(default_factory=dict, max_length=20)


class ProjectSetup:
    def __init__(self, code):
        self.code = code
        with code.db() as db:
            columns = {r['name'] for r in db.execute('PRAGMA table_info(projects)')}
            if 'setup' not in columns: db.execute("ALTER TABLE projects ADD COLUMN setup TEXT NOT NULL DEFAULT '[]'")
            if 'environment' not in columns: db.execute("ALTER TABLE projects ADD COLUMN environment TEXT NOT NULL DEFAULT '{}'")
            task_columns = {r['name'] for r in db.execute('PRAGMA table_info(tasks)')}
            if 'verification_epoch' not in task_columns: db.execute('ALTER TABLE tasks ADD COLUMN verification_epoch INTEGER NOT NULL DEFAULT 0')

    async def create(self, body):
        raw = Path(body.path).expanduser()
        if '\0' in body.path or not raw.is_absolute(): raise HTTPException(422, 'Choose an absolute new folder on the PC')
        try: parent = raw.parent.resolve(strict=True)
        except (OSError, RuntimeError): raise HTTPException(422, 'The parent folder must already exist') from None
        target = parent / raw.name
        if raw.name in ('.', '..', ''): raise HTTPException(422, 'Choose a new project folder')
        inside, _, _ = await self.code.run(self.code.git, 'rev-parse', '--show-toplevel', cwd=parent, check=False)
        if inside == 0: raise HTTPException(422, 'Choose a folder outside an existing Git repository; open that repository for ordinary coding work')
        try: target.mkdir(mode=0o700)
        except FileExistsError: raise HTTPException(409, 'The folder already exists; open it as a local repository instead') from None
        files = {'README.md': '# ' + target.name + '\n\nLocal project created by FRIDAY.\n', '.gitignore': '.env\n__pycache__/\n.venv/\nnode_modules/\n'}
        checks = []
        if body.template == 'python':
            files.update({'app.py': 'def greet(name):\n    return f"Hello, {name}!"\n',
                'tests/test_app.py': 'import unittest\nfrom app import greet\n\nclass GreetingTest(unittest.TestCase):\n    def test_greeting(self):\n        self.assertEqual(greet("World"), "Hello, World!")\n'})
            checks = [dict(name='Unit tests', command='python3 -m unittest discover -s tests -v', cwd='.', runner='tests', min_tests=1)]
        elif body.template == 'node':
            files.update({'package.json': json.dumps({'name': 'local-project', 'private': True, 'type': 'module', 'scripts': {'test': 'node --test'}}, indent=2) + '\n',
                'app.mjs': 'export const greet = (name) => `Hello, ${name}!`;\n',
                'test/app.test.mjs': 'import test from "node:test";\nimport assert from "node:assert/strict";\nimport { greet } from "../app.mjs";\ntest("greeting", () => assert.equal(greet("World"), "Hello, World!"));\n'})
            checks = [dict(name='Unit tests', command='node --test', cwd='.', runner='tests', min_tests=1)]
        try:
            for name, content in files.items():
                path = target / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
            await self.code.run(self.code.git, 'init', '--quiet', '-b', 'main', cwd=target)
            await self.code.run(self.code.git, 'add', '--', *files, cwd=target)
            await self.code.run(self.code.git, '-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgsign=false', '-c', 'user.name=FRIDAY',
                                '-c', 'user.email=friday@localhost.invalid', 'commit', '--quiet', '-m', 'Initialize local project', cwd=target)
            project = await self.code.add_local(str(target))
            if checks:
                from .code import VerificationProfile
                project = self.code.set_checks(project['id'], VerificationProfile(checks=checks))
            return project
        except Exception:
            # Do not recursively delete a folder that another process may have
            # written to. Partial scaffolding is retained for inspection/recovery.
            raise HTTPException(409, 'Project initialization stopped; the new folder was preserved for inspection') from None

    def get(self, project_id):
        project = self.code.project(project_id)
        return {'steps': project['setup'], 'environment': project['environment']}

    def set(self, project_id, body):
        from .verification import save_profile
        project = self.code.project(project_id)
        steps = [s.model_dump() for s in body.steps]
        for step in steps:
            step['name'], step['command'] = step['name'].strip(), step['command'].strip()
            if not step['name'] or not step['command'] or any(c in step['command'] for c in ('\0', '\n', '\r')):
                raise HTTPException(422, 'Use a name and one command per setup step')
            if Path(step['cwd']).is_absolute() or '..' in Path(step['cwd']).parts or '\0' in step['cwd']:
                raise HTTPException(422, 'Setup folders must stay inside the worktree')
            step['cwd'] = str(Path(step['cwd']))
        for name, value in body.environment.items():
            if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,127}', name) or len(value) > 4096 or '\0' in value:
                raise HTTPException(422, 'Invalid environment variable')
        with self.code.db() as db:
            tasks = db.execute('SELECT worktree,verification_epoch FROM tasks WHERE project=? AND closed=0', (project_id,)).fetchall()
            for task in tasks: save_profile(self.code.pc.root / 'code-verification', task['worktree'], project['checks'], environment=body.environment, setup=steps, epoch=task['verification_epoch'])
            db.execute('UPDATE projects SET setup=?,environment=? WHERE id=?', (json.dumps(steps), json.dumps(body.environment), project_id))
        return self.get(project_id)

    async def prepare(self, task_id):
        task = self.code.task(task_id)
        if self.code.pc.agent_of(task['session']) == 'friday-code-plan': raise HTTPException(409, 'Approve implementation before running environment setup')
        project = self.code.project(task['project'])
        if not project['setup']: raise HTTPException(409, 'Configure reviewed setup commands in the repository menu first')
        await self.code.require_idle(task)
        # Dependency installation can change ignored files without changing the
        # Git fingerprint. Invalidate old checks before dispatching setup.
        with self.code.db() as db: db.execute('UPDATE tasks SET verification_epoch=verification_epoch+1 WHERE id=?', (task_id,))
        from .verification import save_profile
        epoch = self.code.task(task_id)['verification_epoch']
        save_profile(self.code.pc.root / 'code-verification', task['worktree'], project['checks'], environment=project['environment'], setup=project['setup'], epoch=epoch)
        instructions = '\n'.join(f'{i+1}. {step["name"]}: cwd={step["cwd"]}; exact command={step["command"]}' for i, step in enumerate(project['setup']))
        return await self.code.follow_up(task_id, 'Prepare this project using the following operator-reviewed setup recipe. '
            'Run each command separately through the normal permission-checked shell tool in its stated folder. '
            'Inspect exits, stop on failure and explain the cause; do not substitute installers, change global tools or deploy/restart FRIDAY. '
            'Setup invalidates previous verification, including checks affected by ignored dependencies. Run the required project checks after setup. '
            'Report actual commands/results and any unavailable dependencies. Environment values are supplied by the coding hook; do not print secrets.\n\n' + instructions)

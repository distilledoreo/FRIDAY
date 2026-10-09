"""Apply FRIDAY privacy/priority hooks while preserving the live TTFT adapter."""
import argparse,ast
from pathlib import Path
import shutil,time

def patch(api_dir):
    api_dir=Path(api_dir);app=api_dir/'app.py';cache=api_dir/'prompt_cache.py'
    text=app.read_text();cache_text=cache.read_text()
    old='    body = prompt_cache.stabilize(await request.json())\n    await openings.before_chat(body)'
    new='    from imagegen.privacy import prepare_chat\n    body = await prepare_chat(await request.json(), request.headers.get("X-Assistant-Incognito") == "1", prompt_cache, openings)'
    if old not in text and new not in text:raise ValueError('Gateway chat adapter changed; review the privacy hook before deployment')
    updated=text.replace(old,new).replace('app.add_event_handler(', 'app.router.add_event_handler(')
    updated_cache=cache_text.replace('while self.gate.readers or self.gate.exclusive:', 'while not self.gate.background_allowed():')
    ast.parse(updated);ast.parse(updated_cache)
    if (updated,updated_cache)==(text,cache_text):return False
    backup=api_dir/('before-friday-hooks-'+str(time.time_ns()));backup.mkdir(mode=0o700)
    for path,value in [(app,updated),(cache,updated_cache)]:
        shutil.copy2(path,backup/path.name);path.write_text(value)
    return True

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('api_dir',type=Path)
    print('Gateway hooks patched:',patch(parser.parse_args().api_dir))

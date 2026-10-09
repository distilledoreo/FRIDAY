"""Parse inert ChatGPT export text; select the current branch, never execute tools."""
import hashlib
import json
from pathlib import PurePosixPath
import zipfile

MAX_JSON = 512 * 1024 * 1024
MAX_CHATS = 20000

def read_export(path):
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            matches = [i for i in z.infolist() if PurePosixPath(i.filename.replace('\\', '/')).name == 'conversations.json']
            if len(matches) != 1:
                raise ValueError('Choose a ChatGPT export containing one conversations.json file')
            item = matches[0]
            if item.file_size > MAX_JSON or item.flag_bits & 1:
                raise ValueError('Export JSON exceeds 512 MB or is encrypted')
            with z.open(item) as f:
                raw = f.read(MAX_JSON + 1)
    else:
        with open(path, 'rb') as f: raw = f.read(MAX_JSON + 1)
    if len(raw) > MAX_JSON: raise ValueError('Export JSON exceeds 512 MB')
    value = json.loads(raw)
    chats = value.get('conversations') if isinstance(value, dict) else value
    if not isinstance(chats, list) or len(chats) > MAX_CHATS:
        raise ValueError('Expected at most 20,000 exported conversations')
    result, skipped, warnings = [], 0, set()
    for chat in chats:
        if not isinstance(chat, dict): skipped += 1; continue
        mapping = chat.get('mapping')
        if not isinstance(mapping, dict): skipped += 1; continue
        node = chat.get('current_node')
        if node not in mapping:
            leaves = [key for key, item in mapping.items() if isinstance(item, dict) and not item.get('children')]
            def stamp(key):
                message = mapping[key].get('message') or {}
                return number(message.get('create_time'))
            node = max(leaves, key=stamp) if leaves else None
            warnings.add('Some chats had no current branch; the newest leaf was used.')
        chain, visited = [], set()
        while node in mapping:
            if node in visited or len(visited) > 100000: raise ValueError('Export contains a cyclic or oversized conversation')
            visited.add(node); item = mapping[node]
            if not isinstance(item, dict): break
            chain.append(item.get('message')); node = item.get('parent')
        messages = []
        for message in reversed(chain):
            if not isinstance(message, dict): continue
            role = (message.get('author') or {}).get('role')
            if role not in ('user', 'assistant') or message.get('channel') == 'analysis': continue
            metadata = message.get('metadata') or {}
            if metadata.get('is_visually_hidden_from_conversation') or message.get('recipient', 'all') not in ('all', None): continue
            content = message.get('content') or {}
            parts = content.get('parts', [])
            text = '\n'.join(p if isinstance(p, str) else p.get('text', '') if isinstance(p, dict) and isinstance(p.get('text'), str) else '' for p in parts).strip()
            if not text and isinstance(content.get('text'), str): text = content['text'].strip()
            if not text: continue
            if len(text) > 2_000_000: raise ValueError('A message exceeds the two-million-character limit')
            messages.append({'role':role, 'content':text})
        if not messages: skipped += 1; continue
        encoded = json.dumps(messages, ensure_ascii=False, separators=(',', ':'))
        if len(encoded.encode()) > 8 * 1024 * 1024: raise ValueError('A conversation exceeds 8 MB; import a smaller export')
        origin = str(chat.get('id') or chat.get('conversation_id') or hashlib.sha256(encoded.encode()).hexdigest())
        result.append({'id':'chatgpt-' + hashlib.sha256(origin.encode()).hexdigest()[:32], 'title':str(chat.get('title') or 'Imported ChatGPT chat')[:200],
                       'created':number(chat.get('create_time')), 'updated':number(chat.get('update_time')), 'messages':messages,
                       'digest':hashlib.sha256(encoded.encode()).hexdigest(), 'origin':'ChatGPT', 'scope':''})
    if not result: raise ValueError('No readable user/assistant conversations found')
    warnings.add('Text and the selected conversation branch are imported; media, system prompts and tool execution are excluded.')
    return result, skipped, sorted(warnings)

def number(value):
    return float(value) if isinstance(value, (int, float)) and 0 <= value < 100000000000 else 0.0

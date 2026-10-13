"""Finds private details a public web call would carry out, as Android's PrivateLeak.kt does.

Keep in step with core/src/commonMain/.../grounding/PrivateLeak.kt and GroundingPrecheck (the shared Kotlin
originals). Words that came from recalled memories or private tool results (inbox, calendar, briefs) and that the
user didn't write themselves are flagged. Contact details, street addresses and ID numbers always count as
identifying; other details are judged by the model, so only things that single out a private person need approval.
"""
import json

import regex

RECALL_MARKER = '\n\nRetrieved personal context.'
# Tool results that bring private data into a conversation.
PRIVATE_TOOLS = {'get_pc_task', 'read_account_inbox', 'read_account_message', 'read_account_calendar', 'read_account_calendar_event',
                 'read_daily_brief', 'list_followups', 'search_memory', 'search_history', 'get_agent_report'}
WEB_TOOLS = {'web_search', 'fetch_page'}

_word = regex.compile(r"[\p{L}\p{N}][\p{L}\p{N}'’]*")
_contact = regex.compile(r'[\w.+-]+@[\w-]+(?:\.[\w-]+)+|\+?\d[\d\s().-]{7,}\d')
_street = regex.compile(r'\b\d{1,6}\s+(?:[\p{L}.]+\s+){1,3}(?:st|street|ave|avenue|rd|road|blvd|boulevard|dr|drive|ln|lane|ct|court|way|pl|place|pkwy|parkway|cir|circle|trl|trail|hwy|highway)\b', regex.IGNORECASE)
_id_number = regex.compile(r'\b\d{6,}\b')
_sentence = regex.compile(r'[^.!?\n]+[.!?]?')

JUDGE_INSTRUCTIONS = ("You check web searches for a personal assistant before they are sent. Decide whether the listed details, "
    "which came from the user's private memories or accounts, would identify a specific private person. Identifying: names of private "
    "people (family, friends, coworkers, contacts), their contact details or handles, home or street addresses, account, order or ID numbers, "
    "or a combination that points to one private individual. Not identifying: cities, regions and countries, interests, preferences, "
    "beliefs, devices and products, companies and organizations, public figures, dates and general facts. Reply with exactly one word: "
    "IDENTIFYING or OK.")

COMMON = set("""
    the and for are but not you all any can had her was one our out day get has him his how man new now old see two way who
    its did let put say she too use with that this from they have will your what when where which while there their them then
    than been were would could should about after again also back because before being best between both came come does done down
    each even every find first give good great here into just know last like long look made make many more most much must near
    need next only other over people right same some still such take tell than these thing things think those through time today
    tomorrow tonight under very want well went year years week weeks month months open close closed hours price prices cost
    cheap free near nearby best top latest news current recent update updates review reviews guide how-to vs versus compare
    weather forecast temperature rain snow sunny wind traffic score scores game games schedule result results live stream
    official website site page online buy sale deal deals local city town state country world service services company store
    stores shop restaurant restaurants food recipe recipes movie movies show shows song songs music book books app apps phone
    phones release date dates time times today's tonight's morning evening night weekend monday tuesday wednesday thursday
    friday saturday sunday january february march april may june july august september october november december 2024 2025 2026 2027
    user user's mentioned memory memories chat chats fact facts approved past said says prefers likes like loves enjoys wants
    work works working home family friend friends help information info about details detail question answer answers
""".split())


def _words(text):
    return [w for w in (m.group(0).strip("'") for m in _word.finditer(text.lower().replace('’', "'"))) if w]


def details(outgoing, private_texts, user_text):
    """The private words in [outgoing], or empty when it's safe to send without asking."""
    if not private_texts or not outgoing.strip():
        return []
    own = set(_words(user_text))
    private = {w for text in private_texts for w in _words(text)}
    found = []
    for match in _contact.finditer(outgoing):
        value = match.group(0).strip()
        if any(value in text for text in private_texts) and value not in user_text and value not in found:
            found.append(value)
    for w in _words(_contact.sub(' ', outgoing)):
        if len(w) >= 3 and w not in COMMON and w in private and w not in own and w not in found:
            found.append(w)
    return found


def find(outgoing, private_texts, user_text):
    """(identifying, other, context): [identifying] always needs approval; [other] only if the model judges it identifying."""
    if not private_texts or not outgoing.strip():
        return [], [], []
    identifying = []
    for pattern in (_contact, _street, _id_number):
        for match in pattern.finditer(outgoing):
            value = match.group(0).strip()
            if any(value.lower() in text.lower() for text in private_texts) and value.lower() not in user_text.lower() and value not in identifying:
                identifying.append(value)
    other = [w for w in details(outgoing, private_texts, user_text) if not any(w in i.lower() for i in identifying)]
    context = []
    for text in private_texts:
        for m in _sentence.finditer(text):
            s = m.group(0).strip()
            if any(o.lower() in s.lower() for o in other) and s not in context:
                context.append(s)
    return identifying, other, [c[:240] for c in context[:6]]


def judge_request(outgoing, other, context):
    return f"Search or URL: {outgoing}\nDetails from private data: {', '.join(other)}\nWhere they came from:\n" + '\n'.join(f'- {c}' for c in context)


def judged_identifying(reply):
    """True unless the reply clearly says OK."""
    if reply is None:
        return True
    text = reply.upper()
    return 'IDENTIFYING' in text or 'OK' not in text


def recalled(system_prompt):
    """The recalled memories in a system prompt, without the fixed explanation that introduces them."""
    start = system_prompt.find(RECALL_MARKER)
    if start < 0:
        return None
    body = system_prompt.find('\n', start + len(RECALL_MARKER))
    if body < 0:
        return None
    return system_prompt[body:].strip() or None


def argument_strings(arguments):
    try:
        value = json.loads(arguments) if isinstance(arguments, str) else arguments
    except ValueError:
        return arguments
    def collect(v):
        if isinstance(v, str): return [v]
        if isinstance(v, dict): return [s for x in v.values() for s in collect(x)]
        if isinstance(v, list): return [s for x in v for s in collect(x)]
        return []
    return '\n'.join(collect(value))


def text_of(content):
    if isinstance(content, list):
        return '\n'.join(part.get('text', '') for part in content if isinstance(part, dict))
    return content or ''


async def identifying_details(messages, call, judge):
    """Private details a web call would carry that identify someone; empty means it can go without asking.

    [judge] sends the yes/no question to the model and returns its text, or raises when it can't."""
    system = text_of(messages[0].get('content')) if messages and messages[0].get('role') == 'system' else ''
    names = {c['id']: c['function']['name'] for m in messages if m.get('role') == 'assistant' for c in m.get('tool_calls') or []}
    private = [r for r in [recalled(system)] if r]
    for m in messages:
        if m.get('private_context'):
            private.append(m['private_context'])
        if m.get('role') == 'tool' and names.get(m.get('tool_call_id')) in PRIVATE_TOOLS:
            try: result = json.loads(m.get('content') or '')
            except ValueError: result = None
            if not (isinstance(result, dict) and (result.get('error') or result.get('success') is False)):
                private.append(m.get('content') or '')
    own = '\n'.join(text_of(m.get('content')[:1] if isinstance(m.get('content'), list) else m.get('content')) for m in messages if m.get('role') == 'user')
    outgoing = argument_strings(call['function']['arguments'])
    identifying, other, context = find(outgoing, private, own)
    if not identifying and not other:
        return []
    if identifying:
        return identifying + other
    try:
        reply = await judge([{'role': 'system', 'content': JUDGE_INSTRUCTIONS}, {'role': 'user', 'content': judge_request(outgoing, other, context)}])
    except Exception:
        reply = None  # Can't judge: ask.
    return other if judged_identifying(reply) else []


BLOCKED_WEB = "Web access is disabled for this turn. Respect the user's offline/no-search request."

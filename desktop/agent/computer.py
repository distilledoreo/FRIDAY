"""Screenshots and mouse/keyboard input on the user's X11 desktop, for FRIDAY's computer use.

Run as an MCP server (``python -m agent.computer``) inside FRIDAY's OpenCode on the PC. OpenCode's
own permission rules decide whether each tool call runs or asks the user first.
"""
import base64
import contextlib
import json
import os
import subprocess
import sys
import time

from pydantic import BaseModel, ConfigDict, Field, model_validator


class Input(BaseModel):
    model_config = ConfigDict(extra='forbid', allow_inf_nan=False)
    action: str = Field(pattern='^(click|double_click|right_click|middle_click|move|drag|type|key|scroll)$')
    x: float | None = Field(default=None, ge=0)
    y: float | None = Field(default=None, ge=0)
    x2: float | None = Field(default=None, ge=0)
    y2: float | None = Field(default=None, ge=0)
    text: str | None = Field(default=None, max_length=4000)
    keys: str | None = Field(default=None, max_length=100)
    direction: str | None = Field(default=None, pattern='^(up|down|left|right)$')
    amount: int = Field(default=3, ge=1, le=30)

    @model_validator(mode='after')
    def complete_action(self):
        if (self.x is None) != (self.y is None): raise ValueError('Coordinates need both x and y')
        if self.action == 'move' and self.x is None: raise ValueError('Move needs x and y')
        if self.action == 'drag' and None in (self.x, self.y, self.x2, self.y2): raise ValueError('Drag needs x, y, x2 and y2')
        if self.action == 'type' and not self.text: raise ValueError('Type needs text')
        if self.action == 'key' and (not self.keys or not all(p.strip() for p in self.keys.split('+'))): raise ValueError('Key needs keys, like ctrl+l')
        return self


class Computer:
    def __init__(self, display=None, max_width=1920):
        self.display, self.max_width = display, max_width
        self.screen_scale = 1.0

    def _display(self):
        return self.display or os.environ.get('DISPLAY') or ':0'

    def _xlib(self):
        try:
            from Xlib import display as xdisplay  # noqa: F401
            return xdisplay
        except ImportError: return None

    def screenshot(self):
        env = dict(os.environ, DISPLAY=self._display())
        result = subprocess.run(['import', '-silent', '-window', 'root', '-resize', f'{self.max_width}x>', 'png:-'],
                                env=env, capture_output=True, timeout=20)
        image = result.stdout
        if result.returncode != 0 or len(image) < 24 or image[:8] != b'\x89PNG\r\n\x1a\n': raise RuntimeError('Screenshot failed')
        width, height = int.from_bytes(image[16:20], 'big'), int.from_bytes(image[20:24], 'big')
        real = self._screen_size()
        self.screen_scale = (real[0] / width) if real else 1.0
        return image, width, height

    def _screen_size(self):
        xdisplay = self._xlib()
        if xdisplay is None: return None
        with contextlib.suppress(Exception):
            connection = xdisplay.Display(self._display())
            try:
                screen = connection.screen()
                return screen.width_in_pixels, screen.height_in_pixels
            finally: connection.close()
        return None

    def describe(self, body):
        where = f' at ({body.x:.0f}, {body.y:.0f})' if body.x is not None and body.y is not None else ''
        return {'click': f'Click{where}', 'double_click': f'Double-click{where}', 'right_click': f'Right-click{where}',
                'middle_click': f'Middle-click{where}', 'move': f'Move the pointer{where}',
                'drag': f'Drag from ({body.x:.0f}, {body.y:.0f}) to ({(body.x2 or 0):.0f}, {(body.y2 or 0):.0f})' if body.x is not None else 'Drag',
                'type': f'Type “{(body.text or "")[:200]}”', 'key': f'Press {body.keys}', 'scroll': f'Scroll {body.direction or "down"}{where}'}[body.action]

    def act(self, body):
        body = body if isinstance(body, Input) else Input(**body)
        if self.screen_scale == 1.0 and body.x is not None:
            real = self._screen_size()
            if real and real[0] > self.max_width: self.screen_scale = real[0] / self.max_width
        self._perform(body)
        return self.describe(body)

    def _perform(self, body):
        xdisplay = self._xlib()
        if xdisplay is None: raise RuntimeError('Computer input needs python-xlib in the gateway environment')
        from Xlib import X, XK
        from Xlib.ext import xtest
        connection = xdisplay.Display(self._display())
        try:
            scale = self.screen_scale or 1.0
            def point(x, y): return int(round(x * scale)), int(round(y * scale))
            def move(x, y):
                px, py = point(x, y)
                screen = connection.screen()
                if not (0 <= px < screen.width_in_pixels and 0 <= py < screen.height_in_pixels): raise ValueError('Coordinates are outside the latest screen')
                xtest.fake_input(connection, X.MotionNotify, x=px, y=py); connection.sync()
            def button(number, times=1):
                for _ in range(times):
                    xtest.fake_input(connection, X.ButtonPress, number); xtest.fake_input(connection, X.ButtonRelease, number); connection.sync()
                    time.sleep(.06)
            def keycode(name):
                aliases = {'enter': 'Return', 'return': 'Return', 'esc': 'Escape', 'escape': 'Escape', 'tab': 'Tab', 'space': 'space',
                           'backspace': 'BackSpace', 'delete': 'Delete', 'del': 'Delete', 'up': 'Up', 'down': 'Down', 'left': 'Left', 'right': 'Right',
                           'home': 'Home', 'end': 'End', 'pageup': 'Prior', 'pagedown': 'Next', 'ctrl': 'Control_L', 'control': 'Control_L',
                           'alt': 'Alt_L', 'shift': 'Shift_L', 'super': 'Super_L', 'win': 'Super_L', 'meta': 'Super_L', 'cmd': 'Super_L'}
                symbol = XK.string_to_keysym(aliases.get(name.lower(), name if len(name) > 1 else name))
                if not symbol and len(name) == 1: symbol = ord(name)
                code = connection.keysym_to_keycode(symbol)
                if not code: raise ValueError(f'Unknown key: {name}')
                return code
            def press(names):
                codes = [keycode(name) for name in names]
                try:
                    for code in codes: xtest.fake_input(connection, X.KeyPress, code)
                finally:
                    for code in reversed(codes): xtest.fake_input(connection, X.KeyRelease, code)
                connection.sync(); time.sleep(.02)
            def type_text(text):
                shift = connection.keysym_to_keycode(XK.string_to_keysym('Shift_L'))
                sequence = []
                for char in text:
                    symbol = XK.string_to_keysym({' ': 'space', '\t': 'Tab', '\n': 'Return'}.get(char, char)) or (0x01000000 + ord(char) if ord(char) > 255 else ord(char))
                    code = connection.keysym_to_keycode(symbol)
                    if not code: raise ValueError(f'Cannot type “{char}” on this keyboard layout')
                    needs_shift = connection.keycode_to_keysym(code, 0) != symbol
                    if needs_shift and connection.keycode_to_keysym(code, 1) != symbol: raise ValueError(f'Cannot type “{char}” on this keyboard layout')
                    sequence.append((code, needs_shift))
                for code, needs_shift in sequence:
                    try:
                        if needs_shift: xtest.fake_input(connection, X.KeyPress, shift)
                        xtest.fake_input(connection, X.KeyPress, code)
                    finally:
                        xtest.fake_input(connection, X.KeyRelease, code)
                        if needs_shift: xtest.fake_input(connection, X.KeyRelease, shift)
                    connection.sync(); time.sleep(.012)
            if body.x is not None and body.y is not None and body.action != 'drag': move(body.x, body.y)
            if body.action == 'click': button(1)
            elif body.action == 'double_click': button(1, 2)
            elif body.action == 'right_click': button(3)
            elif body.action == 'middle_click': button(2)
            elif body.action == 'drag':
                if None in (body.x, body.y, body.x2, body.y2): raise ValueError('Drag needs x, y, x2 and y2')
                move(body.x, body.y); xtest.fake_input(connection, X.ButtonPress, 1); connection.sync(); time.sleep(.1)
                try: move(body.x2, body.y2); time.sleep(.1)
                finally: xtest.fake_input(connection, X.ButtonRelease, 1); connection.sync()
            elif body.action == 'type':
                if not body.text: raise ValueError('Type needs text')
                type_text(body.text)
            elif body.action == 'key':
                if not body.keys: raise ValueError('Key needs keys, like ctrl+l or Return')
                press([part.strip() for part in body.keys.split('+') if part.strip()])
            elif body.action == 'scroll':
                button({'up': 4, 'down': 5, 'left': 6, 'right': 7}[body.direction or 'down'], body.amount)
        finally: connection.close()


TOOLS = [
    {'name': 'screenshot', 'description': 'See the user\'s PC screen now. Coordinates in later actions use this image\'s pixels.',
     'inputSchema': {'type': 'object', 'properties': {}, 'additionalProperties': False}},
    {'name': 'action', 'description': 'Use the mouse or keyboard on the user\'s PC: click, double_click, right_click, middle_click, move, drag (x,y to x2,y2), '
     'type (text), key (keys like ctrl+l, Return, alt+Tab) or scroll (direction, amount). x/y are pixels in the latest screenshot. '
     'Take a screenshot afterwards to check the result. Prefer shell commands when they can do the job.',
     'inputSchema': {'type': 'object', 'properties': {
         'action': {'type': 'string', 'enum': ['click', 'double_click', 'right_click', 'middle_click', 'move', 'drag', 'type', 'key', 'scroll']},
         'x': {'type': 'number'}, 'y': {'type': 'number'}, 'x2': {'type': 'number'}, 'y2': {'type': 'number'},
         'text': {'type': 'string'}, 'keys': {'type': 'string'}, 'direction': {'type': 'string', 'enum': ['up', 'down', 'left', 'right']},
         'amount': {'type': 'integer', 'minimum': 1, 'maximum': 30}}, 'required': ['action'], 'additionalProperties': False}},
]


def serve(computer=None, stdin=sys.stdin, stdout=sys.stdout):
    computer = computer or Computer()
    for line in stdin:
        request = None
        try:
            request = json.loads(line)
            if 'id' not in request: continue
            method, params = request.get('method'), request.get('params') or {}
            if method == 'initialize':
                result = {'protocolVersion': params.get('protocolVersion', '2024-11-05'), 'capabilities': {'tools': {}},
                          'serverInfo': {'name': 'friday-computer', 'version': '1'}}
            elif method == 'ping': result = {}
            elif method == 'tools/list': result = {'tools': TOOLS}
            elif method == 'tools/call':
                name, arguments = params.get('name'), params.get('arguments') or {}
                try:
                    if name == 'screenshot':
                        image, width, height = computer.screenshot()
                        result = {'content': [{'type': 'image', 'data': base64.b64encode(image).decode(), 'mimeType': 'image/png'},
                                              {'type': 'text', 'text': f'Screenshot {width}×{height}.'}]}
                    elif name == 'action':
                        result = {'content': [{'type': 'text', 'text': 'Done: ' + computer.act(arguments)}]}
                    else: raise ValueError('Unknown tool')
                except Exception as error:
                    result = {'content': [{'type': 'text', 'text': f'{type(error).__name__}: {error}'}], 'isError': True}
            else: raise ValueError('Unsupported method')
            response = {'jsonrpc': '2.0', 'id': request['id'], 'result': result}
        except Exception as error:
            response = {'jsonrpc': '2.0', 'id': (request or {}).get('id'), 'error': {'code': -32603, 'message': str(error)[:200]}}
        stdout.write(json.dumps(response) + '\n'); stdout.flush()


if __name__ == '__main__': serve()

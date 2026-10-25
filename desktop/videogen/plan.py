"""Choose a video's size from its duration and a speed-to-quality level, within what the GPUs can render.

LTX works on 32x32-pixel blocks and on every eighth frame, so a video's GPU workload is roughly
(width/32) * (height/32) * ((frames - 1) / 8 + 1) "blocks". One number limits both resolution and duration:
on two P100s, 1600x896 for 4 s (18,200 blocks) and 640x352 for 26 s (17,380) rendered fully, while
1664x928 for 4 s (19,604) and 640x352 for 28 s (18,700) ran out of memory.

Each placement (one card, keeping chat; or both cards, pausing chat) has its own block limit and measured
render times. Fast is the smallest acceptable size or whatever renders within the fast target time; quality is the
largest size any placement fits; the levels between step evenly in blocks on a log scale.
"""
FPS = 24
LEVELS = 5
ASPECTS = {'16:9': (16, 9), '9:16': (9, 16), '1:1': (1, 1), '4:3': (4, 3), '3:4': (3, 4)}

# Measured 2026-10-23..25 with the LTX-2.5 distilled Q3_K_M transformer, eight steps. Times are whole jobs:
# loading, text encoding, sampling and decoding (not the chat model's reload afterwards).
DEFAULT_PLACEMENTS = [
    # The image card alone, with chat left running: weights come from disk each job (RAM is mostly Strata's).
    # 640x352 and 832x480 for 4 s rendered fully; 640x352 for 10 s (6,820) sampled but ran out of memory decoding.
    {'name': 'single', 'pauses_chat': False, 'max_blocks': 5070, 'timing': [[2860, 410], [5070, 626]]},
    # Both cards, the transformer split by layer through a local RPC worker; chat is stopped.
    {'name': 'dual', 'pauses_chat': True, 'max_blocks': 18200,
     'timing': [[660, 142], [17380, 2028], [18200, 2436]]},
]
DEFAULTS = {'min_seconds': 2, 'max_seconds': 26, 'min_pixels': 640 * 352, 'fast_seconds': 180,
            'chat_restore_seconds': 240, 'placements': DEFAULT_PLACEMENTS}


def frames_for(seconds):
    """Frame count for a duration at 24 fps: LTX needs 8n+1 frames."""
    return 8 * max(1, round(FPS * seconds / 8)) + 1


def blocks(width, height, frames):
    return (width // 32) * (height // 32) * ((frames - 1) // 8 + 1)


def sizes(aspect):
    """Every size for a shape on the 32-pixel grid, short side 256-1088, smallest first."""
    aw, ah = ASPECTS[aspect]
    out = set()
    for short in range(256, 1089, 32):
        long = max(short, round(short * max(aw, ah) / min(aw, ah) / 32) * 32)
        out.add((long, short) if aw >= ah else (short, long))
    return sorted(out, key=lambda s: (s[0] * s[1], s[0]))


def estimate(placement, work):
    """Seconds from the measured points: straight lines between them, extended past the ends along the nearest pair
    (or in proportion to the work when there is only one)."""
    points = sorted(placement['timing'])
    if len(points) == 1:
        return points[0][1] * work / points[0][0]
    for (b0, s0), (b1, s1) in zip(points, points[1:]):
        if work <= b1 or (b1, s1) == points[-1]:
            if work < points[0][0]:
                (b0, s0), (b1, s1) = points[0], points[1]
            return max(1.0, s0 + (s1 - s0) * (work - b0) / (b1 - b0))


class Planner:
    def __init__(self, config=None):
        self.config = {**DEFAULTS, **(config or {})}
        self.placements = self.config['placements']
        if not self.placements or any(p['max_blocks'] <= 0 or not p['timing'] for p in self.placements):
            raise ValueError('Video placements need a block limit and at least one measured time')

    def placement(self, work):
        """The first placement that fits, in configured order: the one that keeps chat comes first."""
        return next((p for p in self.placements if work <= p['max_blocks']), None)

    def option(self, width, height, frames):
        work = blocks(width, height, frames)
        p = self.placement(work)
        if p is None:
            return None
        seconds = round(estimate(p, work))
        return {'width': width, 'height': height, 'frames': frames, 'blocks': work, 'placement': p['name'],
                'pauses_chat': p['pauses_chat'], 'estimate_seconds': seconds,
                'chat_paused_seconds': seconds + self.config['chat_restore_seconds'] if p['pauses_chat'] else 0}

    def levels(self, seconds, aspect):
        """The quality levels for a duration and shape, fastest first; empty if even the smallest size won't fit."""
        if aspect not in ASPECTS:
            raise ValueError('Unknown aspect ratio')
        if not self.config['min_seconds'] <= seconds <= self.config['max_seconds']:
            return []
        frames = frames_for(seconds)
        fitting = [o for o in (self.option(w, h, frames) for w, h in sizes(aspect)
                               if w * h >= self.config['min_pixels'] * 0.95) if o]
        if not fitting:
            return []
        floor = fitting[0]
        budget = max(self.config['fast_seconds'], floor['estimate_seconds'])
        fast = max((o for o in fitting if o['estimate_seconds'] <= budget), key=lambda o: o['blocks'], default=floor)
        best = fitting[-1]
        out = []
        for i in range(LEVELS):
            target = fast['blocks'] * (best['blocks'] / fast['blocks']) ** (i / (LEVELS - 1))
            out.append(max((o for o in fitting if fast['blocks'] <= o['blocks'] <= target + 1e-6), key=lambda o: o['blocks']))
        return out

    def resolve(self, seconds, aspect, quality):
        levels = self.levels(seconds, aspect)
        if not levels:
            raise ValueError(f'No size fits {seconds:g} s; use {self.config["min_seconds"]}-{self.config["max_seconds"]} s')
        if not 0 <= quality < len(levels):
            raise ValueError(f'Quality must be 0-{len(levels) - 1}')
        return levels[quality]

    def table(self):
        """Every duration's levels for every shape, for clients to show sizes and times without asking."""
        durations = list(range(self.config['min_seconds'], self.config['max_seconds'] + 1))
        return {'durations': durations, 'aspects': list(ASPECTS), 'levels': LEVELS,
                'plans': {a: {str(d): self.levels(d, a) for d in durations} for a in ASPECTS}}


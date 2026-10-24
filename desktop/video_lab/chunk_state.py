"""Inspect the private runtime's lossless audio/video latent handoff files."""
from array import array
import math
from pathlib import Path
import struct
import sys


def read_state(path):
    if sys.byteorder != 'little':
        raise RuntimeError('The experimental state format requires a little-endian host')
    path = Path(path)
    with path.open('rb') as stream:
        header = stream.read(32)
        if len(header) != 32:
            raise ValueError('Truncated carry header')
        magic, version, width, height, frames, channels, audio_frames, fps = struct.unpack('<8I', header)
        if (magic, version) != (0x46524156, 1):
            raise ValueError('Unsupported carry format')
        if not (0 < width <= 256 and 0 < height <= 256 and 0 < frames <= 4096 and
                channels == 128 and audio_frames > 0 and 0 < fps <= 240):
            raise ValueError('Unsupported carry dimensions')
        video_count = width * height * frames * channels
        audio_count = audio_frames * 128
        count = video_count + audio_count
        if count > 1 << 26 or path.stat().st_size != 32 + count * 4:
            raise ValueError('Carry payload size mismatch')
        values = array('f')
        values.fromfile(stream, count)
    if not all(math.isfinite(value) for value in values):
        raise ValueError('Non-finite carry payload')
    return dict(width=width, height=height, frames=frames, channels=channels,
                audio_frames=audio_frames, fps=fps, video=values[:video_count], audio=values[video_count:])


def audit_carry(previous_path, current_path, carry_frames):
    previous, current = read_state(previous_path), read_state(current_path)
    for key in ['width', 'height', 'channels', 'fps']:
        if previous[key] != current[key]:
            raise ValueError('Carry mismatch: ' + key)
    if carry_frames < 9 or (carry_frames - 1) % 8:
        raise ValueError('Carry frame count must be 8k+1, at least 9')
    video_frames = (carry_frames - 1) // 8 + 1
    audio_frames = math.ceil(carry_frames * 25 / current['fps'])
    if video_frames >= current['frames'] or video_frames > previous['frames'] or \
            audio_frames >= current['audio_frames'] or audio_frames > previous['audio_frames']:
        raise ValueError('Carry exceeds window')
    plane = current['width'] * current['height']
    errors = {}
    for kind, channels, block, carried, key in [('video', 128, plane, video_frames, 'frames'),
                                                ('audio', 8, 16, audio_frames, 'audio_frames')]:
        maximum = 0.0
        for channel in range(channels):
            src = block * (channel * previous[key] + previous[key] - carried)
            dst = block * channel * current[key]
            maximum = max(maximum, max(abs(a - b) for a, b in zip(
                previous[kind][src:src + block * carried], current[kind][dst:dst + block * carried])))
        errors[kind + '_max_abs_error'] = maximum
    if max(errors.values()) > 1e-5:
        raise RuntimeError('Carried AV context changed during denoising: ' + str(errors))
    return dict(video_latent_frames=video_frames, audio_latent_frames=audio_frames, **errors)

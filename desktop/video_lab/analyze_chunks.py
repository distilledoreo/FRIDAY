"""Decode a completed chunk experiment and inspect frames around its joins."""
import argparse
import json
from pathlib import Path
import subprocess


def analyze(directory):
    import cv2
    import numpy as np

    report = json.loads((directory / 'results.json').read_text())
    if report['status'] != 'complete':
        raise ValueError('Experiment has not completed')
    video = Path(report['output'])
    capture = cv2.VideoCapture(str(video))
    differences = []
    flow = []
    previous = None
    frames = 0
    while True:
        ok, frame = capture.read()
        if not ok:
            break
        gray = cv2.cvtColor(cv2.resize(frame, (320, 176)), cv2.COLOR_BGR2GRAY)
        if previous is not None:
            differences.append(float(np.mean(cv2.absdiff(previous, gray))))
            vectors = cv2.calcOpticalFlowFarneback(previous, gray, None, .5, 3, 15, 3, 5, 1.2, 0)
            flow.append(float(np.mean(np.linalg.norm(vectors, axis=2))))
        previous = gray
        frames += 1
    capture.release()
    if frames != report['expected_frames']:
        raise RuntimeError('Full video decode did not produce the expected frame count')
    config = report['config']
    stride = config['window_frames'] - config['carry_frames']
    boundaries = [config['window_frames'] + i * stride for i in range(config['windows'] - 1)]
    metrics = []
    for boundary in boundaries:
        local = list(range(max(0, boundary - 13), min(len(differences), boundary + 12)))
        reference = [i for i in local if i != boundary - 1]
        metrics.append(dict(first_new_frame=boundary, time_s=boundary / 24,
                            frame_difference=differences[boundary - 1],
                            neighborhood_median_difference=float(np.median([differences[i] for i in reference])),
                            optical_flow_px=flow[boundary - 1],
                            neighborhood_median_flow_px=float(np.median([flow[i] for i in reference]))))
    selected = [i for b in boundaries for i in [b - 2, b - 1, b, b + 1, b + 2]]
    expression = '+'.join(f'eq(n,{i})' for i in selected)
    contact = directory / 'join-contact.png'
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-threads', '2', '-i', str(video), '-vf',
                    f"select='{expression}',scale=480:-1,tile=5x{len(boundaries)}", '-frames:v', '1', str(contact)], check=True)
    overview = directory / 'overview.png'
    step = max(1, frames // 15)
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-threads', '2', '-i', str(video), '-vf',
                    f"select='not(mod(n,{step}))',scale=320:-1,tile=5x3", '-frames:v', '1', str(overview)], check=True)
    audio = subprocess.run(['ffmpeg', '-hide_banner', '-threads', '2', '-i', str(video), '-af',
                            'astats=reset=0', '-f', 'null', '-'], capture_output=True, text=True, check=True)
    levels = [line.strip() for line in audio.stderr.splitlines() if 'RMS level dB' in line or 'Peak level dB' in line][-2:]
    pcm = subprocess.check_output(['ffmpeg', '-v', 'error', '-threads', '2', '-i', str(video), '-vn',
                                   '-ar', '48000', '-ac', '2', '-f', 'f32le', '-'])
    waveform = np.frombuffer(pcm, dtype='<f4').reshape(-1, 2)
    for boundary in metrics:
        index = boundary['first_new_frame'] * 2000
        changes = np.max(np.abs(np.diff(waveform[index - 2400:index + 2400], axis=0)), axis=1)
        boundary['audio_join_step'] = float(np.max(np.abs(waveform[index] - waveform[index - 1])))
        boundary['audio_neighborhood_p99_step'] = float(np.quantile(changes, .99))
    result = dict(decoded_frames=frames, boundaries=metrics, selected_frames=selected,
                  audio_levels=levels, contact=str(contact), overview=str(overview),
                  decoded_audio_samples_per_channel=len(waveform), audio_blend_ms=report.get('audio_blend_ms', 0),
                  note='Frame difference and optical flow describe joins; they do not prove perceptual continuity.')
    (directory / 'qa.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    print(json.dumps(analyze(parser.parse_args().directory), indent=2))


if __name__ == '__main__':
    main()

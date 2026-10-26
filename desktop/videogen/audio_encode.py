"""Encode a soundtrack into LTX audio latents for the renderer's FRIDAY_LTX_AUDIO_IN, on the CPU.

The renderer has only the audio decoder, so this uses ComfyUI's PyTorch LTX audio VAE (run it with a Python that has
ComfyUI and torch). ffmpeg reads any audio or video file. Output: "FRAU", version 1, 8 channels, 16 bins, frames, then
[8][frames][16] float32 latents, normalized as the transformer expects.

    python audio_encode.py --comfyui /path/to/ComfyUI --vae ltx-2.5-audio-vae-bf16.safetensors \\
        --input song.m4a --seconds 10 --output song.frau
"""
import argparse
import json
import os
from pathlib import Path
import struct
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--comfyui', type=Path, required=True, help='ComfyUI source folder (the one with comfy/)')
    parser.add_argument('--vae', type=Path, required=True)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--seconds', type=float, required=True, help='Encode at most this much from the start')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    os.environ['CUDA_VISIBLE_DEVICES'] = ''  # The GPUs belong to the renderer and the chat model.
    sys.path.insert(0, str(args.comfyui))
    import torch
    from safetensors import safe_open
    from comfy.cli_args import args as comfy_args
    comfy_args.cpu = True  # Read before ComfyUI's model manager initializes a device.
    from comfy.ldm.lightricks.vae.audio_vae import AudioVAE

    with safe_open(str(args.vae), 'pt') as f:
        metadata = f.metadata() or {}
        state = {key.replace('audio_vae.', 'autoencoder.', 1): f.get_tensor(key).float() for key in f.keys()}
    vae = AudioVAE(metadata).eval()
    missing, unexpected = vae.load_state_dict(state, strict=False)
    if [k for k in missing if k.startswith('autoencoder.')]:
        raise SystemExit('Audio VAE weights are incomplete: ' + ', '.join(missing[:5]))
    channels = vae.autoencoder.encoder.in_channels
    rate = vae.sample_rate
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(args.input), '-t', f'{args.seconds:.3f}', '-vn',
                          '-ac', str(channels), '-ar', str(rate), '-f', 'f32le', '-'], capture_output=True, check=True).stdout
    if not raw:
        raise SystemExit('The input has no audio')
    waveform = torch.frombuffer(bytearray(raw), dtype=torch.float32).reshape(-1, channels).T.unsqueeze(0)
    with torch.no_grad():
        latent = vae.encode(waveform, rate)  # [1, 8, frames, 16]
    if tuple(latent.shape[1:2]) != (8,) or latent.shape[3] != 16:
        raise SystemExit(f'Unexpected audio latent shape {tuple(latent.shape)}')
    frames = latent.shape[2]
    data = latent[0].contiguous().float().numpy().tobytes()
    with open(args.output, 'wb') as out:
        out.write(struct.pack('<5I', 0x55415246, 1, 8, 16, frames))
        out.write(data)
    print(json.dumps({'frames': frames, 'seconds': round(waveform.shape[2] / rate, 3), 'sample_rate': rate}))


if __name__ == '__main__':
    main()

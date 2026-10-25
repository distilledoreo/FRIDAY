"""Write a private video config: the LTX renderer, its second-card RPC worker, verified model files and both GPUs."""
import argparse
import hashlib
import json
from pathlib import Path

from .manager import atomic


def verified(path, role=None, previous=None):
    path = path.resolve(strict=True)
    stat = path.stat()
    if previous and previous.get('verified_size') == stat.st_size and previous.get('verified_mtime_ns') == stat.st_mtime_ns:
        return {**previous, 'path': str(path), **({'role': role} if role else {})}
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(8 * 1024 * 1024):
            digest.update(block)
    stat = path.stat()
    return {**({'role': role} if role else {}), 'path': str(path), 'sha256': digest.hexdigest(),
            'verified_size': stat.st_size, 'verified_mtime_ns': stat.st_mtime_ns}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', required=True, type=Path, help='LTX sd-cli with the resident-placement patch')
    parser.add_argument('--rpc-worker', required=True, type=Path, help='GGML RPC server linked to the same GGML')
    parser.add_argument('--transformer', required=True, type=Path)
    parser.add_argument('--text-encoder', required=True, type=Path)
    parser.add_argument('--video-vae', required=True, type=Path)
    parser.add_argument('--audio-vae', required=True, type=Path)
    parser.add_argument('--image-gpu', required=True, help='UUID of the card shared with images (one-card videos)')
    parser.add_argument('--chat-gpu', required=True, help='UUID of the chat model\'s card (borrowed for two-card videos)')
    parser.add_argument('--asr-unit', default='friday-voice-asr.service', help='GPU speech service on the image card ("" for none)')
    parser.add_argument('--lora-dir', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    for gpu in (args.image_gpu, args.chat_gpu):
        if not gpu.startswith('GPU-') or ',' in gpu:
            parser.error('Give each GPU as one physical GPU-... UUID')
    if args.image_gpu == args.chat_gpu:
        parser.error('The image and chat cards must differ')
    previous = json.loads(args.output.read_text()) if args.output.is_file() else {}
    known = {item['path']: item for item in previous.get('files', []) + [previous.get('binary', {}), previous.get('rpc_worker', {})] if item}
    files = [verified(getattr(args, role), role, known.get(str(getattr(args, role).resolve())))
             for role in ('transformer', 'text_encoder', 'video_vae', 'audio_vae')]
    config = {**previous, 'binary': verified(args.binary, previous=known.get(str(args.binary.resolve()))),
              'rpc_worker': verified(args.rpc_worker, previous=known.get(str(args.rpc_worker.resolve()))),
              'files': files, 'gpus': {'image': args.image_gpu, 'chat': args.chat_gpu}, 'asr_unit': args.asr_unit or None}
    config.setdefault('inputs', [])
    if args.lora_dir:
        config['lora_dir'] = str(args.lora_dir.resolve(strict=True))
    atomic(args.output, config)
    args.output.chmod(0o600)
    print('Verified video configuration written: ' + str(args.output))


if __name__ == '__main__':
    main()

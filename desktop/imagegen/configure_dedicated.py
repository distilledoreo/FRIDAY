"""Pin a verified private image profile to the spare GPU, preserving its sampling setup."""
import argparse
import hashlib
import json
import re
from pathlib import Path

from .manager import atomic


def verified(path, previous=None):
    path = path.resolve(strict=True)
    stat = path.stat()
    if previous and previous.get('sha256') and previous.get('verified_size') == stat.st_size and \
            previous.get('verified_mtime_ns') == stat.st_mtime_ns:
        return {**previous, 'path': str(path)}
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(8 * 1024 * 1024):
            digest.update(block)
    stat = path.stat()
    return {'path': str(path), 'sha256': digest.hexdigest(), 'bytes': stat.st_size,
            'verified_size': stat.st_size, 'verified_mtime_ns': stat.st_mtime_ns}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-config', required=True, type=Path)
    parser.add_argument('--binary', required=True, type=Path)
    parser.add_argument('--transformer', required=True, type=Path)
    parser.add_argument('--encoder', required=True, type=Path)
    parser.add_argument('--vision', required=True, type=Path)
    parser.add_argument('--vae', required=True, type=Path)
    parser.add_argument('--gpu-uuid', required=True)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--encoder-device', default='cpu',
                        help='cpu, or a Vulkan card such as vulkan0 (needs a build with SD_VULKAN=ON)')
    parser.add_argument('--encoder-vram', type=int, default=7, help='GiB the encoder may use on a Vulkan card')
    args = parser.parse_args()
    if not args.gpu_uuid.startswith('GPU-') or ',' in args.gpu_uuid:
        parser.error('Provide exactly one physical GPU UUID')
    if args.encoder_device != 'cpu' and not re.fullmatch(r'vulkan\d+', args.encoder_device):
        parser.error('--encoder-device must be cpu or vulkanN')
    config = json.loads(args.base_config.read_text())
    original_command = config['command']
    previous = {str(Path(item['path']).resolve()): item for item in config['files']}
    binary = verified(args.binary)
    models = {name: verified(getattr(args, name), previous.get(str(getattr(args, name).resolve())))
              for name in ('transformer', 'encoder', 'vision', 'vae')}
    sample = config.get('sample_params', {})
    placement = f'diffusion=cuda0,te={args.encoder_device},vae=cuda0'
    budget = 'cuda0=10' + ('' if args.encoder_device == 'cpu' else f',{args.encoder_device}={args.encoder_vram}')
    def option(flag, default):
        return original_command[original_command.index(flag) + 1] if flag in original_command else default
    config.update(placement='dedicated', environment={'CUDA_DEVICE_ORDER': 'PCI_BUS_ID',
                  'CUDA_VISIBLE_DEVICES': args.gpu_uuid},
                  files=list(models.values()), binary_sha256=binary['sha256'],
                  binary_size=binary['bytes'], binary_mtime_ns=binary['verified_mtime_ns'])
    config['command'] = [binary['path'], '--diffusion-model', models['transformer']['path'],
                         '--llm', models['encoder']['path'], '--llm_vision', models['vision']['path'],
                         '--vae', models['vae']['path'], '--mmap', '--eager-load',
                         '--backend', placement, '--params-backend', placement,
                         '--max-vram', budget, '--vae-tiling', '--vae-tile-size', '512', '--diffusion-fa',
                         '--listen-ip', '127.0.0.1', '--listen-port', '8192', '--threads', '4',
                         '--cfg-scale', str(sample.get('guidance', {}).get('txt_cfg', option('--cfg-scale', '1'))),
                         '--sampling-method', sample.get('sample_method', option('--sampling-method', 'euler')),
                         '--steps', str(sample.get('sample_steps', option('--steps', '8'))), '--log-level', 'info']
    if sample.get('custom_sigmas'):
        config['command'] += ['--sigmas', ','.join(map(str, sample['custom_sigmas']))]
    if config.get('lora'):
        directory = Path(option('--lora-model-dir', '.')).resolve(strict=True)
        config['command'] += ['--lora-model-dir', str(directory)]
        for adapter in config['lora']:
            path = (directory / adapter['path']).resolve(strict=True)
            config['files'].append(verified(path, previous.get(str(path))))
    atomic(args.output, config)
    args.output.chmod(0o600)
    print('Verified dedicated-GPU configuration written: ' + str(args.output))


if __name__ == '__main__':
    main()

"""Build FRIDAY's LTX runtime: resident placement, AV carry, and keyframe/source-clip/audio conditioning.

It applies build_chunk_variant's patches (their per-token audio timesteps let frozen audio stay frozen) and hooks
native/ltx_conditioning.h into the LTX branch, then rebuilds only stable-diffusion.cpp against the planner variant's
archive, as the other variants do.
"""
import argparse
from pathlib import Path
import shutil

from . import build_chunk_variant as chunk


def patch_source(source):
    source = chunk.patch_source(source)
    anchor = 'static sd::Tensor<float> make_ltxav_empty_audio_latent(int audio_length) {'
    source = chunk.replace_once(source, anchor, '#include "ltx_conditioning.h"\n\n' + anchor)
    old = '''    if (sd_version_is_ltxav(sd_ctx->sd->version)) {
        if (sd_vid_gen_params->control_frames_size > 0) {
            LOG_ERROR("LTXAV control_frames are not implemented");
            return std::nullopt;
        }
'''
    new = '''    if (sd_version_is_ltxav(sd_ctx->sd->version) && friday_ltx_wants_conditioning(sd_vid_gen_params)) {
        if (!friday_ltx_condition_video(sd_ctx, sd_vid_gen_params, request, &latents, start_image, end_image)) {
            return std::nullopt;
        }
    } else if (sd_version_is_ltxav(sd_ctx->sd->version)) {
        if (sd_vid_gen_params->control_frames_size > 0) {
            LOG_ERROR("LTXAV control_frames are not implemented");
            return std::nullopt;
        }
'''
    source = chunk.replace_once(source, old, new)
    old = '''        latents.init_latent = pack_ltxav_audio_and_video_latents(latents.init_latent, latents.audio_latent);
    }

    return latents;
}'''
    new = '''        latents.init_latent = pack_ltxav_audio_and_video_latents(latents.init_latent, latents.audio_latent);
        if (sd_version_is_ltxav(sd_ctx->sd->version) && !friday_ltx_import_audio(&latents, sd_ctx->sd->get_latent_channel())) {
            return std::nullopt;
        }
    }

    return latents;
}'''
    return chunk.replace_once(source, old, new)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--planner-variant', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    shutil.copy2(Path(__file__).resolve().parent / 'native' / 'ltx_conditioning.h', args.output / 'ltx_conditioning.h')
    chunk.build(args, patch_source)
    print('Built FRIDAY conditioning runtime:', args.output / 'sd-cli')


if __name__ == '__main__':
    main()

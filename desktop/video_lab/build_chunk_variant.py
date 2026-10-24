"""Build an isolated AV-carry runtime on top of the resident-placement variant."""
import argparse
from pathlib import Path
import shlex
import shutil
import subprocess


def replace_once(source, old, new):
    if source.count(old) != 1:
        raise RuntimeError('Runtime integration anchor changed: ' + old[:100])
    return source.replace(old, new, 1)


def patch_source(source):
    source = replace_once(source, '#include "core/ggml_extend.hpp"',
                          '#include "core/ggml_extend.hpp"\n#include "ltx_chunk_carry.h"')
    anchor = 'static int get_minimax_h3_num_audio_latents(int frames, int fps) {'
    source = replace_once(source, anchor, '#include "ltx_chunk_runtime.h"\n\n' + anchor)
    old = 'audio_timesteps_tensor = sd::Tensor<float>({static_cast<int64_t>(base_timesteps_vec.size())}, base_timesteps_vec);'
    new = '''auto audio_steps = friday_ltx::audio_timesteps(
                    denoise_mask.data(), denoise_mask.numel(),
                    static_cast<uint64_t>(sampling_init_latent.shape()[0]) * sampling_init_latent.shape()[1] *
                        sampling_init_latent.shape()[2] * get_latent_channel(),
                    audio_length, base_timesteps_vec[0]);
                audio_timesteps_tensor = sd::Tensor<float>({static_cast<int64_t>(audio_steps.size())}, audio_steps);'''
    source = replace_once(source, old, new)
    old = '    sd::Tensor<float> x_t   = latents.init_latent;\n    sd::Tensor<float> noise = sd::Tensor<float>::randn_like(x_t, sd_ctx->sd->rng);'
    new = '''    if ((std::getenv("FRIDAY_LTX_CARRY_IN") || std::getenv("FRIDAY_LTX_CARRY_OUT")) &&
        (!sd_version_is_ltxav(sd_ctx->sd->version) || plan.high_noise_sample_steps > 0 ||
         latents.video_conditioning_frame_count > 0 || latents.ref_image_num > 0)) {
        LOG_ERROR("LTX chunk carry requires a plain LTX AV generation");
        return false;
    }
    if (!friday_ltx_import_carry(&latents.init_latent, &latents.denoise_mask,
                               latents.audio_length, request.fps, sd_ctx->sd->get_latent_channel(), "stage1")) {
        return false;
    }
''' + old
    source = replace_once(source, old, new)
    old = '    LOG_INFO("sampling completed, taking %.2fs", (sampling_end - sampling_start) * 1.0f / 1000);\n\n    if (latent_upscale_enabled) {'
    new = '''    LOG_INFO("sampling completed, taking %.2fs", (sampling_end - sampling_start) * 1.0f / 1000);
    if (!friday_ltx_export_carry(final_latent, latents.audio_length, request.fps,
                               sd_ctx->sd->get_latent_channel(), "stage1")) {
        return false;
    }

    if (latent_upscale_enabled) {'''
    source = replace_once(source, old, new)
    old = '        noise = sd::Tensor<float>::randn_like(x_t, sd_ctx->sd->rng);\n\n        W                                   = hires_request.width'
    new = '''        if (!friday_ltx_import_carry(&x_t, &hires_denoise_mask, latents.audio_length,
                                   request.fps, sd_ctx->sd->get_latent_channel(), "stage2")) {
            return false;
        }
        noise = sd::Tensor<float>::randn_like(x_t, sd_ctx->sd->rng);

        W                                   = hires_request.width'''
    source = replace_once(source, old, new)
    old = '''        LOG_INFO("sampling(latent upscale) completed, taking %.2fs",
                 (sampling_end - sampling_start) * 1.0f / 1000);'''
    new = old + '''
        if (!friday_ltx_export_carry(final_latent, latents.audio_length, request.fps,
                                   sd_ctx->sd->get_latent_channel(), "stage2")) {
            return false;
        }'''
    return replace_once(source, old, new)


def patch_model(source):
    old = '''            ggml_tensor* v_prompt_timestep_mod = nullptr;
            ggml_tensor* a_prompt_timestep_mod = nullptr;'''
    new = '''            const bool has_audio_prefix = effective_audio_timestep->ne[0] > 1;
            auto global_audio_timestep = effective_audio_timestep;
            if (has_audio_prefix) {
                // The final audio token is generated, so its timestep is the window's global sigma.
                global_audio_timestep = ggml_view_1d(ctx->ggml_ctx, effective_audio_timestep, 1,
                    (effective_audio_timestep->ne[0] - 1) * effective_audio_timestep->nb[0]);
            }
            auto prompt_timestep_scaled = has_audio_prefix
                ? ggml_ext_scale(ctx->ggml_ctx, global_audio_timestep, config.timestep_scale_multiplier)
                : a_timestep_scaled;
''' + old
    source = replace_once(source, old, new)
    for name in ['prompt_adaln_single', 'audio_prompt_adaln_single']:
        source = replace_once(source, '= ' + name + '->forward(ctx, a_timestep_scaled).first;',
                              '= ' + name + '->forward(ctx, prompt_timestep_scaled).first;')
    source = replace_once(source,
        'auto av_ca_video_timestep = repeat_scalar_timestep_like(ctx, effective_audio_timestep, timestep);',
        'auto av_ca_video_timestep = repeat_scalar_timestep_like(ctx, global_audio_timestep, timestep);')
    source = replace_once(source,
        'blocks["av_ca_video_scale_shift_adaln_single"])->forward(ctx, av_ca_video_timestep).first;',
        'blocks["av_ca_video_scale_shift_adaln_single"])->forward(ctx, has_audio_prefix ? timestep : av_ca_video_timestep).first;')
    source = replace_once(source,
        '->forward(ctx, ggml_ext_scale(ctx->ggml_ctx, av_ca_audio_timestep, av_ca_factor))',
        '->forward(ctx, ggml_ext_scale(ctx->ggml_ctx, has_audio_prefix ? global_audio_timestep : av_ca_audio_timestep, av_ca_factor))')
    return source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--planner-variant', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    native = Path(__file__).resolve().parent / 'native'
    for header in native.glob('ltx_chunk_*.h'):
        shutil.copy2(header, args.output / header.name)
    original = args.source / 'src/stable-diffusion.cpp'
    source = args.output / original.name
    source.write_text(patch_source(original.read_text()))
    model = args.output / 'model/diffusion/ltxv.hpp'
    model.parent.mkdir(parents=True, exist_ok=True)
    model.write_text(patch_model((args.source / 'src/model/diffusion/ltxv.hpp').read_text()))
    lines = subprocess.check_output(['ninja', '-C', str(args.build), '-t', 'commands', 'sd-cli'], text=True).splitlines()
    command = shlex.split(next(line for line in lines if ' -c ' in line and line.endswith('/src/stable-diffusion.cpp')))
    obj = args.output / 'stable-diffusion.cpp.o'
    command[command.index('-c') + 1] = str(source)
    command[command.index('-o') + 1] = str(obj)
    for flag, value in [('-MF', str(obj) + '.d'), ('-MT', str(obj))]:
        if flag in command:
            command[command.index(flag) + 1] = value
    command += ['-I', str(args.source / 'src')]
    subprocess.run(command, cwd=args.build, check=True)
    archive = args.output / 'libstable-diffusion.a'
    shutil.copy2(args.planner_variant / archive.name, archive)
    if subprocess.check_output(['ar', 't', str(archive)], text=True).splitlines().count(obj.name) != 1:
        raise RuntimeError('Unexpected runtime archive layout')
    subprocess.run(['ar', 'rcs', str(archive), str(obj)], check=True)
    link = shlex.split(lines[-1])
    link = link[next(i for i, value in enumerate(link) if value.endswith(('/c++', '/g++'))):]
    if '&&' in link:
        link = link[:link.index('&&')]
    link[link.index('-o') + 1] = str(args.output / 'sd-cli')
    link[link.index('libstable-diffusion.a')] = str(archive)
    subprocess.run(link, cwd=args.build, check=True)
    print('Built isolated AV-carry runtime:', args.output / 'sd-cli')


if __name__ == '__main__':
    main()

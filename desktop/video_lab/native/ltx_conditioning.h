#ifndef __FRIDAY_LTX_CONDITIONING_H__
#define __FRIDAY_LTX_CONDITIONING_H__

// FRIDAY's extra LTX AV conditioning, included into stable-diffusion.cpp after its LTX helpers:
//
// - Keyframes: images passed as --ref-image, placed at the pixel frames listed in FRIDAY_LTX_KEYFRAME_FRAMES
//   ("24,60"). Each becomes one appended keyframe latent at its own time position, the mechanism LTX 2.5 uses for an
//   end image (which joins the list at the last frame).
// - Source clip: frames passed with --control-video are encoded and frozen as the video's opening frames, so the
//   generated part continues them. The clip is trimmed to 8k+1 frames and must leave at least one latent to generate.
// - Audio: FRIDAY_LTX_AUDIO_IN names a latent file from the LTX audio encoder (header "FRAU", version 1, then
//   [8 channels][frames][16 bins] floats). Its frames are frozen from the start of the soundtrack; the rest is
//   generated. Frozen audio tokens get zero timesteps through the chunk-carry runtime's per-token audio timesteps.
//
// Every conditioned latent uses the request's strength (1 = frozen), like the stock start and end images.

#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

static std::vector<int> friday_ltx_keyframe_frames(int images, int frames) {
    std::vector<int> out;
    const char* list = std::getenv("FRIDAY_LTX_KEYFRAME_FRAMES");
    if (list == nullptr || *list == '\0') {
        if (images > 0) {
            throw std::runtime_error("--ref-image keyframes need FRIDAY_LTX_KEYFRAME_FRAMES");
        }
        return out;
    }
    std::stringstream stream(list);
    std::string item;
    while (std::getline(stream, item, ',')) {
        int frame = std::stoi(item);
        if (frame < 1 || frame >= frames) {
            throw std::runtime_error("Keyframes must fall after the first frame and within the video");
        }
        out.push_back(frame);
    }
    if (static_cast<int>(out.size()) != images) {
        throw std::runtime_error("FRIDAY_LTX_KEYFRAME_FRAMES must list one frame per --ref-image");
    }
    return out;
}

static bool friday_ltx_wants_conditioning(const sd_vid_gen_params_t* params) {
    const char* keyframes = std::getenv("FRIDAY_LTX_KEYFRAME_FRAMES");
    return params->control_frames_size > 0 || params->ref_images_count > 0 || (keyframes != nullptr && *keyframes != '\0');
}

// Target latents first, then one latent per keyframe at its own time, as build_ltxv_video_positions does for one.
static sd::Tensor<float> friday_ltx_positions(int64_t width, int64_t height, int64_t target_latent_frames,
                                              const std::vector<int>& keyframes, int fps, int spatial_scale) {
    constexpr int temporal_scale = 8;
    int64_t total_tokens = width * height * (target_latent_frames + static_cast<int64_t>(keyframes.size()));
    sd::Tensor<float> positions({2, 3, total_tokens, 1});
    int64_t token = 0;
    auto add_frame = [&](float t_start, float t_end) {
        for (int64_t h = 0; h < height; h++) {
            for (int64_t w = 0; w < width; w++) {
                set_ltxv_video_position(&positions, token++, t_start, t_end,
                                        static_cast<float>(h * spatial_scale), static_cast<float>((h + 1) * spatial_scale),
                                        static_cast<float>(w * spatial_scale), static_cast<float>((w + 1) * spatial_scale));
            }
        }
    };
    for (int64_t t = 0; t < target_latent_frames; t++) {
        add_frame(ltxv_latent_corner_to_pixel_frame(t, temporal_scale, true) / static_cast<float>(fps),
                  ltxv_latent_corner_to_pixel_frame(t + 1, temporal_scale, true) / static_cast<float>(fps));
    }
    for (int frame : keyframes) {
        add_frame(static_cast<float>(frame) / fps, static_cast<float>(frame + 1) / fps);
    }
    return positions;
}

// Replaces the stock LTX start/end branch when keyframes or a source clip are given.
static bool friday_ltx_condition_video(sd_ctx_t* sd_ctx, const sd_vid_gen_params_t* params, GenerationRequest* request,
                                       ImageGenerationLatents* latents, const sd::Tensor<float>& start_image,
                                       const sd::Tensor<float>& end_image) {
    try {
        float strength = std::clamp(request->strength, 0.f, 1.f);
        float conditioned = 1.f - strength;
        latents->init_latent = sd_ctx->sd->generate_init_latent(request->width, request->height, request->frames, true);
        latents->denoise_mask = make_ltxav_video_denoise_mask(latents->init_latent, 1.f);
        const int64_t target_frames = latents->init_latent.shape()[2];

        if (params->control_frames_size > 0) {
            int clip = std::min(params->control_frames_size, request->frames);
            clip = ((clip - 1) / 8) * 8 + 1;
            if (clip < 9 || (clip - 1) / 8 + 1 >= target_frames) {
                throw std::runtime_error("The source clip must be at least 9 frames and shorter than the video");
            }
            sd::Tensor<float> frames;
            for (int i = 0; i < clip; i++) {
                auto frame = sd_image_to_tensor(params->control_frames[i], request->width, request->height);
                frame = frame.reshape({frame.shape()[0], frame.shape()[1], 1, frame.shape()[2], frame.shape()[3]});
                frames = i == 0 ? frame : sd::ops::concat(frames, frame, 2);
            }
            auto clip_latent = sd_ctx->sd->encode_first_stage(frames);
            if (clip_latent.empty() || !apply_ltxav_condition_by_latent_index(&latents->init_latent, &latents->denoise_mask,
                                                                              clip_latent, 0, "source clip", conditioned)) {
                throw std::runtime_error("Could not encode the source clip");
            }
            LOG_INFO("LTX conditioning: %d source frames frozen as %" PRId64 " opening latents", clip, clip_latent.shape()[2]);
        } else if (!start_image.empty()) {
            if (!apply_ltxav_condition_image_by_latent_index(sd_ctx, start_image, &latents->init_latent, &latents->denoise_mask,
                                                             0, "init", strength)) {
                throw std::runtime_error("Could not encode the start image");
            }
        }

        std::vector<int> keyframes = friday_ltx_keyframe_frames(params->ref_images_count, request->frames);
        std::vector<sd::Tensor<float>> images;
        for (int i = 0; i < params->ref_images_count; i++) {
            images.push_back(sd_image_to_tensor(params->ref_images[i], request->width, request->height));
        }
        if (!end_image.empty()) {
            keyframes.push_back(request->frames - 1);
            images.push_back(end_image);
        }
        for (size_t i = 0; i < images.size(); i++) {
            auto latent = encode_ltxav_condition_image(sd_ctx, images[i], "keyframe");
            if (latent.empty() || latent.shape()[2] != 1) {
                throw std::runtime_error("Could not encode a keyframe");
            }
            latents->init_latent = sd::ops::concat(latents->init_latent, latent, 2);
            auto mask = sd::full<float>({latent.shape()[0], latent.shape()[1], 1, 1, 1}, conditioned);
            latents->denoise_mask = sd::ops::concat(latents->denoise_mask, mask, 2);
        }
        if (!keyframes.empty()) {
            latents->video_target_frame_count = target_frames;
            latents->video_conditioning_frame_count = static_cast<int64_t>(keyframes.size());
            latents->video_positions = friday_ltx_positions(latents->init_latent.shape()[0], latents->init_latent.shape()[1],
                                                            target_frames, keyframes, request->fps, request->vae_scale_factor);
            LOG_INFO("LTX conditioning: %zu keyframes appended", keyframes.size());
        }
        return true;
    } catch (const std::exception& error) {
        LOG_ERROR("LTX conditioning failed: %s", error.what());
        return false;
    }
}

// After audio and video are packed: freeze the imported soundtrack's latents from the start.
static bool friday_ltx_import_audio(ImageGenerationLatents* latents, int latent_channels) {
    const char* input = std::getenv("FRIDAY_LTX_AUDIO_IN");
    if (input == nullptr || *input == '\0') {
        return true;
    }
    try {
        std::ifstream in(input, std::ios::binary);
        uint32_t header[5] = {};
        in.read(reinterpret_cast<char*>(header), sizeof(header));
        if (!in || header[0] != 0x55415246 || header[1] != 1 || header[2] != 8 || header[3] != 16 || header[4] == 0 ||
            header[4] > 100000) {
            throw std::runtime_error("Invalid audio latent header");
        }
        const int audio_frames = latents->audio_length;
        if (audio_frames <= 0 || latents->init_latent.empty()) {
            throw std::runtime_error("This generation has no soundtrack to condition");
        }
        std::vector<float> audio(static_cast<size_t>(header[4]) * 8 * 16);
        in.read(reinterpret_cast<char*>(audio.data()), audio.size() * sizeof(float));
        if (!in) {
            throw std::runtime_error("Audio latent file is truncated");
        }
        const int frozen = std::min<int>(header[4], audio_frames - 1);
        auto& packed = latents->init_latent;
        const uint64_t video_values = static_cast<uint64_t>(packed.shape()[0]) * packed.shape()[1] * packed.shape()[2] * latent_channels;
        if (packed.numel() < static_cast<int64_t>(video_values + 128ULL * audio_frames)) {
            throw std::runtime_error("Packed latent has no room for the soundtrack");
        }
        if (latents->denoise_mask.empty()) {
            latents->denoise_mask = sd::Tensor<float>::ones(packed.shape());
        }
        if (latents->denoise_mask.numel() != packed.numel()) {
            throw std::runtime_error("Unexpected denoise mask layout");
        }
        for (int channel = 0; channel < 8; channel++) {
            const uint64_t src = 16ULL * channel * header[4];
            const uint64_t dst = video_values + 16ULL * channel * audio_frames;
            std::copy_n(audio.data() + src, 16ULL * frozen, packed.data() + dst);
            std::fill_n(latents->denoise_mask.data() + dst, 16ULL * frozen, 0.f);
        }
        LOG_INFO("LTX conditioning: %d of %d audio latent frames frozen from the input soundtrack", frozen, audio_frames);
        return true;
    } catch (const std::exception& error) {
        LOG_ERROR("LTX audio conditioning failed: %s", error.what());
        return false;
    }
}

#endif

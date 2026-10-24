#ifndef __FRIDAY_LTX_CHUNK_RUNTIME_H__
#define __FRIDAY_LTX_CHUNK_RUNTIME_H__

#include <string>

static friday_ltx::State friday_ltx_state(const sd::Tensor<float>& packed,
                                        int audio_length, int fps, int channels) {
    friday_ltx::State state;
    state.width = packed.shape()[0];
    state.height = packed.shape()[1];
    state.frames = packed.shape()[2];
    state.channels = channels;
    state.audio_frames = audio_length;
    state.fps = fps;
    state.validate();
    if (packed.numel() < state.video_size() + state.audio_size()) {
        throw std::runtime_error("Packed LTX AV tensor is too small");
    }
    return state;
}

static bool friday_ltx_import_carry(sd::Tensor<float>* packed, sd::Tensor<float>* mask,
                                    int audio_length, int fps, int channels, const char* stage) {
    const char* input = std::getenv("FRIDAY_LTX_CARRY_IN");
    if (input == nullptr || *input == '\0') {
        return true;
    }
    try {
        if (!mask->empty()) {
            throw std::runtime_error("Chunk carry cannot be combined with other conditioning masks");
        }
        const char* frames = std::getenv("FRIDAY_LTX_CARRY_FRAMES");
        if (frames == nullptr) {
            throw std::runtime_error("FRIDAY_LTX_CARRY_FRAMES is required");
        }
        const auto previous = friday_ltx::read_state((std::string(input) + "." + stage + ".avc").c_str());
        const auto current = friday_ltx_state(*packed, audio_length, fps, channels);
        *mask = sd::Tensor<float>::ones(packed->shape());
        const int carry_frames = std::stoi(frames);
        friday_ltx::inject(previous, current, carry_frames, packed->data(), mask->data(), packed->numel());
        LOG_INFO("LTX chunk %s: carrying %d video latent frames and %d audio latent frames as frozen context",
                 stage, (carry_frames - 1) / 8 + 1,
                 static_cast<int>(std::ceil(carry_frames * 25.0 / fps)));
        return true;
    } catch (const std::exception& error) {
        LOG_ERROR("LTX chunk import failed: %s", error.what());
        return false;
    }
}

static bool friday_ltx_export_carry(const sd::Tensor<float>& packed, int audio_length,
                                    int fps, int channels, const char* stage) {
    const char* output = std::getenv("FRIDAY_LTX_CARRY_OUT");
    if (output == nullptr || *output == '\0') {
        return true;
    }
    try {
        auto state = friday_ltx_state(packed, audio_length, fps, channels);
        state.video.assign(packed.data(), packed.data() + state.video_size());
        state.audio.assign(packed.data() + state.video_size(),
                           packed.data() + state.video_size() + state.audio_size());
        friday_ltx::write_state((std::string(output) + "." + stage + ".avc").c_str(), state);
        LOG_INFO("LTX chunk %s: saved AV latents (%u video frames, %u audio frames)",
                 stage, state.frames, state.audio_frames);
        return true;
    } catch (const std::exception& error) {
        LOG_ERROR("LTX chunk export failed: %s", error.what());
        return false;
    }
}

#endif

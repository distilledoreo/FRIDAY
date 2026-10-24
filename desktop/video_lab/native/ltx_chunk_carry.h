#ifndef __FRIDAY_LTX_CHUNK_CARRY_H__
#define __FRIDAY_LTX_CHUNK_CARRY_H__

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <stdexcept>
#include <vector>

namespace friday_ltx {

struct State {
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t frames = 0;
    uint32_t channels = 0;
    uint32_t audio_frames = 0;
    uint32_t fps = 0;
    std::vector<float> video;
    std::vector<float> audio;

    uint64_t video_size() const {
        return static_cast<uint64_t>(width) * height * frames * channels;
    }

    uint64_t audio_size() const {
        return static_cast<uint64_t>(audio_frames) * 16 * 8;
    }

    void validate() const {
        if (!width || !height || !frames || channels != 128 || !fps || !audio_frames ||
            width > 256 || height > 256 || frames > 4096 || fps > 240 ||
            video_size() + audio_size() > (1ULL << 26)) {
            throw std::runtime_error("Unsupported LTX carry dimensions");
        }
    }
};

inline void write_state(const char* path, const State& state) {
    state.validate();
    if (state.video.size() != state.video_size() || state.audio.size() != state.audio_size()) {
        throw std::runtime_error("LTX carry payload size mismatch");
    }
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    const std::array<uint32_t, 8> header = {0x46524156, 1, state.width, state.height,
                                          state.frames, state.channels, state.audio_frames, state.fps};
    out.write(reinterpret_cast<const char*>(header.data()), sizeof(header));
    out.write(reinterpret_cast<const char*>(state.video.data()), state.video.size() * sizeof(float));
    out.write(reinterpret_cast<const char*>(state.audio.data()), state.audio.size() * sizeof(float));
    out.close();
    if (!out) {
        throw std::runtime_error("Cannot save LTX carry state");
    }
}

inline State read_state(const char* path) {
    std::ifstream in(path, std::ios::binary);
    std::array<uint32_t, 8> header{};
    in.read(reinterpret_cast<char*>(header.data()), sizeof(header));
    if (!in || header[0] != 0x46524156 || header[1] != 1) {
        throw std::runtime_error("Invalid LTX carry state header");
    }
    State state;
    state.width = header[2];
    state.height = header[3];
    state.frames = header[4];
    state.channels = header[5];
    state.audio_frames = header[6];
    state.fps = header[7];
    state.validate();
    state.video.resize(state.video_size());
    state.audio.resize(state.audio_size());
    in.read(reinterpret_cast<char*>(state.video.data()), state.video.size() * sizeof(float));
    in.read(reinterpret_cast<char*>(state.audio.data()), state.audio.size() * sizeof(float));
    if (!in || in.peek() != std::ifstream::traits_type::eof()) {
        throw std::runtime_error("LTX carry payload is truncated or has trailing data");
    }
    return state;
}

inline void inject(const State& previous, const State& current, int carry_frames,
                   float* packed, float* mask, uint64_t packed_size) {
    previous.validate();
    current.validate();
    if (previous.width != current.width || previous.height != current.height ||
        previous.channels != current.channels || previous.fps != current.fps ||
        carry_frames < 9 || (carry_frames - 1) % 8) {
        throw std::runtime_error("LTX carry requires matching resolution/fps and an 8k+1 frame overlap");
    }
    const int video_carry = (carry_frames - 1) / 8 + 1;
    const int audio_carry = static_cast<int>(std::ceil(carry_frames * 25.0 / current.fps));
    if (video_carry >= static_cast<int>(current.frames) || video_carry > static_cast<int>(previous.frames) ||
        audio_carry >= static_cast<int>(current.audio_frames) || audio_carry > static_cast<int>(previous.audio_frames) ||
        packed_size < current.video_size() + current.audio_size()) {
        throw std::runtime_error("LTX carry exceeds the generation window");
    }
    const uint64_t plane = static_cast<uint64_t>(current.width) * current.height;
    std::fill_n(mask, packed_size, 1.f);
    for (uint32_t channel = 0; channel < current.channels; ++channel) {
        const uint64_t src = plane * (channel * previous.frames + previous.frames - video_carry);
        const uint64_t dst = plane * channel * current.frames;
        std::copy_n(previous.video.data() + src, plane * video_carry, packed + dst);
        std::fill_n(mask + dst, plane * video_carry, 0.f);
    }
    for (int channel = 0; channel < 8; ++channel) {
        const uint64_t src = 16ULL * (channel * previous.audio_frames + previous.audio_frames - audio_carry);
        const uint64_t dst = current.video_size() + 16ULL * channel * current.audio_frames;
        std::copy_n(previous.audio.data() + src, 16 * audio_carry, packed + dst);
        std::fill_n(mask + dst, 16 * audio_carry, 0.f);
    }
}

inline std::vector<float> audio_timesteps(const float* mask, uint64_t mask_size,
                                        uint64_t video_values, int audio_frames, float timestep) {
    if (audio_frames <= 0 || mask_size < video_values + 128ULL * audio_frames) {
        return {timestep};
    }
    std::vector<float> result(audio_frames, timestep);
    bool conditioned = false;
    for (int frame = 0; frame < audio_frames; ++frame) {
        result[frame] *= mask[video_values + 16ULL * frame];
        conditioned |= result[frame] != timestep;
    }
    return conditioned ? result : std::vector<float>{timestep};
}

}  // namespace friday_ltx

#endif

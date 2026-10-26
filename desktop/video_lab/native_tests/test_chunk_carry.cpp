#include "ltx_chunk_carry.h"

#include <cassert>
#include <cstdio>
#include <string>

int main(int argc, char** argv) {
    assert(argc == 2);
    friday_ltx::State previous;
    previous.width = 2;
    previous.height = 3;
    previous.frames = 13;
    previous.channels = 128;
    previous.audio_frames = 102;
    previous.fps = 24;
    previous.video.resize(previous.video_size());
    previous.audio.resize(previous.audio_size());
    for (size_t i = 0; i < previous.video.size(); ++i) previous.video[i] = static_cast<float>(i);
    for (size_t i = 0; i < previous.audio.size(); ++i) previous.audio[i] = static_cast<float>(i + 7);
    friday_ltx::write_state(argv[1], previous);
    auto loaded = friday_ltx::read_state(argv[1]);
    assert(loaded.video == previous.video && loaded.audio == previous.audio);
    auto current = previous;
    std::vector<float> packed(current.video_size() + current.audio_size() + 17, -2.f);
    std::vector<float> mask(packed.size(), -1.f);
    friday_ltx::inject(loaded, current, 25, packed.data(), mask.data(), packed.size());
    for (int c = 0; c < 128; ++c) {
        for (int t = 0; t < 13; ++t) {
            for (int p = 0; p < 6; ++p) {
                auto dst = (c * 13 + t) * 6 + p;
                assert(mask[dst] == (t < 4 ? 0.f : 1.f));
                assert(packed[dst] == (t < 4 ? previous.video[(c * 13 + 9 + t) * 6 + p] : -2.f));
            }
        }
    }
    for (int c = 0; c < 8; ++c) {
        for (int t = 0; t < 102; ++t) {
            for (int f = 0; f < 16; ++f) {
                auto dst = current.video_size() + (c * 102 + t) * 16 + f;
                assert(mask[dst] == (t < 27 ? 0.f : 1.f));
                assert(packed[dst] == (t < 27 ? previous.audio[(c * 102 + 75 + t) * 16 + f] : -2.f));
            }
        }
    }
    auto times = friday_ltx::audio_timesteps(mask.data(), mask.size(), current.video_size(), 102, 900.f);
    assert(times.size() == 102 && times[0] == 0.f && times[26] == 0.f && times[27] == 900.f);
    for (int bad : {1, 24, 97}) {
        bool rejected = false;
        try { friday_ltx::inject(loaded, current, bad, packed.data(), mask.data(), packed.size()); }
        catch (const std::runtime_error&) { rejected = true; }
        assert(rejected);
    }
    current.width = 3;
    bool rejected = false;
    try { friday_ltx::inject(loaded, current, 25, packed.data(), mask.data(), packed.size()); }
    catch (const std::runtime_error&) { rejected = true; }
    assert(rejected);
    std::ofstream(argv[1], std::ios::app | std::ios::binary).put('x');
    rejected = false;
    try { friday_ltx::read_state(argv[1]); }
    catch (const std::runtime_error&) { rejected = true; }
    assert(rejected);
    std::remove(argv[1]);
    std::puts("LTX carry: AV roundtrip, prefix/mask/timestep alignment, mismatch and corrupt-file checks passed");
}

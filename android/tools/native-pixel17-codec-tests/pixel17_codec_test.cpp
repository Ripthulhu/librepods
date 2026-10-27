// SPDX-License-Identifier: GPL-3.0-or-later
#include "pixel17_codec.h"
#include "wire.h"
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <vector>

namespace {
using namespace eld;
std::array<uintptr_t, 11> original_table{};
std::atomic<size_t> allocations{0}, releases{0}, destructions{0}, deletions{0}, locks{0};
std::atomic<bool> fail_allocation{false}, fail_constructor{false}, corrupt_constructor{false}, corrupt_name{false};
std::mutex block_mutex;
std::condition_variable changed;
bool block_reader = false, reader_entered = false, let_reader_finish = false;
bool use_debug_callback = false, block_debug = false, debug_entered = false, let_debug_finish = false;
std::atomic<size_t> debug_calls{0};
std::atomic<int> debug_fd{0};
std::atomic<const void*> debug_object{nullptr};
constexpr size_t config_offsets[] = {0x60, 0xa0, 0xe0, 0x120, 0x160};

void check(bool condition, const char* text = "Pixel object assertion failed") {
    if (!condition) { std::fprintf(stderr, "%s\n", text); std::abort(); }
}
template<class T> T get(const void* bytes, size_t offset) {
    T out;
    std::memcpy(&out, static_cast<const uint8_t*>(bytes) + offset, sizeof(out));
    return out;
}
template<class T> void put(void* bytes, size_t offset, const T& value) {
    std::memcpy(static_cast<uint8_t*>(bytes) + offset, &value, sizeof(value));
}
void* allocate(size_t bytes) {
    check(bytes == kPixel17CodecObjectSize);
    if (fail_allocation) return nullptr;
    ++allocations;
    return new uint8_t[bytes]{};
}
void release(void* bytes, size_t size) {
    check(size == kPixel17CodecObjectSize);
    ++releases;
    delete[] static_cast<uint8_t*>(bytes);
}
void destructor(void* object) {
    check(get<const uintptr_t*>(object, 0) == original_table.data() + 2,
          "Original vptr must be restored before host destruction");
    ++destructions;
}
void deleting_destructor(void* object) {
    destructor(object);
    ++deletions;
    release(object, kPixel17CodecObjectSize);
}
bool local_software(const void*) { return false; }
void no_op() {}
void construct(void* object, int32_t priority) {
    if (fail_constructor) throw std::runtime_error("synthetic constructor failure");
    put(object, 0, original_table.data() + 2);
    put(object, 0x30, int32_t{corrupt_constructor ? 2 : 1});
    put(object, 0x38, uint64_t{2});
    static_cast<uint8_t*>(object)[0x40] = 6;
    std::memcpy(static_cast<uint8_t*>(object) + 0x41, "AAC\0", 4);
    put(object, 0x58, priority);
    put(object, 0x5c, priority);
    for (size_t offset : config_offsets) {
        Pixel17NativeConfig config{1, priority, 0, 0, 0, 0x12345678,
            {INT64_C(0x123456789abcdef), -2, INT64_MIN, INT64_MAX}, UINT64_C(0xfedcba9876543210)};
        put(object, offset, config);
    }
    static_cast<uint8_t*>(object)[0x227] = 1;
}
void lock(void*) {
    std::unique_lock<std::mutex> guard(block_mutex);
    if (block_reader) {
        reader_entered = true;
        changed.notify_all();
        changed.wait(guard, [] { return let_reader_finish; });
    }
    ++locks;
}
void unlock(void*) { check(locks > 0); --locks; }
void debug_callback(const void* object, int fd) {
    check(locks == 0, "A debug callback must not inherit the codec lock");
    Pixel17NativeConfig config{};
    check(pixel17_codec_get_config(object, &config) && config.codec_type == 5,
          "Diagnostic callbacks may safely read the leased object's configuration");
    check(locks == 0);
    ++debug_calls; debug_fd = fd; debug_object = object;
    std::unique_lock<std::mutex> guard(block_mutex);
    if (block_debug) {
        debug_entered = true;
        changed.notify_all();
        changed.wait(guard, [] { return let_debug_finish; });
    }
}
void* assign_short(void* object, const char* text, size_t size) {
    check(get<uint8_t>(object, 0) == 6 && size == 7 && std::strcmp(text, "AAC-ELD") == 0);
    static_cast<uint8_t*>(object)[0] = static_cast<uint8_t>(size * 2);
    std::memcpy(static_cast<uint8_t*>(object) + 1, text, size + 1);
    if (corrupt_name) static_cast<uint8_t*>(object)[1] = 'X';
    return object;
}
Pixel17CodecBindings bindings() {
    Pixel17CodecBindings result{allocate, release, construct, original_table.data() + 2, lock, unlock, assign_short};
    if (use_debug_callback) result.debug = debug_callback;
    return result;
}
void dispose(void* object, size_t slot = 1) {
    const auto table = get<const uintptr_t*>(object, 0);
    reinterpret_cast<void (*)(void*)>(table[slot])(object);
}
template<class Fn> Fn method(void* object, size_t slot) {
    return reinterpret_cast<Fn>(get<const uintptr_t*>(object, 0)[slot]);
}
using ConfigFn = uint8_t (*)(void*, const uint8_t*, bool, uint8_t*);
using PeerFn = bool (*)(void*, const uint8_t*);

void creation_and_layout() {
    check(!pixel17_codec_create(5, 0));
    check(!pixel17_codec_prepare({}));
    original_table.fill(reinterpret_cast<uintptr_t>(&no_op));
    original_table[0] = original_table[1] = 0;
    original_table[2] = reinterpret_cast<uintptr_t>(&destructor);
    original_table[3] = reinterpret_cast<uintptr_t>(&deleting_destructor);
    original_table[6] = reinterpret_cast<uintptr_t>(&local_software);
    auto valid = bindings();
    auto incomplete = valid; incomplete.release = nullptr;
    check(!pixel17_codec_prepare(incomplete));
    check(pixel17_codec_prepare(valid));
    check(pixel17_codec_prepare(valid));
    auto changed_binding = valid; changed_binding.assign_short = nullptr;
    check(!pixel17_codec_prepare(changed_binding));
    changed_binding = valid; changed_binding.debug = use_debug_callback ? nullptr : debug_callback;
    check(!pixel17_codec_prepare(changed_binding));
    for (int index : {-1, 0, 1, 4, 6, 7, 8, 12}) check(!pixel17_codec_create(index, 0));
    check(!pixel17_codec_create(5, -2));
    check(!pixel17_codec_create(5, 1000001));
    void* object = pixel17_codec_create(5, 0);
    check(object && pixel17_codec_owns(object) && pixel17_codec_active_objects() == 1);
    check(get<int32_t>(object, 0x30) == 5);
    check(get<uint64_t>(object, 0x38) == kPixel17EldCodecId);
    check(get<uint8_t>(object, 0x40) == 14 &&
          std::strcmp(reinterpret_cast<const char*>(object) + 0x41, "AAC-ELD") == 0);
    check(get<int32_t>(object, 0x58) == 6001 && get<int32_t>(object, 0x5c) == 0);
    for (size_t i = 0; i < 5; ++i) {
        auto config = get<Pixel17NativeConfig>(object, config_offsets[i]);
        check(config.codec_type == 5 && config.opaque_tail == 0 && config.padding == 0);
        for (auto value : config.codec_specific) check(value == 0);
        check(config.sample_rate == (i < 3 ? 2u : 0u));
        check(config.bits_per_sample == (i < 3 ? kPixel17PcmMask : 0u));
        check(config.channel_mode == (i < 3 ? 2u : 0u));
    }
    check(get<uint8_t>(object, 0x227) == 1);
    check(method<int (*)(const void*)>(object, 2)(object) == 256000);
    check(!method<bool (*)(const void*)>(object, 3)(object));
    check(get<const uintptr_t*>(object, 0)[4] == original_table[6]);
    check(!method<bool (*)(const void*)>(object, 4)(object));
    check(method<bool (*)(void*)>(object, 7)(object));
    method<void (*)(void*, int)>(object, 8)(object, -1);
    check(debug_calls == (use_debug_callback ? 1u : 0u));
    if (use_debug_callback) check(debug_fd == -1 && debug_object == object);
    std::array<uint8_t, 45> info{};
    check(pixel17_codec_info(object, info.data(), info.size()) && bitrate(info.data(), info.size()) == 256000);
    Pixel17NativeConfig config{};
    check(pixel17_codec_get_config(object, &config) && config.codec_type == 5);
    int unrelated = 0;
    check(!pixel17_codec_owns(&unrelated) && !pixel17_codec_info(&unrelated, info.data(), info.size()));
    dispose(object);
    check(!pixel17_codec_owns(object) && pixel17_codec_active_objects() == 0 && locks == 0);

    object = pixel17_codec_create(5, 1234);
    check(object && get<int32_t>(object, 0x58) == 1234);
    dispose(object, 0);
    check(pixel17_codec_active_objects() == 0);
    release(object, kPixel17CodecObjectSize); // A non-deleting host destructor retains caller-owned storage.
}

void constructor_failure_cleanup() {
    auto before = releases.load();
    fail_allocation = true;
    check(!pixel17_codec_create(5, 0));
    check(releases == before);
    fail_allocation = false;
    fail_constructor = true;
    auto before_dtor = destructions.load();
    check(!pixel17_codec_create(5, 0));
    check(releases == before + 1 && destructions == before_dtor);
    fail_constructor = false;
    corrupt_constructor = true;
    check(!pixel17_codec_create(5, 0));
    check(releases == before + 2 && destructions == before_dtor + 1);
    corrupt_constructor = false;
    corrupt_name = true;
    check(!pixel17_codec_create(5, 0));
    check(releases == before + 3 && destructions == before_dtor + 2);
    corrupt_name = false;
    check(pixel17_codec_active_objects() == 0);
}

void negotiation_and_status() {
    void* object = pixel17_codec_create(5, 10001);
    check(object);
    std::array<uint8_t, 45> peer{}, result{};
    check(make_configuration(peer.data(), peer.size(), 128000));
    check(method<ConfigFn>(object, 5)(object, peer.data(), false, result.data()) == 0);
    check(bitrate(result.data(), result.size()) == 128000);
    check(method<int (*)(const void*)>(object, 2)(object) == 128000);
    std::array<uint8_t, kPixel17CodecObjectSize> saved{};
    std::memcpy(saved.data(), object, saved.size());
    peer[10] = 0;
    result.fill(0x5a);
    check(method<ConfigFn>(object, 5)(object, peer.data(), false, result.data()) != 0);
    check(std::memcmp(saved.data(), object, saved.size()) == 0 && result[0] == 0x5a);
    check(!method<PeerFn>(object, 6)(object, peer.data()));
    check(make_capabilities(peer.data(), peer.size()));
    Pixel17NativeConfig user{5, 0, 2, kPixel17PcmMask, 2, 0x12345678, {0, 0, 0, 0}, UINT64_C(0xfedcba9876543210)};
    Pixel17NativeConfig audio{0, 0, 2, kPixel17PcmMask, 2, 0xaabbccdd, {0, 0, 0, 0}, UINT64_MAX};
    put(object, 0x120, user); put(object, 0x160, audio);
    check(pixel17_codec_set_config(object, peer.data(), peer.size(), true, result.data(), result.size()) == 0);
    check(std::memcmp(static_cast<uint8_t*>(object) + 0x120, &user, sizeof(user)) == 0);
    check(std::memcmp(static_cast<uint8_t*>(object) + 0x160, &audio, sizeof(audio)) == 0);
    check(pixel17_codec_set_peer(object, peer.data(), peer.size()));
    check(get<Pixel17NativeConfig>(object, 0x60).opaque_tail == 0);
    check(bitrate(result.data(), result.size()) == 256000);
    check(std::memcmp(static_cast<uint8_t*>(object) + 0x1cd, peer.data(), peer.size()) == 0);
    for (int invalid : {0, 1, 2, 3}) {
        auto bad = user;
        if (invalid == 0) bad.bits_per_sample = 2;
        if (invalid == 1) bad.sample_rate = 1;
        if (invalid == 2) bad.codec_specific[3] = INT64_MIN;
        if (invalid == 3) bad.bits_per_sample = kPixel17PcmMask == 4 ? 1 : 4;
        put(object, 0x120, bad);
        std::memcpy(saved.data(), object, saved.size());
        result.fill(0x5a);
        check(pixel17_codec_set_config(object, peer.data(), peer.size(), true, result.data(), result.size()) != 0);
        check(std::memcmp(saved.data(), object, saved.size()) == 0 && result[0] == 0x5a);
    }
    dispose(object);
}

void bounds_and_lossless_copies() {
    void* object = pixel17_codec_create(5, 0);
    check(object);
    std::array<uint8_t, 46> peer{}, out{};
    check(make_capabilities(peer.data(), peer.size()));
    out.fill(0x5a);
    for (size_t size : {size_t(0), size_t(16), size_t(46)}) {
        check(!pixel17_codec_info(object, out.data(), size));
        check(!pixel17_codec_set_peer(object, peer.data(), size));
        check(pixel17_codec_set_config(object, peer.data(), size, true, out.data(), 45) != 0);
        check(pixel17_codec_set_config(object, peer.data(), 45, true, out.data(), size) != 0);
        check(out[0] == 0x5a);
    }
    check(!pixel17_codec_info(nullptr, out.data(), 45));
    check(!pixel17_codec_info(object, nullptr, 45));
    check(!pixel17_codec_set_peer(object, nullptr, 45));
    check(pixel17_codec_set_config(object, nullptr, 45, true, out.data(), 45) != 0);
    check(pixel17_codec_set_config(object, peer.data(), 45, true, nullptr, 45) != 0);
    check(pixel17_codec_set_config(object, peer.data(), 17, true, out.data(), 17) == 0);
    Pixel17NativeConfig raw{INT32_MIN, INT32_MAX, 0xfefdfcfb, 0xcafebabe, 0x12345678,
        0xdeadbeef, {INT64_MIN, INT64_MAX, -1, INT64_C(0x123456789abcdef)}, UINT64_MAX}, copied{};
    check(pixel17_copy_config(&raw, sizeof(raw), &copied));
    check(std::memcmp(&raw, &copied, sizeof(raw)) == 0);
    check(!pixel17_copy_config(&raw, 56, &copied) && !pixel17_copy_config(&raw, 65, &copied));
    check(!pixel17_copy_config(nullptr, 64, &copied) && !pixel17_copy_config(&raw, 64, nullptr));
    Pixel17PeerParams decoded{};
    uint8_t params[] = {1, 1, 0xe8, 3};
    check(pixel17_peer_params(params, sizeof(params), &decoded) && decoded.peer_mtu == 1000);
    check(!pixel17_peer_params(params, 3, &decoded) && !pixel17_peer_params(params, 5, &decoded));
    params[0] = 2; check(!pixel17_peer_params(params, 4, &decoded));
    params[0] = 1; params[2] = 8; params[3] = 0;
    check(!pixel17_peer_params(params, 4, &decoded));
    dispose(object);
}

void destruction_waits_for_reader_and_slots_are_bounded() {
    std::vector<void*> objects;
    for (int i = 0; i < 32; ++i) { auto* object = pixel17_codec_create(5, 0); check(object); objects.push_back(object); }
    check(!pixel17_codec_create(5, 0));
    void* selected = objects.back(); objects.pop_back();
    {
        std::lock_guard<std::mutex> guard(block_mutex);
        block_reader = true; reader_entered = false; let_reader_finish = false;
    }
    std::atomic<bool> read_ok{false}, destroyed{false};
    std::thread reader([&] { std::array<uint8_t, 45> info{}; read_ok = pixel17_codec_info(selected, info.data(), info.size()); });
    {
        std::unique_lock<std::mutex> guard(block_mutex);
        check(changed.wait_for(guard, std::chrono::seconds(2), [] { return reader_entered; }));
    }
    const auto deleting = method<void (*)(void*)>(selected, 1);
    std::thread destroyer([&] { deleting(selected); destroyed = true; });
    for (int i = 0; i < 200 && pixel17_codec_owns(selected); ++i) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    check(!pixel17_codec_owns(selected) && !destroyed);
    check(!pixel17_codec_create(5, 0), "Retiring readers still consume their bounded object slot");
    {
        std::lock_guard<std::mutex> guard(block_mutex);
        let_reader_finish = true; block_reader = false;
    }
    changed.notify_all();
    reader.join(); destroyer.join();
    check(read_ok && destroyed);
    void* replacement = pixel17_codec_create(5, 0); check(replacement); dispose(replacement);
    for (auto* object : objects) dispose(object);
    check(pixel17_codec_active_objects() == 0 && locks == 0);
}

void debug_lifetime() {
    if (!use_debug_callback) return;
    void* object = pixel17_codec_create(5, 0); check(object);
    const auto dump = method<void (*)(void*, int)>(object, 8);
    const auto before = debug_calls.load();
    int unrelated = 0;
    dump(&unrelated, 13); dump(nullptr, 14);
    check(debug_calls == before, "Unowned objects must never enter the diagnostic callback");
    {
        std::lock_guard<std::mutex> guard(block_mutex);
        block_debug = true; debug_entered = false; let_debug_finish = false;
    }
    std::thread diagnostic([&] { dump(object, 73); });
    {
        std::unique_lock<std::mutex> guard(block_mutex);
        check(changed.wait_for(guard, std::chrono::seconds(2), [] { return debug_entered; }));
    }
    std::atomic<bool> destroyed{false};
    const auto deleting = method<void (*)(void*)>(object, 1);
    std::thread destroyer([&] { deleting(object); destroyed = true; });
    for (int i = 0; i < 200 && pixel17_codec_owns(object); ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    check(!pixel17_codec_owns(object) && !destroyed,
          "A diagnostic lease must keep its object alive until callback return");
    {
        std::lock_guard<std::mutex> guard(block_mutex);
        let_debug_finish = true; block_debug = false;
    }
    changed.notify_all();
    diagnostic.join(); destroyer.join();
    check(destroyed && debug_calls == before + 1 && debug_fd == 73 && debug_object == object);
    check(pixel17_codec_active_objects() == 0 && locks == 0);
}

void quarantine_preserves_destruction() {
    void* object = pixel17_codec_create(5, 0); check(object);
    pixel17_codec_quarantine();
    check(!pixel17_codec_create(5, 0));
    check(pixel17_codec_owns(object));
    check(!method<bool (*)(void*)>(object, 7)(object));
    check(method<int (*)(const void*)>(object, 2)(object) == 0);
    std::array<uint8_t, 45> info{}, out{};
    check(make_capabilities(info.data(), info.size()));
    check(!pixel17_codec_info(object, out.data(), out.size()));
    check(pixel17_codec_set_config(object, info.data(), info.size(), true, out.data(), out.size()) != 0);
    check(!pixel17_codec_set_peer(object, info.data(), info.size()));
    const auto before = debug_calls.load();
    method<void (*)(void*, int)>(object, 8)(object, 74);
    check(debug_calls == before + (use_debug_callback ? 1 : 0),
          "Quarantine must preserve read-only diagnostics");
    dispose(object);
    check(pixel17_codec_active_objects() == 0);
}
}

int main(int argc, char** argv) {
    check(argc == 1 || (argc == 2 && std::strcmp(argv[1], "--debug") == 0));
    use_debug_callback = argc == 2;
    creation_and_layout();
    constructor_failure_cleanup();
    negotiation_and_status();
    bounds_and_lossless_copies();
    destruction_waits_for_reader_and_slots_are_bounded();
    debug_lifetime();
    quarantine_preserves_destruction();
    check(allocations == releases && destructions >= deletions && locks == 0);
    std::puts(use_debug_callback ? "Pixel17 object adapter and leased debug callbacks passed"
                                : "Pixel17 object adapter and default debug no-op passed");
}

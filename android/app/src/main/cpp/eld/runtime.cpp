// SPDX-License-Identifier: GPL-3.0-or-later
#include "runtime.h"
#include "note8_adapter.h"
#include "encoder_interface.h"
#include "game_host.h"
#include "hal_adapter.h"
#include "pixel17_runtime.h"
#include <atomic>
#include <cstring>
#include <limits>
#include <mutex>
#if defined(__ANDROID__)
#include <android/log.h>
#include <android/api-level.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <jni.h>
#include <link.h>
#include <stdio.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
extern "C" {
#include "xz_private.h"
}
#endif

namespace eld {
namespace {
constexpr size_t kMaximumHooks=21;
struct Segment { uint64_t va, offset, file_size, memory_size; uint32_t flags; };
std::mutex guard;
// Only read on Android; host test builds set them without reading.
[[maybe_unused]] RuntimeHook install_hook=nullptr;
[[maybe_unused]] RuntimeUnhook remove_hook=nullptr;
bool initialized=false, preference_known=false, opted_in=false, java_ack=false;
bool complete=false, attempted=false, late=false, veto=false;
bool lifetime_pinned=false;
bool pixel_mode=false;
std::atomic<bool> identity{false}, encoder_failed{false};
[[maybe_unused]] void* held_library=nullptr;
#if defined(__ANDROID__)
jobject host_loader_global=nullptr, module_bridge_global=nullptr;
#endif

bool add(uint64_t a,uint64_t b,uint64_t* result) {
    if(b>std::numeric_limits<uint64_t>::max()-a) return false;
    *result=a+b; return true;
}
bool image_anchor(uintptr_t base,uintptr_t symbol,uintptr_t origin,uintptr_t va) {
    uint64_t expected;
    return base && origin==base && add(base,va,&expected) && symbol==expected;
}
bool file_range(const uint8_t* file,size_t size,const Segment* segments,size_t count,
                uintptr_t va,size_t bytes,uint32_t permissions,const uint8_t** data) {
    uint64_t end;
    if(!file || !segments || !count || count>128 || !bytes || !add(va,bytes,&end)) return false;
    for(size_t i=0;i<count;i++) {
        const auto& segment=segments[i]; uint64_t segment_end,file_end,offset;
        if((segment.flags&permissions)!=permissions || ((permissions&1) && (segment.flags&2)) ||
           segment.file_size>segment.memory_size || !add(segment.va,segment.file_size,&segment_end) ||
           !add(segment.offset,segment.file_size,&file_end) || file_end>size ||
           va<segment.va || end>segment_end || !add(segment.offset,va-segment.va,&offset)) continue;
        *data=file+static_cast<size_t>(offset); return true;
    }
    return false;
}
bool memory_range(const Segment* segments,size_t count,uintptr_t va,size_t bytes,uint32_t flags) {
    uint64_t end;
    if(!segments || !count || count>128 || !bytes || !add(va,bytes,&end)) return false;
    for(size_t i=0;i<count;i++) {
        uint64_t limit;
        if((segments[i].flags&flags)==flags && !(segments[i].flags&1) &&
           add(segments[i].va,segments[i].memory_size,&limit) && va>=segments[i].va && end<=limit) return true;
    }
    return false;
}
bool same_context(const uint8_t* file,size_t size,uintptr_t base,const Segment* segments,
                  size_t count,uintptr_t va,size_t bytes,const uint8_t* prologue) {
    const uint8_t* expected=nullptr; uint64_t start,end;
    if(!base || !add(base,va,&start) || !add(start,bytes,&end) ||
       !file_range(file,size,segments,count,va,bytes,5,&expected) ||
       (prologue && (bytes<32 || std::memcmp(expected,prologue,32)!=0))) return false;
    return std::memcmp(expected,reinterpret_cast<const void*>(static_cast<uintptr_t>(start)),bytes)==0;
}
bool install_transaction(const Note8HookSpec* specs,size_t count,uintptr_t base,
                         RuntimeHook hook,RuntimeUnhook unhook) {
    if(!specs || !count || count>kMaximumHooks || !base || !hook || !unhook) return false;
    for(size_t i=0;i<count;i++) {
        if(!specs[i].replacement || !specs[i].original || *specs[i].original ||
           specs[i].elf_va>std::numeric_limits<uintptr_t>::max()-base) return false;
        for(size_t j=0;j<i;j++) if(specs[i].elf_va==specs[j].elf_va || specs[i].original==specs[j].original) return false;
    }
    size_t installed=0;
    for(;installed<count;installed++) {
        const auto& spec=specs[installed]; void* target=reinterpret_cast<void*>(base+spec.elf_va);
        int result=hook(target,spec.replacement,spec.original);
        if(result==0 && *spec.original && *spec.original!=target) continue;
        // A failed call may still have returned a trampoline. Retain it if rollback itself fails.
        if(unhook(target)==0) *spec.original=nullptr;
        while(installed) {
            const auto& previous=specs[--installed];
            if(unhook(reinterpret_cast<void*>(base+previous.elf_va))==0) *previous.original=nullptr;
        }
        return false;
    }
    return true;
}
void publish_locked() {
    bool allowed=initialized && preference_known && opted_in && lifetime_pinned && java_ack && complete &&
                 !late && !veto && !encoder_failed.load();
#if defined(__ANDROID__) && defined(__aarch64__)
    if(pixel_mode) {
        pixel17_runtime_set_enabled(allowed);
        // Native registration must precede Java publication. Installing detours is insufficient.
        if(allowed && pixel17_runtime_registered()) identity.store(true,std::memory_order_release);
    } else
#endif
    if(allowed) {
        identity.store(true,std::memory_order_release);
        if(!note8_set_enabled(true) || encoder_failed.load()) { veto=true; note8_set_enabled(false); }
    } else note8_set_enabled(false);
}
#if defined(__ANDROID__) && defined(__aarch64__)
void pixel_registered() {
    std::lock_guard<std::mutex> lock(guard);
    publish_locked();
}
#endif
void failed_encoder(uint64_t) {
    encoder_failed.store(true);
    note8_quarantine();
}

#if defined(__ANDROID__) && defined(__aarch64__)
void log(const char* text) { __android_log_print(ANDROID_LOG_INFO,"LibrePodsELD","%s",text); }
struct LoadedImage {
    uintptr_t base=0;
    char path[4096]{};
    Elf64_Phdr phdr[128]{};
    size_t count=0;
    unsigned matches=0;
};
const char* basename(const char* path) {
    if(!path) return "";
    const char* slash=std::strrchr(path,'/'); return slash?slash+1:path;
}
int find_image(dl_phdr_info* info,size_t,void* opaque) {
    auto* result=static_cast<LoadedImage*>(opaque);
    if(std::strcmp(basename(info->dlpi_name),"libbluetooth_jni.so")!=0) return 0;
    ++result->matches;
    if(result->matches!=1 || !info->dlpi_addr || !info->dlpi_phdr || info->dlpi_phnum>128 ||
       !info->dlpi_phnum || std::strlen(info->dlpi_name)>=sizeof(result->path)) return 1;
    result->base=info->dlpi_addr; result->count=info->dlpi_phnum;
    std::strcpy(result->path,info->dlpi_name);
    std::memcpy(result->phdr,info->dlpi_phdr,result->count*sizeof(Elf64_Phdr)); return 0;
}
bool mapped_permissions(uintptr_t address,size_t bytes,uint32_t flags) {
    uint64_t end;
    if(!bytes || !add(address,bytes,&end)) return false;
    FILE* maps=fopen("/proc/self/maps","r"); if(!maps) return false;
    char line[1024],permissions[5]; unsigned long long begin=0,finish=0; bool result=false;
    while(fgets(line,sizeof(line),maps)) {
        if(sscanf(line,"%llx-%llx %4s",&begin,&finish,permissions)==3 && address>=begin && end<=finish) {
            result=permissions[0]=='r' && (permissions[1]=='w')==bool(flags&2) &&
                   (permissions[2]=='x')==bool(flags&1); break;
        }
    }
    fclose(maps); return result;
}
struct FileImage {
    const uint8_t* data=nullptr; size_t size=0;
    Segment segments[128]{}; size_t segment_count=0;
    ~FileImage() { if(data) munmap(const_cast<uint8_t*>(data),size); }
    bool open(const LoadedImage& loaded) {
        if(loaded.matches!=1 || !loaded.base || !loaded.count || loaded.path[0]!='/') return false;
        int fd=::open(loaded.path,O_RDONLY|O_CLOEXEC); if(fd<0) return false;
        struct stat stat={};
        if(fstat(fd,&stat)!=0 || !S_ISREG(stat.st_mode) || stat.st_size!=13786880) { close(fd); return false; }
        size=static_cast<size_t>(stat.st_size);
        void* mapped=mmap(nullptr,size,PROT_READ,MAP_PRIVATE,fd,0); close(fd);
        if(mapped==MAP_FAILED) return false; data=static_cast<const uint8_t*>(mapped);
        uint8_t expected[32];
        for(size_t i=0;i<32;i++) {
            auto hex=[](char c)->unsigned { return c>='0' && c<='9'?c-'0':c-'a'+10; };
            expected[i]=static_cast<uint8_t>((hex(kNote8BluetoothSha256[i*2])<<4)|hex(kNote8BluetoothSha256[i*2+1]));
        }
        xz_sha256 hash; xz_sha256_reset(&hash); xz_sha256_update(data,size,&hash);
        if(!xz_sha256_validate(expected,&hash)) return false;
        const auto* header=reinterpret_cast<const Elf64_Ehdr*>(data); uint64_t end;
        if(std::memcmp(header->e_ident,ELFMAG,SELFMAG)!=0 || header->e_ident[EI_CLASS]!=ELFCLASS64 ||
           header->e_ident[EI_DATA]!=ELFDATA2LSB || header->e_machine!=EM_AARCH64 || header->e_type!=ET_DYN ||
           header->e_phentsize!=sizeof(Elf64_Phdr) || header->e_phnum!=loaded.count ||
           !add(header->e_phoff,loaded.count*sizeof(Elf64_Phdr),&end) || end>size) return false;
        const auto* phdr=reinterpret_cast<const Elf64_Phdr*>(data+header->e_phoff);
        if(std::memcmp(phdr,loaded.phdr,loaded.count*sizeof(Elf64_Phdr))!=0) return false;
        for(size_t i=0;i<loaded.count;i++) if(phdr[i].p_type==PT_LOAD) {
            const auto& p=phdr[i]; uint64_t file_end,memory_end,active_end;
            if(p.p_filesz>p.p_memsz || !add(p.p_offset,p.p_filesz,&file_end) || file_end>size ||
               !add(p.p_vaddr,p.p_memsz,&memory_end) || !add(loaded.base,memory_end,&active_end)) return false;
            segments[segment_count++]={p.p_vaddr,p.p_offset,p.p_filesz,p.p_memsz,p.p_flags};
        }
        return segment_count!=0;
    }
    bool context(uintptr_t base,uintptr_t va,size_t bytes,const uint8_t* prologue=nullptr) const {
        return va<=std::numeric_limits<uintptr_t>::max()-base && mapped_permissions(base+va,bytes,5) &&
            same_context(data,size,base,segments,segment_count,va,bytes,prologue);
    }
};
// Complete functions used outside detours: FDK, AAC object vtable, alloc/free and four peer/SCO readers.
struct ReadPin { uintptr_t va; size_t bytes; };
const ReadPin read_pins[]={
    {0x70fba0,992},{0x712144,1612},{0x712790,572},{0x7129cc,620},{0x710134,8208},{0x71006c,200},
    {0x68f39c,196},{0x68f52c,36},
    {0x4aa864,12},{0x5b1a84,132},{0x5b1b08,340},{0x4b6c08,24},
    {0x543dac,4},{0x543db0,44},{0x48e924,8},{0x543e48,2232},
    {0x53dd88,676},{0x544700,664},{0x543ddc,108},{0x545b9c,272},
};
bool platform_function(void* symbol) {
    Dl_info info={};
    return symbol && dladdr(symbol,&info) && std::strcmp(basename(info.dli_fname),"libc++.so")==0 &&
           mapped_permissions(reinterpret_cast<uintptr_t>(symbol),4,5);
}
bool bootstrap(const LoadedImage& loaded,void* module) {
    FileImage file; if(!file.open(loaded)) { log("Rejected Bluetooth file/image identity"); return false; }
    if(!module || !lifetime_pinned) { log("Missing callback handle or native-library lifetime pin"); return false; }
    void* anchor=dlsym(module,"aacEncOpen"); Dl_info origin={};
    if(!anchor || !dladdr(anchor,&origin) || !image_anchor(loaded.base,reinterpret_cast<uintptr_t>(anchor),
            reinterpret_cast<uintptr_t>(origin.dli_fbase),0x70fba0)) {
        log("Callback handle does not identify the pinned Bluetooth image"); return false;
    }
    // Borrowed native-API handle: its actual Java owner and this module's bridge are globally pinned.
    // Do not reopen across class-loader namespaces or close a handle owned by Android NativeLibraries.
    held_library=module;
    size_t codec_count=0,game_count=0,hal_count=0,read_count=0;
    const auto* codec=note8_hook_specs(&codec_count); const auto* game=game_host_hook_specs(&game_count);
    const auto* hal=hal_adapter_hook_specs(&hal_count);
    const auto* hal_reads=hal_adapter_read_specs(&read_count);
    if(codec_count!=17 || game_count!=3 || hal_count!=1 || read_count!=12 || !hal || !hal_reads) {
        log("Unexpected hook/read descriptor count"); return false;
    }
    Note8HookSpec specs[kMaximumHooks]{};
    for(size_t i=0;i<codec_count;i++) specs[i]=codec[i];
    for(size_t i=0;i<game_count;i++) specs[codec_count+i]=game[i];
    specs[codec_count+game_count]=hal[0];
    for(const auto& spec:specs)
        if(spec.function_size<32 || !file.context(loaded.base,spec.elf_va,spec.function_size,spec.prologue)) {
            log("Rejected hook function context"); return false;
        }
    for(const auto& pin:read_pins) if(!file.context(loaded.base,pin.va,pin.bytes)) {
        log("Rejected encoder/read function context"); return false;
    }
    for(size_t i=0;i<read_count;i++) {
        const auto& spec=hal_reads[i]; const uint8_t* expected=nullptr;
        size_t prefix=spec.function_size<32?spec.function_size:32;
        if(!prefix || !file_range(file.data,file.size,file.segments,file.segment_count,
               spec.elf_va,spec.function_size,5,&expected) ||
           std::memcmp(expected,spec.prologue,prefix)!=0 || !file.context(loaded.base,spec.elf_va,spec.function_size)) {
            log("Rejected software HAL function context"); return false;
        }
    }
    const uintptr_t globals[]={kHalHidlActiveStorage,kHalHidlSoftwareStorage};
    for(uintptr_t va:globals) {
        if(va%alignof(void*) || !memory_range(file.segments,file.segment_count,va,sizeof(void*),6) ||
           va>std::numeric_limits<uintptr_t>::max()-loaded.base ||
           !mapped_permissions(loaded.base+va,sizeof(void*),6)) {
            log("Rejected software HAL storage bounds/permissions"); return false;
        }
    }
    const uint8_t* unused=nullptr;
    if(!file_range(file.data,file.size,file.segments,file.segment_count,0xc81940,11*sizeof(uintptr_t),4,&unused) ||
       !mapped_permissions(loaded.base+0xc81940,11*sizeof(uintptr_t),4)) {
        log("Rejected AAC vtable bounds/permissions"); return false;
    }
    auto lock=reinterpret_cast<Note8MutexFn>(dlsym(module,"_ZNSt3__115recursive_mutex4lockEv"));
    auto unlock=reinterpret_cast<Note8MutexFn>(dlsym(module,"_ZNSt3__115recursive_mutex6unlockEv"));
    if(!platform_function(reinterpret_cast<void*>(lock)) || !platform_function(reinterpret_cast<void*>(unlock))) {
        log("Cannot resolve platform recursive mutex ABI"); return false;
    }
    EldFdkApi api={}; EldFdkEncoder* test=nullptr; EldFdkConfig config={};
    Dl_info fdk_origin={};
    if(eld_fdk_bind(module,&api)!=ELD_FDK_OK || !dladdr(reinterpret_cast<void*>(api.open),&fdk_origin) ||
       reinterpret_cast<uintptr_t>(fdk_origin.dli_fbase)!=loaded.base ||
       eld_fdk_open(&api,256000,&test,&config)!=ELD_FDK_OK) {
        log("FDK bind/configuration preflight failed"); return false;
    }
    int16_t silence[960]={}; uint8_t au[321]; size_t count=0;
    int encoded=eld_fdk_encode(test,silence,480,au,sizeof(au),&count); eld_fdk_close(&test);
    if(encoded!=ELD_FDK_OK) { log("FDK silent encode preflight failed"); return false; }
    if(!note8_prepare(loaded.base,lock,unlock)) { log("Codec object/vtable/slot preflight failed"); return false; }
    if(!game_host_prepare(loaded.base)) { log("Peer/SCO reader preflight failed"); return false; }
    if(!hal_adapter_prepare(loaded.base)) { log("Software HAL static preflight failed"); return false; }
    // The provider can be uninitialized here. Actual transport/software admission is selection-time.
    EncoderHost host={api,reinterpret_cast<void* (*)(size_t)>(loaded.base+0x68f39c),
        reinterpret_cast<void (*)(void*)>(loaded.base+0x68f52c),&failed_encoder,&game_host_current_peer};
    if(!encoder_prepare(host)) { log("Encoder interface preflight failed"); return false; }
    if(!install_transaction(specs,kMaximumHooks,loaded.base,install_hook,remove_hook)) {
        log("Hook transaction failed; restart required"); return false;
    }
    log("Pinned native AAC-ELD preflight and hook transaction complete"); return true;
}
#endif
}

void runtime_init(RuntimeHook hook,RuntimeUnhook unhook) {
    std::lock_guard<std::mutex> lock(guard);
    if(initialized) return;
    initialized=true; install_hook=hook; remove_hook=unhook;
    if(!hook || !unhook) veto=true;
#if defined(__ANDROID__) && defined(__aarch64__)
    LoadedImage loaded; dl_iterate_phdr(find_image,&loaded);
    if(loaded.matches) { late=true; log("Bluetooth image predates native initialization; restart required"); }
#endif
    publish_locked();
}
void runtime_library_loaded(const char* name,void* handle) {
    if(!name) return;
#if defined(__ANDROID__) && defined(__aarch64__)
    if(std::strcmp(basename(name),"libbluetooth_jni.so")!=0) return;
    std::lock_guard<std::mutex> lock(guard);
    if(attempted || complete || late || veto) return;
    attempted=true;
    if(!initialized || !preference_known || !opted_in) {
        late=true;
        log("Bluetooth load preceded startup opt-in; enabling requires restart");
        publish_locked(); return;
    }
    if(!java_ack) {
        late=true; log("Bluetooth load preceded Java bridge acknowledgment; restart required");
        publish_locked(); return;
    }
    LoadedImage loaded; dl_iterate_phdr(find_image,&loaded);
    pixel_mode=android_get_device_api_level()==37;
    complete=pixel_mode?pixel17_runtime_prepare(handle,install_hook,remove_hook,pixel_registered):bootstrap(loaded,handle);
    if(!complete) veto=true;
    publish_locked();
#else
    (void)name;
    (void)handle;
#endif
}
void runtime_set_enabled(bool enabled) {
    std::lock_guard<std::mutex> lock(guard);
    if(!preference_known) { preference_known=true; opted_in=enabled; }
    else if(!enabled) veto=true;
    // A startup-off or revoked opt-in cannot be enabled in an already initialized process.
    publish_locked();
}
void runtime_java_ready(bool ready) {
    std::lock_guard<std::mutex> lock(guard);
    if(identity.load() && !ready) veto=true;
    java_ack=ready && lifetime_pinned; publish_locked();
}
bool runtime_ready() { return identity.load(std::memory_order_acquire); }
int runtime_native_codec_index() { return runtime_ready() ? kNote8EldSlot : -1; }

#ifdef ELD_RUNTIME_TESTING
bool runtime_test_context(const uint8_t* file,size_t size,uintptr_t base,
    const RuntimeTestSegment* segments,size_t count,uintptr_t va,size_t bytes,const uint8_t* prologue) {
    static_assert(sizeof(RuntimeTestSegment)==sizeof(Segment));
    if(!segments || count>128) return false;
    Segment copied[128]; std::memcpy(copied,segments,count*sizeof(Segment));
    return same_context(file,size,base,copied,count,va,bytes,prologue);
}
bool runtime_test_install(const Note8HookSpec* specs,size_t count,uintptr_t base,RuntimeHook hook,RuntimeUnhook unhook) {
    return install_transaction(specs,count,base,hook,unhook);
}
bool runtime_test_memory(const RuntimeTestSegment* segments,size_t count,uintptr_t va,size_t bytes,uint32_t flags) {
    if(!segments || count>128) return false;
    Segment copied[128]; std::memcpy(copied,segments,count*sizeof(Segment));
    return memory_range(copied,count,va,bytes,flags);
}
bool runtime_test_image_anchor(uintptr_t base,uintptr_t symbol,uintptr_t origin,uintptr_t va) {
    return image_anchor(base,symbol,origin,va);
}
void runtime_test_reset() {
    std::lock_guard<std::mutex> lock(guard);
    initialized=preference_known=opted_in=java_ack=complete=attempted=late=veto=lifetime_pinned=pixel_mode=false;
    install_hook=nullptr; remove_hook=nullptr; identity.store(false); encoder_failed.store(false); held_library=nullptr;
}
void runtime_test_native_complete() { std::lock_guard<std::mutex> lock(guard); complete=true; publish_locked(); }
void runtime_test_late() { std::lock_guard<std::mutex> lock(guard); late=true; publish_locked(); }
void runtime_test_encoder_failed() { failed_encoder(1); }
void runtime_test_lifetime_pinned() { std::lock_guard<std::mutex> lock(guard); lifetime_pinned=true; }
#endif
}

#if defined(__ANDROID__)
extern "C" JNIEXPORT jboolean JNICALL Java_me_kavishdevar_librepods_utils_NativeBridge_pinEldHostLoader(JNIEnv* env,jobject bridge,jobject loader) {
    if(!env || !bridge || !loader) return JNI_FALSE;
    jclass loader_type=env->FindClass("java/lang/ClassLoader");
    if(!loader_type) return JNI_FALSE;
    bool valid=env->IsInstanceOf(loader,loader_type); env->DeleteLocalRef(loader_type);
    if(!valid || env->ExceptionCheck()) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(eld::guard);
    if(eld::lifetime_pinned)
        return env->IsSameObject(loader,eld::host_loader_global) && env->IsSameObject(bridge,eld::module_bridge_global)?JNI_TRUE:JNI_FALSE;
    if(eld::attempted || eld::late || eld::veto) return JNI_FALSE;
    jobject host=env->NewGlobalRef(loader); if(!host) return JNI_FALSE;
    jobject module=env->NewGlobalRef(bridge);
    if(!module) { env->DeleteGlobalRef(host); return JNI_FALSE; }
    eld::host_loader_global=host; eld::module_bridge_global=module;
    // Process-lifetime references prevent both Android NativeLibrary owners from being collected.
    eld::lifetime_pinned=true;
    return JNI_TRUE;
}
extern "C" JNIEXPORT void JNICALL Java_me_kavishdevar_librepods_utils_NativeBridge_setEldEnabled(JNIEnv*,jobject,jboolean enabled) {
    eld::runtime_set_enabled(enabled==JNI_TRUE);
}
extern "C" JNIEXPORT void JNICALL Java_me_kavishdevar_librepods_utils_NativeBridge_setEldJavaBridgeReady(JNIEnv*,jobject,jboolean ready) {
    eld::runtime_java_ready(ready==JNI_TRUE);
}
extern "C" JNIEXPORT jboolean JNICALL Java_me_kavishdevar_librepods_utils_NativeBridge_isEldRuntimeReady(JNIEnv*,jobject) {
    return eld::runtime_ready()?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT jint JNICALL Java_me_kavishdevar_librepods_utils_NativeBridge_getEldNativeCodecIndex(JNIEnv*,jobject) {
    return eld::runtime_native_codec_index();
}
extern "C" JNIEXPORT void JNICALL Java_me_kavishdevar_librepods_utils_NativeBridge_setEldInitialCodecSlotFree(JNIEnv*,jobject,jboolean free) {
    eld::pixel17_runtime_initial_slot_free(free==JNI_TRUE);
}
#endif

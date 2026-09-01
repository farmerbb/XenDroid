// SPDX-License-Identifier: WTFPL

#ifndef APS3E_EMULATOR_H
#define APS3E_EMULATOR_H

#include <android/native_window_jni.h>
#include <mutex>
#include <string>
#include <vector>

namespace ae{
    constexpr int BOOT_TYPE_WITH_PATH=1;
    constexpr int BOOT_TYPE_WITH_FD=2;
    extern int boot_type;

    extern std::string boot_game_path;
    extern int boot_game_fd;

    extern ANativeWindow* window;       // guarded by window_mutex
    extern int window_width;            // guarded by window_mutex
    extern int window_height;           // guarded by window_mutex
    extern std::mutex window_mutex;

    // Surface lifecycle entry points (called from the JNI/binder thread).
    // attach: take ownership of `w` (one ref from ANativeWindow_fromSurface),
    //         stash it, and -- once booted -- marshal a recreate to main_thr.
    // detach: SYNCHRONOUSLY marshal a teardown to main_thr (blocks until the
    //         GPU drain completes), then release the window.
    // resize: update the cached size (swapchain re-queries size on recreate).
    extern void surface_attach(ANativeWindow* w, int width, int height);
    extern void surface_detach();
    extern void surface_resize(int width, int height);

    extern void main_thr();
    struct input_device_entry{
        int device_slot;
        std::string stable_id;
        std::string display_name;
        int guest_slot;
    };
    extern void key_event(int device_slot,int key_code,bool pressed,int value);
    extern int input_attach_device(const char* stable_id,const char* display_name,int subtype,int preferred_slot);
    extern void input_detach_device(int device_slot);
    extern std::vector<input_device_entry> input_list_devices();
    extern bool input_bind_slot(int guest_slot,int device_slot);
    extern void input_unbind_slot(int guest_slot);
    extern std::vector<uint16_t> input_vibration_state();
    extern bool is_running();
    extern bool is_paused();
    extern void pause();
    extern void resume();
    extern void quit();
    extern void flush_gpu_caches();
}
#endif //APS3E_EMULATOR_H

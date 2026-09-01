/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#ifndef xendroid_XE_ANDROID_INPUT_DRIVER_H
#define xendroid_XE_ANDROID_INPUT_DRIVER_H

#include <array>
#include <mutex>
#include <queue>
#include <string>
#include <vector>

#include "xenia/base/mutex.h"
#include "xenia/hid/input_driver.h"
#include "xenia/ui/virtual_key.h"

namespace xe {
    namespace hid {
        namespace android {

            class AndroidInputDriver final : public InputDriver {
            public:
                struct KeyStatus{
                    ui::VirtualKey id;
                    bool pressed;
                    int value;
                };

                static constexpr size_t kKeyCount = 24;
                // Guest slots cap at 4; the surplus lets extra pads stay attached
                // and be mapped without unplugging anything.
                static constexpr size_t kMaxDevices = 8;

                // The index into devices_ is the driver_slot InputSystem binds
                // to a guest slot.
                struct Device {
                    bool present = false;
                    std::string stable_id;
                    std::string display_name;
                    uint8_t subtype = 0x01;  // XINPUT_DEVSUBTYPE_GAMEPAD
                    int8_t preferred_slot = -1;
                    std::array<KeyStatus, kKeyCount> key_status;
                    std::array<KeyStatus, kKeyCount> prev_key_status;
                    uint32_t key_status_mask = 0;
                    uint32_t packet_number = 1;
                    uint16_t vibration_left = 0;
                    uint16_t vibration_right = 0;
                };

                explicit AndroidInputDriver(xe::ui::Window* window, size_t window_z_order);
                ~AndroidInputDriver() override;

                X_STATUS Setup() override;

                X_RESULT GetCapabilities(uint32_t user_index, uint32_t flags,X_INPUT_CAPABILITIES* out_caps) override;
                X_RESULT GetState(uint32_t user_index, X_INPUT_STATE* out_state) override;
                X_RESULT SetState(uint32_t user_index, X_INPUT_VIBRATION* vibration) override;
                X_RESULT GetKeystroke(uint32_t user_index, uint32_t flags,X_INPUT_KEYSTROKE* out_keystroke) override;

                InputType GetInputType() const override;
                std::vector<InputDeviceInfo> EnumerateDevices() override;

                // Returns the driver slot to pass to OnKey, or -1 when full.
                // Re-attaching a known stable_id reuses its slot, so a pad that
                // drops and returns keeps the guest slot it was mapped to.
                int AttachDevice(const std::string& stable_id,
                                 const std::string& display_name, uint8_t subtype,
                                 int8_t preferred_slot);
                void DetachDevice(int device_slot);
                void OnKey(int device_slot, int key_index, bool pressed, int value);
                std::vector<uint16_t> VibrationState();

            private:
                std::mutex devices_mutex_;
                std::array<Device, kMaxDevices> devices_;
            };
        }
    }
}

#endif //xendroid_XE_ANDROID_INPUT_DRIVER_H

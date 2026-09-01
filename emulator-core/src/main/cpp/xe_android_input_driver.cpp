/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#include "xe_android_input_driver.h"
#include "xenia/ui/virtual_key.h"
#include "xenia/base/logging.h"

namespace xe {
    namespace hid {
        namespace android {
            namespace {
            const std::array<AndroidInputDriver::KeyStatus,
                             AndroidInputDriver::kKeyCount>& KeyTemplate() {
                static const std::array<AndroidInputDriver::KeyStatus,
                                        AndroidInputDriver::kKeyCount> kTemplate = {{
                        {ui::VirtualKey::kXInputPadDpadLeft,false,0},
                        {ui::VirtualKey::kXInputPadDpadUp,false,0},
                        {ui::VirtualKey::kXInputPadDpadRight,false,0},
                        {ui::VirtualKey::kXInputPadDpadDown,false,0},
                        {ui::VirtualKey::kXInputPadA,false,0},
                        {ui::VirtualKey::kXInputPadB,false,0},
                        {ui::VirtualKey::kXInputPadX,false,0},
                        {ui::VirtualKey::kXInputPadY,false,0},
                        {ui::VirtualKey::kXInputPadBack,false,0},
                        {ui::VirtualKey::kXInputPadStart,false,0},

                        {ui::VirtualKey::kXInputPadLShoulder,false,0},
                        {ui::VirtualKey::kXInputPadRShoulder,false,0},
                        {ui::VirtualKey::kXInputPadLThumbPress,false,0},
                        {ui::VirtualKey::kXInputPadRThumbPress,false,0},
                        {ui::VirtualKey::kXInputPadLTrigger,false,0},
                        {ui::VirtualKey::kXInputPadRTrigger,false,0},

                        {ui::VirtualKey::kXInputPadLThumbLeft,false,0},
                        {ui::VirtualKey::kXInputPadLThumbUp,false,0},
                        {ui::VirtualKey::kXInputPadLThumbRight,false,0},
                        {ui::VirtualKey::kXInputPadLThumbDown,false,0},
                        {ui::VirtualKey::kXInputPadRThumbLeft,false,0},
                        {ui::VirtualKey::kXInputPadRThumbUp,false,0},
                        {ui::VirtualKey::kXInputPadRThumbRight,false,0},
                        {ui::VirtualKey::kXInputPadRThumbDown,false,0},
                }};
                return kTemplate;
            }
            }  // namespace

            AndroidInputDriver::AndroidInputDriver(xe::ui::Window* window, size_t window_z_order)
                    : InputDriver(window, window_z_order) {
                for (Device& device : devices_) {
                    device.key_status = KeyTemplate();
                    device.prev_key_status = device.key_status;
                }
            }

            AndroidInputDriver::~AndroidInputDriver()=default;

            X_STATUS AndroidInputDriver::Setup() {
                // The overlay is always available; the app detaches it while a
                // physical pad is attached so the pad lands on player 1.
                AttachDevice("android-touch", "On-screen controls", 0x01, 0);
                return X_STATUS_SUCCESS;
            }

            X_RESULT AndroidInputDriver::GetCapabilities(uint32_t user_index, uint32_t flags,X_INPUT_CAPABILITIES* out_caps) {
                //XELOGI("AID:GetCapabilities {} {}",user_index, flags);
                {
                    std::lock_guard<std::mutex> lock(devices_mutex_);
                    if (user_index >= kMaxDevices || !devices_[user_index].present) {
                        return X_ERROR_DEVICE_NOT_CONNECTED;
                    }
                }

                // TODO(benvanik): confirm with a real XInput controller.
                out_caps->type = 0x01;      // XINPUT_DEVTYPE_GAMEPAD
                out_caps->sub_type = 0x01;  // XINPUT_DEVSUBTYPE_GAMEPAD
                out_caps->flags = 0;
                out_caps->gamepad.buttons = 0xFFFF;
                out_caps->gamepad.left_trigger = 0xFF;
                out_caps->gamepad.right_trigger = 0xFF;
                out_caps->gamepad.thumb_lx = (int16_t)0xFFFFu;
                out_caps->gamepad.thumb_ly = (int16_t)0xFFFFu;
                out_caps->gamepad.thumb_rx = (int16_t)0xFFFFu;
                out_caps->gamepad.thumb_ry = (int16_t)0xFFFFu;
                // Non-zero so titles that gate rumble on capabilities will send it.
                out_caps->vibration.left_motor_speed = 0xFFFF;
                out_caps->vibration.right_motor_speed = 0xFFFF;
                return X_ERROR_SUCCESS;
            }

            X_RESULT AndroidInputDriver::GetState(uint32_t user_index,X_INPUT_STATE* out_state) {
                //XELOGI("AID:GetState {}",user_index);
                std::lock_guard<std::mutex> lock(devices_mutex_);
                if (user_index >= kMaxDevices || !devices_[user_index].present) {
                    return X_ERROR_DEVICE_NOT_CONNECTED;
                }
                Device& device = devices_[user_index];
                device.packet_number++;

                uint16_t buttons = 0;
                uint8_t left_trigger = 0;
                uint8_t right_trigger = 0;
                int16_t thumb_lx = 0;
                int16_t thumb_ly = 0;
                int16_t thumb_rx = 0;
                int16_t thumb_ry = 0;


                if (/*window()->HasFocus() && */1) {
                    //XELOGI("AID:GetState:HasFocus");
                    for (const KeyStatus& ks : device.key_status) {
                        if (!ks.pressed) continue;
                        switch (ks.id) {
                            case ui::VirtualKey::kXInputPadA:
                                buttons |= 0x1000;  // XINPUT_GAMEPAD_A
                                break;
                            case ui::VirtualKey::kXInputPadY:
                                buttons |= 0x8000;  // XINPUT_GAMEPAD_Y
                                break;
                            case ui::VirtualKey::kXInputPadB:
                                buttons |= 0x2000;  // XINPUT_GAMEPAD_B
                                break;
                            case ui::VirtualKey::kXInputPadX:
                                buttons |= 0x4000;  // XINPUT_GAMEPAD_X
                                break;
                            case ui::VirtualKey::kXInputPadDpadLeft:
                                buttons |= 0x0004;  // XINPUT_GAMEPAD_DPAD_LEFT
                                break;
                            case ui::VirtualKey::kXInputPadDpadRight:
                                buttons |= 0x0008;  // XINPUT_GAMEPAD_DPAD_RIGHT
                                break;
                            case ui::VirtualKey::kXInputPadDpadDown:
                                buttons |= 0x0002;  // XINPUT_GAMEPAD_DPAD_DOWN
                                break;
                            case ui::VirtualKey::kXInputPadDpadUp:
                                buttons |= 0x0001;  // XINPUT_GAMEPAD_DPAD_UP
                                break;
                            case ui::VirtualKey::kXInputPadRThumbPress:
                                buttons |= 0x0080;  // XINPUT_GAMEPAD_RIGHT_THUMB
                                break;
                            case ui::VirtualKey::kXInputPadLThumbPress:
                                buttons |= 0x0040;  // XINPUT_GAMEPAD_LEFT_THUMB
                                break;
                            case ui::VirtualKey::kXInputPadBack:
                                buttons |= 0x0020;  // XINPUT_GAMEPAD_BACK
                                break;
                            case ui::VirtualKey::kXInputPadStart:
                                buttons |= 0x0010;  // XINPUT_GAMEPAD_START
                                break;
                            case ui::VirtualKey::kXInputPadLShoulder:
                                buttons |= 0x0100;  // XINPUT_GAMEPAD_LEFT_SHOULDER
                                break;
                            case ui::VirtualKey::kXInputPadRShoulder:
                                buttons |= 0x0200;  // XINPUT_GAMEPAD_RIGHT_SHOULDER
                                break;
                            // value < 0 means a digital press (on-screen pad), which
                            // is full deflection; a physical trigger sends 0-255.
                            case ui::VirtualKey::kXInputPadLTrigger:
                                left_trigger = ks.value < 0 ? 0xFF
                                                            : uint8_t(ks.value > 0xFF ? 0xFF : ks.value);
                                break;
                            case ui::VirtualKey::kXInputPadRTrigger:
                                right_trigger = ks.value < 0 ? 0xFF
                                                             : uint8_t(ks.value > 0xFF ? 0xFF : ks.value);
                                break;
                            case ui::VirtualKey::kXInputPadLThumbLeft:
                                thumb_lx =ks.value;//+= SHRT_MIN;
                                break;
                            case ui::VirtualKey::kXInputPadLThumbRight:
                                thumb_lx =ks.value;//+= SHRT_MAX;
                                break;
                            case ui::VirtualKey::kXInputPadLThumbDown:
                                thumb_ly =ks.value;//+= SHRT_MIN;
                                break;
                            case ui::VirtualKey::kXInputPadLThumbUp:
                                thumb_ly =ks.value;//+= SHRT_MAX;
                                break;
                            case ui::VirtualKey::kXInputPadRThumbUp:
                                thumb_ry =ks.value;//+= SHRT_MAX;
                                break;
                            case ui::VirtualKey::kXInputPadRThumbDown:
                                thumb_ry =ks.value;//+= SHRT_MIN;
                                break;
                            case ui::VirtualKey::kXInputPadRThumbRight:
                                thumb_rx =ks.value;//+= SHRT_MAX;
                                break;
                            case ui::VirtualKey::kXInputPadRThumbLeft:
                                thumb_rx =ks.value;//+= SHRT_MIN;
                                break;
                        }
                    }
                }

                out_state->packet_number = device.packet_number;
                out_state->gamepad.buttons = buttons;
                out_state->gamepad.left_trigger = left_trigger;
                out_state->gamepad.right_trigger = right_trigger;
                out_state->gamepad.thumb_lx = thumb_lx;
                out_state->gamepad.thumb_ly = thumb_ly;
                out_state->gamepad.thumb_rx = thumb_rx;
                out_state->gamepad.thumb_ry = thumb_ry;

                return X_ERROR_SUCCESS;
            }

            X_RESULT AndroidInputDriver::SetState(uint32_t user_index,X_INPUT_VIBRATION* vibration) {
                //XELOGI( "AID:SetState {}", user_index );
                std::lock_guard<std::mutex> lock(devices_mutex_);
                if (user_index >= kMaxDevices || !devices_[user_index].present) {
                    return X_ERROR_DEVICE_NOT_CONNECTED;
                }
                if (vibration) {
                    devices_[user_index].vibration_left = vibration->left_motor_speed;
                    devices_[user_index].vibration_right = vibration->right_motor_speed;
                }

                return X_ERROR_SUCCESS;
            }

            std::vector<uint16_t> AndroidInputDriver::VibrationState() {
                std::vector<uint16_t> out(kMaxDevices * 2, 0);
                std::lock_guard<std::mutex> lock(devices_mutex_);
                for (size_t i = 0; i < kMaxDevices; ++i) {
                    if (!devices_[i].present) {
                        continue;
                    }
                    out[i * 2] = devices_[i].vibration_left;
                    out[i * 2 + 1] = devices_[i].vibration_right;
                }
                return out;
            }

            X_RESULT AndroidInputDriver::GetKeystroke(uint32_t user_index, uint32_t flags,X_INPUT_KEYSTROKE* out_keystroke) {
                //XELOGI( "AID:GetKeystroke {} {}", user_index, flags );
                std::lock_guard<std::mutex> lock(devices_mutex_);
                if (user_index >= kMaxDevices || !devices_[user_index].present) {
                    return X_ERROR_DEVICE_NOT_CONNECTED;
                }
                Device& device = devices_[user_index];

                /*if (!is_active()) {
                    return X_ERROR_EMPTY;
                }*/

                X_RESULT result = X_ERROR_EMPTY;

                ui::VirtualKey xinput_virtual_key = ui::VirtualKey::kNone;
                uint16_t unicode = 0;
                uint16_t keystroke_flags = 0;
                uint8_t hid_code = 0;
                int key_status_index=-1;

                if (device.key_status_mask == 0) {
                    return X_ERROR_EMPTY;
                }
                // One keystroke per call, lowest index first; the guest drains
                // the rest by polling until EMPTY.
                for(size_t i = 0; i < device.key_status.size(); i++){
                    if(device.key_status_mask&(1<<i)){
                        xinput_virtual_key=device.key_status[i].id;
                        key_status_index=i;
                        device.key_status_mask&=~(1<<i);
                        break;
                    }
                }

                if (xinput_virtual_key != ui::VirtualKey::kNone) {
                    if (device.key_status[key_status_index].pressed) {
                        keystroke_flags |= 0x0001;  // XINPUT_KEYSTROKE_KEYDOWN
                    } else {
                        keystroke_flags |= 0x0002;  // XINPUT_KEYSTROKE_KEYUP
                    }

                    if (device.prev_key_status[key_status_index].pressed == device.key_status[key_status_index].pressed) {
                        keystroke_flags |= 0x0004;  // XINPUT_KEYSTROKE_REPEAT
                    }

                    result = X_ERROR_SUCCESS;
                }

                out_keystroke->virtual_key = uint16_t(xinput_virtual_key);
                out_keystroke->unicode = unicode;
                out_keystroke->flags = keystroke_flags;
                out_keystroke->user_index = user_index;
                out_keystroke->hid_code = hid_code;

                // X_ERROR_EMPTY if no new keys
                // X_ERROR_DEVICE_NOT_CONNECTED if no device
                // X_ERROR_SUCCESS if key
                return result;
            }


            void AndroidInputDriver::OnKey(int device_slot, int key_index, bool pressed, int value){
                if (device_slot < 0 || size_t(device_slot) >= kMaxDevices ||
                    key_index < 0 || size_t(key_index) >= kKeyCount) {
                    return;
                }

                std::lock_guard<std::mutex> lock(devices_mutex_);
                Device& device = devices_[device_slot];
                if (!device.present) {
                    return;
                }
                const bool was_pressed = device.key_status[key_index].pressed;
                device.prev_key_status[key_index] = device.key_status[key_index];

                device.key_status[key_index].pressed = pressed;
                device.key_status[key_index].value = value;

                // Transitions only: analog axes re-send every motion sample, which
                // would keep GetKeystroke from ever reaching EMPTY.
                if (was_pressed != pressed) {
                    device.key_status_mask |= (1 << key_index);
                }
            }

            int AndroidInputDriver::AttachDevice(const std::string& stable_id,
                                                 const std::string& display_name,
                                                 uint8_t subtype,
                                                 int8_t preferred_slot) {
                int slot = -1;
                {
                    std::lock_guard<std::mutex> lock(devices_mutex_);
                    for (size_t i = 0; i < kMaxDevices; ++i) {
                        if (devices_[i].stable_id == stable_id) {
                            slot = int(i);
                            break;
                        }
                    }
                    if (slot < 0) {
                        for (size_t i = 0; i < kMaxDevices; ++i) {
                            if (!devices_[i].present && devices_[i].stable_id.empty()) {
                                slot = int(i);
                                break;
                            }
                        }
                    }
                    if (slot < 0) {
                        return -1;
                    }
                    Device& device = devices_[slot];
                    device.present = true;
                    device.stable_id = stable_id;
                    device.display_name = display_name;
                    device.subtype = subtype;
                    device.preferred_slot = preferred_slot;
                    device.key_status = KeyTemplate();
                    device.prev_key_status = device.key_status;
                    device.key_status_mask = 0;
                }
                NotifyDevicesChanged();
                return slot;
            }

            void AndroidInputDriver::DetachDevice(int device_slot) {
                if (device_slot < 0 || size_t(device_slot) >= kMaxDevices) {
                    return;
                }
                {
                    std::lock_guard<std::mutex> lock(devices_mutex_);
                    Device& device = devices_[device_slot];
                    if (!device.present) {
                        return;
                    }
                    device.present = false;
                    device.vibration_left = 0;
                    device.vibration_right = 0;
                    // stable_id is kept so a reconnect reclaims this slot, and with
                    // it whatever guest slot the binding table holds for the id.
                    device.key_status = KeyTemplate();
                    device.prev_key_status = device.key_status;
                    device.key_status_mask = 0;
                }
                NotifyDevicesChanged();
            }


            InputType AndroidInputDriver::GetInputType() const { return InputType::Controller; }

            std::vector<InputDeviceInfo> AndroidInputDriver::EnumerateDevices() {
                std::vector<InputDeviceInfo> out;
                std::lock_guard<std::mutex> lock(devices_mutex_);
                for (size_t i = 0; i < kMaxDevices; ++i) {
                    const Device& device = devices_[i];
                    if (!device.present) {
                        continue;
                    }
                    InputDeviceInfo info{};
                    info.driver_slot = uint8_t(i);
                    info.stable_id = device.stable_id;
                    info.display_name = device.display_name;
                    info.subtype = device.subtype;
                    info.preferred_slot = device.preferred_slot;
                    out.push_back(std::move(info));
                }
                return out;
            }

        }
    }
}

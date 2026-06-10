/*
 * Minimal SDL2 stub header for evshim compilation.
 * evshim loads all SDL2 symbols at runtime via dlopen/dlsym; this header only
 * provides the struct/type/constant definitions needed to compile the translation
 * unit.  Values match SDL 2.26+ (SDL_VirtualJoystickDesc as shipped in SDL2 >= 2.0.14).
 */
#pragma once
#include <stdint.h>
#include <stdbool.h>

/* SDL_Init flags */
#define SDL_INIT_JOYSTICK  0x00000200u

/* SDL_version */
typedef struct SDL_version {
    uint8_t major;
    uint8_t minor;
    uint8_t patch;
} SDL_version;

/* SDL_Joystick is an opaque struct — evshim only holds a pointer */
typedef struct _SDL_Joystick SDL_Joystick;

/* SDL_JoystickType */
typedef enum {
    SDL_JOYSTICK_TYPE_UNKNOWN,
    SDL_JOYSTICK_TYPE_GAMECONTROLLER,
} SDL_JoystickType;

/* SDL_VirtualJoystickDesc — added in SDL 2.24.0 */
#define SDL_VIRTUAL_JOYSTICK_DESC_VERSION  1

typedef struct SDL_VirtualJoystickDesc {
    uint16_t version;
    uint16_t type;        /* SDL_JoystickType */
    uint16_t naxes;
    uint16_t nbuttons;
    uint16_t nhats;
    uint16_t vendor_id;
    uint16_t product_id;
    uint16_t padding;
    uint32_t button_mask;
    uint32_t axis_mask;
    const char *name;

    void *userdata;
    void (*Update)(void *userdata);
    void (*SetPlayerIndex)(void *userdata, int player_index);
    int  (*Rumble)(void *userdata, uint16_t low_frequency_rumble, uint16_t high_frequency_rumble);
    int  (*RumbleTriggers)(void *userdata, uint16_t left_rumble, uint16_t right_rumble);
    int  (*SetLED)(void *userdata, uint8_t red, uint8_t green, uint8_t blue);
    int  (*SendEffect)(void *userdata, const void *data, int size);
} SDL_VirtualJoystickDesc;

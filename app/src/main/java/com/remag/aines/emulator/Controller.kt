package com.remag.aines.emulator


class Controller {
    var buttonState: Int = 0
    var shiftRegister: Int = 0
    var strobe: Boolean = false

    companion object {
        const val BUTTON_A      = 0x01
        const val BUTTON_B      = 0x02
        const val BUTTON_SELECT = 0x04
        const val BUTTON_START  = 0x08
        const val BUTTON_UP     = 0x10
        const val BUTTON_DOWN   = 0x20
        const val BUTTON_LEFT   = 0x40
        const val BUTTON_RIGHT  = 0x80
    }

    fun write(value: Int) {
        val newStrobe = (value and 0x01) != 0
        strobe = newStrobe
        if (strobe) {
            shiftRegister = buttonState
        }
    }

    fun read(): Int {
        if (strobe) {
            val res = (buttonState and 0x01) or 0x40
            return res
        }
        val response = shiftRegister and 0x01
        shiftRegister = (shiftRegister ushr 1) or 0x80 // Shift in 1s (unpressed)
        return response or 0x40
    }

    fun setButton(button: Int, pressed: Boolean) {
        if (pressed) {
            buttonState = buttonState or button
        } else {
            buttonState = buttonState and button.inv()
        }
        shiftRegister = buttonState
    }
}

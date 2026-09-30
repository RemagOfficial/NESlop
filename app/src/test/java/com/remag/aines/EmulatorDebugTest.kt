package com.remag.aines

import com.remag.aines.emulator.Controller
import com.remag.aines.emulator.NesMachine
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class EmulatorDebugTest {
    @Test
    fun testRunSuperMarioBrosDemoAndGameplay() {
        val romFile = File("src/main/assets/Super Mario Bros.nes")
        if (!romFile.exists()) return
        val romBytes = romFile.readBytes()
        val machine = NesMachine(romBytes)

        val pcHistory = mutableSetOf<Int>()
        for (frame in 1..3000) {
            if (frame == 100) {
                machine.pressButton(Controller.BUTTON_START, true)
            }
            if (frame == 110) {
                machine.pressButton(Controller.BUTTON_START, false)
            }
            if (frame in 200..1000) {
                machine.pressButton(Controller.BUTTON_RIGHT, true)
            }
            if (frame == 1001) {
                machine.pressButton(Controller.BUTTON_RIGHT, false)
            }
            machine.stepFrame()
            pcHistory.add(machine.cpu.regPc)
        }
        assertNotEquals("CPU should execute varied instructions during gameplay", 1, pcHistory.size)
    }
}

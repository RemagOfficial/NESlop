package com.remag.aines

import com.remag.aines.emulator.Cartridge
import com.remag.aines.emulator.Cpu6502
import com.remag.aines.emulator.Bus
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenBusTest {
    @Test
    fun testOpenBusLdaAbsolute() {
        val prg = ByteArray(16384) { 0.toByte() }
        prg[0] = 0xAD.toByte() // LDA Absolute
        prg[1] = 0x34.toByte() // lo
        prg[2] = 0x42.toByte() // hi

        val header = byteArrayOf(
            0x4E, 0x45, 0x53, 0x1A,
            1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        )
        val dummyRom = header + prg + ByteArray(8192) { 0.toByte() }
        val cartridge = Cartridge(dummyRom)
        val bus = Bus()
        bus.cartridge = cartridge
        val cpu = Cpu6502(bus)
        cpu.reset()
        cpu.regPc = 0x8000

        cpu.step()

        assertEquals("LDA Absolute from open bus should return high byte of operand (0x42)", 0x42, cpu.regA)
    }
}

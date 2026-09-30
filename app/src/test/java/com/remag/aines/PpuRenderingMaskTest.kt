package com.remag.aines

import com.remag.aines.emulator.Cartridge
import com.remag.aines.emulator.Ppu2c02
import org.junit.Assert.assertEquals
import org.junit.Test

class PpuRenderingMaskTest {
    @Test
    fun backgroundAndSpritesRespectRenderingEnableBits() {
        val header = byteArrayOf(
            0x4E, 0x45, 0x53, 0x1A,
            1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        )
        val ppu = Ppu2c02(Cartridge(header + ByteArray(16384) + ByteArray(8192) { 0xFF.toByte() }))
        ppu.paletteRam[0] = 0x0F
        ppu.paletteRam[3] = 0x01
        ppu.paletteRam[0x13] = 0x16
        ppu.oam[0] = 0
        ppu.oam[1] = 0
        ppu.oam[2] = 0
        ppu.oam[3] = 10

        ppu.ppuMask = 0x0A // Background enabled, including its left edge; sprites disabled.
        ppu.renderNametableToFramebuffer()
        assertEquals(ppu.systemPalette[0x01], ppu.frameBuffer[1 * 256 + 10])

        ppu.ppuMask = 0x10 // Sprites enabled, background disabled.
        ppu.renderNametableToFramebuffer()
        assertEquals(ppu.systemPalette[0x0F], ppu.frameBuffer[0])
        assertEquals(ppu.systemPalette[0x16], ppu.frameBuffer[1 * 256 + 10])
    }
}

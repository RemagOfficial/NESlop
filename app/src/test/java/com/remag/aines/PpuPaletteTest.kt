package com.remag.aines

import com.remag.aines.emulator.Cartridge
import com.remag.aines.emulator.Ppu2c02
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PpuPaletteTest {

    @Test
    fun testPaletteAttributeTableColorRendering() {
        // Create 16-byte iNES header + 16KB PRG + 8KB CHR
        val header = byteArrayOf(
            0x4E, 0x45, 0x53, 0x1A, // NES^Z
            1, // 1x16KB PRG
            1, // 1x8KB CHR
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        )
        val dummyRom = header + ByteArray(16384) + ByteArray(8192) { 0xFF.toByte() } // Pattern table all 1s
        val cartridge = Cartridge(dummyRom)
        val ppu = Ppu2c02(cartridge)
        ppu.ppuMask = 0x0A // Enable background rendering, including the left edge.

        // Write palette RAM:
        // Background color ($3F00): $0F (Black)
        // Sub-palette 0 ($3F01..03): $01 (Blue), $02, $03
        // Sub-palette 1 ($3F05..07): $16 (Red), $17, $18
        // Sub-palette 2 ($3F09..0B): $2A (Green), $2B, $2C
        // Sub-palette 3 ($3F0D..0F): $28 (Yellow/Olive), $29, $2A
        ppu.writeRegister(0x2006, 0x3F)
        ppu.writeRegister(0x2006, 0x00)
        
        val colors = intArrayOf(
            0x0F, 0x01, 0x02, 0x03, // Pal 0
            0x0F, 0x16, 0x17, 0x18, // Pal 1
            0x0F, 0x2A, 0x2B, 0x2C, // Pal 2
            0x0F, 0x28, 0x29, 0x2A  // Pal 3
        )
        for (c in colors) {
            ppu.writeRegister(0x2007, c)
        }

        // Set Attribute Table for tile (0,0) [Pal 0], (2,0) [Pal 1], (0,2) [Pal 2], (2,2) [Pal 3]
        // Attribute byte at $23C0: bits 0-1 = 0 (Pal 0), bits 2-3 = 1 (Pal 1), bits 4-5 = 2 (Pal 2), bits 6-7 = 3 (Pal 3)
        // Attribute byte = (3 << 6) | (2 << 4) | (1 << 2) | 0 = 0b11100100 = 0xE4
        ppu.writeRegister(0x2006, 0x23)
        ppu.writeRegister(0x2006, 0xC0)
        ppu.writeRegister(0x2007, 0xE4)

        // Park the scroll at the top-left of nametable 0 before rendering: the raster starts each
        // frame from v (reloaded from t on the pre-render scanline), and t is still $23C0 from the
        // attribute write above.
        ppu.writeRegister(0x2006, 0x00)
        ppu.writeRegister(0x2006, 0x00)

        // Render frame
        ppu.renderNametableToFramebuffer()

        // Check Top-Left tile (0,0) -> uses sub-palette 0 ($01 -> Blue)
        val colorTL = ppu.frameBuffer[0]
        // Check Top-Right tile (16,0) -> uses sub-palette 1 ($16 -> Red)
        val colorTR = ppu.frameBuffer[16]
        // Check Bottom-Left tile (0,16) -> uses sub-palette 2 ($2A -> Green)
        val colorBL = ppu.frameBuffer[16 * 256]
        // Check Bottom-Right tile (16,16) -> uses sub-palette 3 ($28 -> Yellow)
        val colorBR = ppu.frameBuffer[16 * 256 + 16]

        // Assert that different sub-palettes yield distinct colors!
        assertNotEquals("Top-Left and Top-Right should have different colors", colorTL, colorTR)
        assertNotEquals("Top-Left and Bottom-Left should have different colors", colorTL, colorBL)
        assertNotEquals("Top-Right and Bottom-Right should have different colors", colorTR, colorBR)

        // Verify exact RGB palette lookup values:
        assertEquals("Sub-palette 0 color 3", ppu.systemPalette[0x03], colorTL)
        assertEquals("Sub-palette 1 color 3", ppu.systemPalette[0x18], colorTR)
        assertEquals("Sub-palette 2 color 3", ppu.systemPalette[0x2C], colorBL)
        assertEquals("Sub-palette 3 color 3", ppu.systemPalette[0x2A], colorBR)
    }
}

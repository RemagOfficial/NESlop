package com.remag.aines

import com.remag.aines.emulator.Cartridge
import com.remag.aines.emulator.Ppu2c02
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprite 0 hit timing and mid-frame scroll writes - the two mechanisms Super Mario Bros. uses to
 * hold its status bar still while the level underneath it scrolls. Getting either of them wrong
 * slices the status bar in half, so the raster has to follow the hardware here rather than
 * approximate.
 */
class PpuSprite0SplitTest {
    private val backdropColor = 0x0F
    private val solidColor = 0x16

    /**
     * PPU on a synthetic NROM cart. CHR tile 0 is solid on all eight rows, tile 1 is transparent on
     * its first four rows and solid on its last four, tile 2 is transparent everywhere. Tile rows 3
     * and 4 of nametable 0 use tile 2 from column 24 rightwards, which leaves a gap whose left edge
     * says exactly which horizontal scroll the raster was using.
     */
    private fun ppuWithPatternTables(): Ppu2c02 {
        val chr = ByteArray(8192)
        for (row in 0 until 8) {
            chr[row] = 0xFF.toByte()
            chr[row + 8] = 0xFF.toByte()
            if (row >= 4) {
                chr[16 + row] = 0xFF.toByte()
                chr[16 + row + 8] = 0xFF.toByte()
            }
        }
        val header = byteArrayOf(
            0x4E, 0x45, 0x53, 0x1A, // NES^Z
            1, // 1x16KB PRG
            1, // 1x8KB CHR
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        )
        val ppu = Ppu2c02(Cartridge(header + ByteArray(16384) + chr))

        for (tileRow in 3..4) {
            for (col in 24 until 32) ppu.vram[tileRow * 32 + col] = 2
        }
        ppu.paletteRam[0] = backdropColor.toByte()
        ppu.paletteRam[3] = solidColor.toByte()
        ppu.writeRegister(0x2000, 0x00) // 8x8 sprites, both pattern tables at $0000
        ppu.writeRegister(0x2001, 0x1E) // Background and sprites, leftmost 8 pixels included
        return ppu
    }

    private fun Ppu2c02.placeSprite0(y: Int, tile: Int, attr: Int, x: Int) {
        writeRegister(0x2003, 0x00)
        listOf(y, tile, attr, x).forEach { writeRegister(0x2004, it) }
    }

    /** Clocks one whole frame, running [action] at every dot, and reports the first sprite 0 hit. */
    private fun Ppu2c02.runFrame(action: Ppu2c02.() -> Unit = {}): Pair<Int, Int> {
        var hitLine = -1
        var hitDot = -1
        for (i in 0 until 262 * 341) {
            clock()
            if (hitLine < 0 && (ppuStatus and 0x40) != 0) {
                hitLine = scanline
                hitDot = dot
            }
            action()
        }
        return hitLine to hitDot
    }

    private fun assertGapStartsAt(ppu: Ppu2c02, line: Int, x: Int) {
        assertEquals("background should be solid just before the gap on line $line",
            ppu.systemPalette[solidColor], ppu.frameBuffer[line * 256 + x - 1])
        assertEquals("background gap should start here on line $line",
            ppu.systemPalette[backdropColor], ppu.frameBuffer[line * 256 + x])
    }

    @Test
    fun sprite0HitWaitsForASolidSpritePixel() {
        val ppu = ppuWithPatternTables()
        // y=24 puts the sprite's pattern rows on lines 25..32; rows 0..3 of tile 1 are transparent,
        // so the first row that can hit is pattern row 4, i.e. scanline 29 - not the sprite's top.
        ppu.placeSprite0(y = 24, tile = 1, attr = 0x00, x = 100)

        val (hitLine, hitDot) = ppu.runFrame()

        assertEquals("sprite 0 hit should land on the first solid pattern row", 29, hitLine)
        assertEquals("sprite 0 hit should register just after its leftmost solid pixel", 101, hitDot)
    }

    @Test
    fun sprite0HitNeedsASolidBackgroundPixel() {
        val ppu = ppuWithPatternTables()
        // x=200 parks the sprite over the transparent tiles of tile row 3, so even though the
        // sprite itself is solid there is nothing for it to hit.
        ppu.placeSprite0(y = 24, tile = 1, attr = 0x00, x = 200)

        val (hitLine, _) = ppu.runFrame()

        assertEquals("a solid sprite pixel over a transparent background pixel is not a hit", -1, hitLine)
    }

    @Test
    fun sprite0HitIsIgnoredWhileRenderingIsOff() {
        val ppu = ppuWithPatternTables()
        ppu.placeSprite0(y = 24, tile = 1, attr = 0x00, x = 100)
        ppu.writeRegister(0x2001, 0x00) // Display off

        val (hitLine, _) = ppu.runFrame()

        assertEquals("no hit may be detected with the background or sprites hidden", -1, hitLine)
    }

    @Test
    fun midFrameScrollWriteAppliesFromTheNextScanline() {
        val ppu = ppuWithPatternTables()
        ppu.placeSprite0(y = 24, tile = 1, attr = 0x00, x = 100)

        var coarseXAfter = -1
        var fineXAfter = -1
        var verticalAtLine32 = -1
        var hitSeenBeforeWrite = false

        // Wait for the hit the way the game does, then program the level scroll (x = 183, y = 0)
        // part of the way down the scanline the hit landed on.
        val (hitLine, _) = ppu.runFrame {
            if (scanline == 29 && dot == 150) {
                hitSeenBeforeWrite = (ppuStatus and 0x40) != 0
                writeRegister(0x2005, 183)
                writeRegister(0x2005, 0)
            }
            if (scanline == 30 && dot == 0) {
                coarseXAfter = vramAddr and 0x1F
                fineXAfter = fineX
            }
            if (scanline == 32 && dot == 0) {
                verticalAtLine32 = vramAddr and 0x7BE0
            }
        }

        assertEquals("sprite 0 hit should be on line 29", 29, hitLine)
        assertTrue("the game should see the hit before it writes the split scroll", hitSeenBeforeWrite)

        // Line 28 was rasterised while the frame still had the VBlank scroll of x = 0, so the gap
        // that starts at nametable column 24 begins at pixel 192.
        assertGapStartsAt(ppu, line = 28, x = 192)
        // Line 30 is the first line after the write: only the horizontal half of the scroll reaches
        // v, and not until dot 257, so the same column now appears at 24 * 8 - 183 = 9.
        assertGapStartsAt(ppu, line = 30, x = 9)
        assertEquals("horizontal coarse scroll should have taken effect by line 30", 22, coarseXAfter)
        assertEquals("fine x scroll should have taken effect by line 30", 7, fineXAfter)
        // The y = 0 write only touched t; v keeps advancing one pixel row per scanline, so line 32
        // is still drawing tile row 4. That is what keeps the status bar rows above it intact.
        assertEquals("vertical scroll must not be reloaded mid-frame", 4 shl 5, verticalAtLine32)
    }
}

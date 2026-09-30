package com.remag.aines

import com.remag.aines.emulator.Cartridge
import com.remag.aines.emulator.Ppu2c02
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Raster accuracy for the two things Super Mario Bros. 3 does to hold its status bar still: an MMC3
 * scanline IRQ, and a handler that rewrites the scroll and blanks the display *while the beam is
 * still drawing*. A whole line rendered from end-of-line state slices the picture up into glitch
 * rows just above the bar, so both the per-line clock count and the per-pixel commit have to be
 * right.
 */
class PpuRasterSplitTest {
    private val backdropColor = 0x0F
    private val solidColor = 0x16

    /**
     * NROM picture that is solid everywhere except tile columns 24..31 of nametable rows 3 and 4,
     * which are transparent. Where the gap shows up therefore says which part of the nametable the
     * raster was reading, and where it stops says how far the beam had got.
     */
    private fun nromPpu(): Ppu2c02 {
        val chr = ByteArray(8192)
        for (row in 0 until 8) {
            chr[row] = 0xFF.toByte()
            chr[row + 8] = 0xFF.toByte()
        }
        val header = byteArrayOf(
            0x4E, 0x45, 0x53, 0x1A,
            1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        )
        val ppu = Ppu2c02(Cartridge(header + ByteArray(16384) + chr))
        for (tileRow in 3..4) {
            for (col in 24 until 32) ppu.vram[tileRow * 32 + col] = 2
        }
        ppu.paletteRam[0] = backdropColor.toByte()
        ppu.paletteRam[3] = solidColor.toByte()
        ppu.writeRegister(0x2000, 0x00)
        ppu.writeRegister(0x2001, 0x1E)
        return ppu
    }

    private fun mmc3Ppu(): Pair<Ppu2c02, Cartridge> {
        val header = byteArrayOf(
            0x4E, 0x45, 0x53, 0x1A,
            2, 1, // 2x16KB PRG, 1x8KB CHR
            0x40, 0, 0, 0, 0, 0, 0, 0, 0, 0 // mapper 4
        )
        val cart = Cartridge(header + ByteArray(32768) + ByteArray(8192))
        val ppu = Ppu2c02(cart)
        ppu.writeRegister(0x2001, 0x18) // Rendering on: the MMC3 counts the beam's scanlines.
        return ppu to cart
    }

    /** Clocks full frames, running [action] after every dot, and reports the first IRQ it sees. */
    private fun Ppu2c02.runFrames(
        frames: Int,
        cart: Cartridge,
        action: Ppu2c02.() -> Unit = {},
    ): Pair<Int, Int> {
        var irqLine = -1
        var irqDot = -1
        for (i in 0 until frames * 262 * 341) {
            clock()
            if (irqLine < 0 && cart.irqRequested) {
                irqLine = scanline
                irqDot = dot
            }
            action()
        }
        return irqLine to irqDot
    }

    private fun colorAt(ppu: Ppu2c02, line: Int, x: Int): Int = ppu.frameBuffer[line * 256 + x]

    @Test
    fun blankingTheDisplayMidScanlineOnlyBlanksTheRestOfThatLine() {
        val ppu = nromPpu()
        // The way Super Mario Bros. 3 hides its status bar update: kill the display part of the way
        // down a line. The pixels the beam has already drawn must keep the background.
        for (i in 0 until 262 * 341) {
            ppu.clock()
            if (ppu.scanline == 20 && ppu.dot == 128) ppu.writeRegister(0x2001, 0x00)
        }

        assertEquals("line 20 should keep its background up to the write",
            ppu.systemPalette[solidColor], colorAt(ppu, 20, 127))
        assertEquals("line 20 should be blanked from the write onwards",
            ppu.systemPalette[backdropColor], colorAt(ppu, 20, 128))
        assertEquals("a line that starts with the display off should be blanked entirely",
            ppu.systemPalette[backdropColor], colorAt(ppu, 21, 0))
    }

    @Test
    fun scrollWriteMidScanlineOnlyAffectsThePixelsAfterIt() {
        val ppu = nromPpu()
        for (i in 0 until 262 * 341) {
            ppu.clock()
            // Line 24 is the first pixel row of tile row 3, whose right quarter is transparent from
            // pixel 192. Pointing v at tile row 0 at pixel 200 has to leave that gap in place and
            // only replace the pixels the beam has not reached yet.
            if (ppu.scanline == 24 && ppu.dot == 200) {
                ppu.writeRegister(0x2006, 0x00)
                ppu.writeRegister(0x2006, 0x00)
            }
        }

        assertEquals("the gap belongs to the scroll that was live when the beam passed it",
            ppu.systemPalette[backdropColor], colorAt(ppu, 24, 192))
        assertEquals("after the write the new scroll takes over on the same line",
            ppu.systemPalette[solidColor], colorAt(ppu, 24, 200))
        assertEquals("tile row 3 is solid left of its gap",
            ppu.systemPalette[solidColor], colorAt(ppu, 24, 100))
    }

    @Test
    fun mmc3CountsTwoHundredAndFortyOneScanlinesPerFrame() {
        val (ppu, cart) = mmc3Ppu()
        cart.writePrg(0xC000, 241) // latch
        cart.writePrg(0xC001, 0)   // request reload
        cart.writePrg(0xE001, 0)   // enable

        val (irqLine, irqDot) = ppu.runFrames(2, cart)

        // The load eats the first clock, so 241 clocks - the 240 visible lines plus the pre-render
        // line, which fetches for scanline 0 just like any visible line does - are exactly one
        // frame. Dropping the pre-render clock pushes the IRQ a whole scanline later.
        assertTrue("the IRQ should have fired within two frames", irqLine >= 0)
        assertEquals("the pre-render scanline must be counted", 0, irqLine)
        assertEquals("the counter is clocked at the end of a scanline", 260, irqDot)
    }

    @Test
    fun mmc3IrqArmedInVBlankLandsOnTheRequestedScanline() {
        val (ppu, cart) = mmc3Ppu()
        var armed = false
        val (irqLine, _) = ppu.runFrames(2, cart) {
            if (scanline == 251 && !armed) {
                cart.writePrg(0xC000, 192)
                cart.writePrg(0xC001, 0)
                cart.writePrg(0xE001, 0)
                armed = true
            }
        }

        assertTrue("the handler should be armed during VBlank", armed)
        // Latch 192 armed in VBlank means the handler runs on scanline 192, which is where Super
        // Mario Bros. 3 wants its status bar to start. Being a line short here is what put the bar
        // two lines below the row the game asked for.
        assertEquals("the IRQ should be asserted at the end of scanline 191", 191, irqLine)
    }
}

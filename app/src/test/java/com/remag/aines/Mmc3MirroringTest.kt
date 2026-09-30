package com.remag.aines

import com.remag.aines.emulator.Cartridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the MMC3 (iNES mapper 4) $A000 nametable-arrangement bit. MMC3's bit 0 is INVERTED relative
 * to what the iNES header bit 0 means: in this codebase mirrorVertical=true selects the "PPU A10"
 * arrangement (table and 1), which is what a header bit 0 of 1 means, but an MMC3 $A000 bit 0 of 1
 * selects the "PPU A11" arrangement (table ushr 1) and therefore maps to mirrorVertical=false.
 * Super Mario Bros. 3 writes $A000=1 for its levels; reading the bit like the header (as the older
 * tests here did) tears the title-screen band and scrambles the in-level status bar, so both
 * transitions are pinned here independently of any ROM execution.
 */
class Mmc3MirroringTest {
    /** Minimal valid mapper-4 cartridge: 32 KiB PRG (four 8 KiB banks), 8 KiB CHR, no mirroring bit set. */
    private fun mmc3Cartridge(): Cartridge {
        val prgChunks = 2 // 2 * 16 KiB = 32 KiB -> four 8 KiB banks
        val chrChunks = 1 // 1 * 8 KiB
        val header = byteArrayOf(
            0x4E, 0x45, 0x53, 0x1A, // "NES\x1A"
            prgChunks.toByte(), chrChunks.toByte(),
            0x40, // flags6: mapper low nibble = 4, bit0 (mirror) clear
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val rom = header +
            ByteArray(prgChunks * 16384) +
            ByteArray(chrChunks * 8192)
        return Cartridge(rom)
    }

    @Test
    fun mapperIsFour() {
        assertEquals(4, mmc3Cartridge().mapperId)
    }

    @Test
    fun a000Bit0SetSelectsTheA11Arrangement() {
        val cart = mmc3Cartridge()
        cart.writePrg(0xA000, 0x01)
        // MMC3 $A000 bit0=1 means CIRAM A10 is driven from PPU A11 ("table ushr 1"), which this
        // codebase spells mirrorVertical=false - the inverse of the iNES header bit 0.
        assertFalse("MMC3 \$A000 bit0=1 must select the A11 (table ushr 1) arrangement", cart.mirrorVertical)
    }

    @Test
    fun a000Bit0ClearSelectsTheA10Arrangement() {
        val cart = mmc3Cartridge()
        cart.writePrg(0xA000, 0x01) // start on the A11 arrangement
        cart.writePrg(0xA000, 0x00) // then switch to the A10 arrangement SMB1-style
        assertTrue("MMC3 \$A000 bit0=0 must select the A10 (table and 1) arrangement", cart.mirrorVertical)
    }
}

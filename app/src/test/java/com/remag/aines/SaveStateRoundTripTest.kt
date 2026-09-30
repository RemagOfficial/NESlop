package com.remag.aines

import com.remag.aines.emulator.Controller
import com.remag.aines.emulator.NesMachine
import com.remag.aines.save.SaveState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Save-state codec tests against the shipping File-based API: a snapshot must round-trip a
 * machine, a save must only restore into the exact ROM that produced it (the identity stored in
 * the file is checked by load()), and corrupt or foreign files must be rejected without leaving
 * the half-restored machine runnable-but-wrong.
 */
class SaveStateRoundTripTest {

    @JvmField
    @Rule
    val tempFolder = TemporaryFolder()

    private fun nesRom(prgFill: Byte, chrFill: Byte): ByteArray {
        // Minimal NROM: 1x16K PRG, 1x8K CHR, mapper 0.
        val rom = ByteArray(16 + 16384 + 8192)
        rom[0] = 0x4E; rom[1] = 0x45; rom[2] = 0x53; rom[3] = 0x1A
        rom[4] = 1; rom[5] = 1
        for (i in 0 until 16384) rom[16 + i] = prgFill
        for (i in 0 until 8192) rom[16 + 16384 + i] = chrFill
        return rom
    }

    @Test
    fun romIdentityDistinguishesGames() {
        val a = SaveState.romIdentity(nesRom(0x01, 0x02))
        val b = SaveState.romIdentity(nesRom(0x01, 0x02))
        val c = SaveState.romIdentity(nesRom(0x03, 0x02))
        assertEquals("same bytes must produce the same identity", a, b)
        assertFalse("different bytes must produce a different identity", a == c)
    }

    @Test
    fun snapshotRoundTripsThroughFile() {
        val rom = nesRom(0x40, 0x80.toByte())
        val source = NesMachine(rom)
        repeat(3) { source.stepFrame() }

        // Mutate one field per subsystem so a missing section cannot pass unnoticed.
        source.cpu.regA = 0x9A
        source.bus.cpuRam[0x300] = 0x77
        source.ppu.vramAddr = 0x1234
        source.ppu.vram[0x100] = 0x22
        source.ppu.paletteRam[4] = 0x0F.toByte()
        source.ppu.oam[7] = 0x55
        source.cartridge.prgRam[5] = 0x33
        source.apu.triPhase = 11
        source.apu.frameStep = 2
        source.controller1.buttonState = Controller.BUTTON_START

        val identity = SaveState.romIdentity(rom)
        val file = SaveState.saveFileFor(tempFolder.root, identity)
        assertTrue("save() must succeed", SaveState.save(file, source, identity, "test.nes"))

        val data = SaveState.load(file, identity)
        assertNotNull("load() must return the save for the matching identity", data)

        val target = NesMachine(rom)
        assertTrue(SaveState.restore(target, data!!))

        assertEquals(0x9A, target.cpu.regA)
        assertEquals(0x77.toByte(), target.bus.cpuRam[0x300])
        assertEquals(0x1234, target.ppu.vramAddr)
        assertEquals(0x22.toByte(), target.ppu.vram[0x100])
        assertEquals(0x0F.toByte(), target.ppu.paletteRam[4])
        assertEquals(0x55.toByte(), target.ppu.oam[7])
        assertEquals(0x33.toByte(), target.cartridge.prgRam[5])
        assertEquals(11, target.apu.triPhase)
        assertEquals(2, target.apu.frameStep)
        assertEquals(Controller.BUTTON_START, target.controller1.buttonState)
        assertArrayEquals(source.ppu.oam, target.ppu.oam)
        assertArrayEquals(source.bus.cpuRam, target.bus.cpuRam)
        assertArrayEquals(source.ppu.vram, target.ppu.vram)
        assertArrayEquals(source.cartridge.prgRam, target.cartridge.prgRam)
    }

    @Test
    fun restoredMachineKeepsEmulating() {
        // Snapshots are taken at frame boundaries, so the restored raster must continue cleanly.
        val rom = nesRom(0x40, 0x80.toByte())
        val source = NesMachine(rom)
        repeat(2) { source.stepFrame() }

        val identity = SaveState.romIdentity(rom)
        val file = SaveState.saveFileFor(tempFolder.root, identity)
        assertTrue(SaveState.save(file, source, identity, "test.nes"))

        val target = NesMachine(rom)
        assertTrue(SaveState.restore(target, SaveState.load(file, identity)!!))
        repeat(5) { target.stepFrame() } // must not throw
        assertNotNull(target.ppu.frameBuffer)
    }

    @Test
    fun saveFromOneGameIsNotLoadableIntoAnother() {
        val romA = nesRom(0x11, 0x22)
        val romB = nesRom(0x33, 0x44)
        val identityA = SaveState.romIdentity(romA)
        val identityB = SaveState.romIdentity(romB)

        val file = SaveState.saveFileFor(tempFolder.root, identityA)
        assertTrue(SaveState.save(file, NesMachine(romA), identityA, "gameA.nes"))

        // The UI keys every lookup by the RUNNING rom's identity, so game B never even sees the
        // file; and even pointed directly at the file, load() refuses a mismatched identity.
        assertNull(SaveState.load(SaveState.saveFileFor(tempFolder.root, identityB), identityB))
        assertNull("foreign save must not load", SaveState.load(file, identityB))
    }

    @Test
    fun corruptOrForeignFilesAreRejected() {
        val rom = nesRom(0x40, 0x80.toByte())
        val identity = SaveState.romIdentity(rom)
        val machine = NesMachine(rom)
        val file = SaveState.saveFileFor(tempFolder.root, identity)
        assertTrue(SaveState.save(file, machine, identity, "test.nes"))
        val data = file.readBytes()

        // Truncated stream must fail outright.
        assertFalse(SaveState.restore(machine, data.copyOfRange(0, data.size / 2)))
        // Wrong magic (foreign-format file) must be rejected.
        val badMagic = data.copyOf().apply { this[3] = 0x7F }
        assertFalse(SaveState.restore(machine, badMagic))
        // A missing file simply has no save.
        assertNull(SaveState.load(File(tempFolder.root, "nope.state"), identity))
    }
}

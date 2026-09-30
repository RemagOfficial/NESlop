package com.remag.aines.save

import android.content.Context
import com.remag.aines.emulator.NesMachine
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32

/**
 * Whole-machine snapshot ("save state") support for AINES.
 *
 * Saves are tied to the game they came from: every file carries a 64-bit identity derived from the
 * ROM bytes plus its size, and [load] refuses anything that does not match the running cartridge,
 * so a save made in one game can never be restored into another.
 *
 * The snapshot is taken at a frame boundary, so transient per-dot raster state (sprite 0 hit
 * pending, painted-pixel cursor, the mapper IRQ line that Bus.stepFrame re-derives every cycle) is
 * not stored; each of them is rebuilt within the first scanline of the next frame.
 */
object SaveState {
    private const val MAGIC = 0x41535431 // "AST1"
    private const val VERSION = 1

    fun romIdentity(romBytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(romBytes)
        return (crc.value shl 32) or (romBytes.size.toLong() and 0xFFFFFFFFL)
    }

    fun saveFileFor(context: Context, identity: Long): File =
        File(saveDir(context), "state_%016x.state".format(identity))

    fun saveFileFor(savesDir: File, identity: Long): File =
        File(savesDir, "state_%016x.state".format(identity))

    private fun saveDir(context: Context): File =
        File(context.filesDir, "saves").apply { mkdirs() }

    fun save(context: Context, machine: NesMachine, identity: Long, romName: String): Boolean =
        save(saveFileFor(context, identity), machine, identity, romName)

    /** File-first overload: the whole format lives here, so tests exercise the shipping codec. */
    fun save(file: File, machine: NesMachine, identity: Long, romName: String): Boolean {
        return try {
            DataOutputStream(file.outputStream().buffered()).use { out ->
                writeMachine(out, machine, identity, romName)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns the saved bytes if a file exists for [identity] and belongs to that exact ROM, or
     * null when there is no compatible save (missing file, foreign game, unsupported version).
     */
    fun load(context: Context, identity: Long): ByteArray? =
        load(saveFileFor(context, identity), identity)

    fun load(file: File, identity: Long): ByteArray? {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            DataInputStream(bytes.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return null
                if (input.readInt() != VERSION) return null
                if (input.readLong() != identity) return null
            }
            bytes
        } catch (e: Exception) {
            null
        }
    }

    fun restore(machine: NesMachine, data: ByteArray): Boolean {
        return try {
            DataInputStream(data.inputStream().buffered()).use { input ->
                readMachine(input, machine)
            }
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------- format

    private fun writeMachine(out: DataOutputStream, machine: NesMachine, identity: Long, romName: String) {
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeLong(identity)
        out.writeUTF(romName)
        writeBus(out, machine)
        writeCpu(out, machine)
        writePpu(out, machine)
        writeCartridge(out, machine)
        writeApu(out, machine)
        writeControllers(out, machine)
    }

    private fun readMachine(input: DataInputStream, machine: NesMachine): Boolean {
        if (input.readInt() != MAGIC) return false
        if (input.readInt() != VERSION) return false
        input.readLong() // Identity: verified by the caller holding the right machine.
        input.readUTF() // ROM name: informational only.
        readBus(input, machine)
        readCpu(input, machine)
        readPpu(input, machine)
        readCartridge(input, machine)
        readApu(input, machine)
        readControllers(input, machine)
        return true
    }

    // ---------------------------------------------------------------- sections

    private fun writeBus(out: DataOutputStream, m: NesMachine) {
        out.write(m.bus.cpuRam)
        out.writeInt(m.bus.openBus)
    }

    private fun readBus(input: DataInputStream, m: NesMachine) {
        input.readFully(m.bus.cpuRam)
        m.bus.openBus = input.readInt()
    }

    private fun writeCpu(out: DataOutputStream, m: NesMachine) {
        out.writeInt(m.cpu.regA)
        out.writeInt(m.cpu.regX)
        out.writeInt(m.cpu.regY)
        out.writeInt(m.cpu.regStkp)
        out.writeInt(m.cpu.regPc)
        out.writeInt(m.cpu.status)
        out.writeLong(m.cpu.cycles)
        out.writeBoolean(m.cpu.irqRequested)
        out.writeBoolean(m.cpu.nmiRequested)
    }

    private fun readCpu(input: DataInputStream, m: NesMachine) {
        m.cpu.regA = input.readInt()
        m.cpu.regX = input.readInt()
        m.cpu.regY = input.readInt()
        m.cpu.regStkp = input.readInt()
        m.cpu.regPc = input.readInt()
        m.cpu.status = input.readInt()
        m.cpu.cycles = input.readLong()
        m.cpu.irqRequested = input.readBoolean()
        m.cpu.nmiRequested = input.readBoolean()
    }

    private fun writePpu(out: DataOutputStream, m: NesMachine) {
        val p = m.ppu
        out.writeInt(p.vramAddr)
        out.writeInt(p.tramAddr)
        out.writeInt(p.fineX)
        out.writeBoolean(p.addressLatch)
        out.writeInt(p.ppuDataBuffer)
        out.writeInt(p.ppuCtrl)
        out.writeInt(p.ppuMask)
        out.writeInt(p.ppuStatus)
        out.writeInt(p.oamAddr)
        // Raster position is fixed at the frame boundary the snapshot is taken on.
        out.writeInt(-1) // dot
        out.writeInt(0) // scanline
        out.writeBoolean(false) // frameReady
        out.writeBoolean(false) // nmiRequested
        out.write(p.paletteRam)
        out.write(p.oam)
        out.write(p.vram)
    }

    private fun readPpu(input: DataInputStream, m: NesMachine) {
        val p = m.ppu
        p.vramAddr = input.readInt()
        p.tramAddr = input.readInt()
        p.fineX = input.readInt()
        p.addressLatch = input.readBoolean()
        p.ppuDataBuffer = input.readInt()
        p.ppuCtrl = input.readInt()
        p.ppuMask = input.readInt()
        p.ppuStatus = input.readInt()
        p.oamAddr = input.readInt()
        p.dot = input.readInt()
        p.scanline = input.readInt()
        p.frameReady = input.readBoolean()
        p.nmiRequested = input.readBoolean()
        input.readFully(p.paletteRam)
        input.readFully(p.oam)
        input.readFully(p.vram)
    }

    private fun writeCartridge(out: DataOutputStream, m: NesMachine) {
        val c = m.cartridge
        out.write(c.prgRam)
        if (c.isChrRam) out.write(c.chrRom)
        out.writeBoolean(c.mirrorVertical)
        out.writeInt(c.mmc3BankSelect)
        for (i in 0 until 8) out.writeInt(c.mmc3Registers[i])
        out.writeBoolean(c.mmc3PrgMode)
        out.writeBoolean(c.mmc3ChrMode)
        out.writeInt(c.mmc3IrqLatch)
        out.writeInt(c.mmc3IrqCounter)
        out.writeBoolean(c.mmc3IrqReload)
        out.writeBoolean(c.mmc3IrqEnabled)
    }

    private fun readCartridge(input: DataInputStream, m: NesMachine) {
        val c = m.cartridge
        input.readFully(c.prgRam)
        if (c.isChrRam) input.readFully(c.chrRom)
        c.mirrorVertical = input.readBoolean()
        c.mmc3BankSelect = input.readInt()
        for (i in 0 until 8) c.mmc3Registers[i] = input.readInt()
        c.mmc3PrgMode = input.readBoolean()
        c.mmc3ChrMode = input.readBoolean()
        c.mmc3IrqLatch = input.readInt()
        c.mmc3IrqCounter = input.readInt()
        c.mmc3IrqReload = input.readBoolean()
        c.mmc3IrqEnabled = input.readBoolean()
        // The IRQ output line is re-derived from the mapper state every cycle by stepFrame, so
        // starting it low after a restore cannot miss an assertion.
        c.irqRequested = false
    }

    private fun writeApu(out: DataOutputStream, m: NesMachine) {
        val a = m.apu
        out.write(a.registers)
        out.writeBoolean(a.irqRequested)
        out.writeBoolean(a.p1Enable)
        out.writeBoolean(a.p2Enable)
        out.writeBoolean(a.triEnable)
        out.writeBoolean(a.noiseEnable)
        out.writeInt(a.p1Duty)
        out.writeInt(a.p1Period)
        out.writeFloat(a.p1FloatPhase)
        out.writeInt(a.p1LengthCounter)
        out.writeBoolean(a.p1Halt)
        writeEnvelope(out, a.p1Envelope)
        writeSweep(out, a.p1Sweep)
        out.writeInt(a.p2Duty)
        out.writeInt(a.p2Period)
        out.writeFloat(a.p2FloatPhase)
        out.writeInt(a.p2LengthCounter)
        out.writeBoolean(a.p2Halt)
        writeEnvelope(out, a.p2Envelope)
        writeSweep(out, a.p2Sweep)
        out.writeInt(a.triPeriod)
        out.writeInt(a.triTimer)
        out.writeInt(a.triPhase)
        out.writeInt(a.triLengthCounter)
        out.writeBoolean(a.triHalt)
        out.writeInt(a.triLinearReload)
        out.writeInt(a.triLinearCounter)
        out.writeBoolean(a.triLinearReloadFlag)
        out.writeBoolean(a.triControlFlag)
        out.writeInt(a.noisePeriod)
        out.writeInt(a.noiseTimer)
        out.writeInt(a.noiseShiftReg)
        out.writeBoolean(a.noiseMode)
        out.writeInt(a.noiseLengthCounter)
        out.writeBoolean(a.noiseHalt)
        writeEnvelope(out, a.noiseEnvelope)
        out.writeInt(a.frameCounterCycles)
        out.writeInt(a.frameStep)
        out.writeBoolean(a.apuClockEven)
        out.writeInt(a.cycleAccumulator)
        out.writeFloat(a.lastX)
        out.writeFloat(a.filterOut)
        out.writeFloat(a.lpOut)
    }

    private fun readApu(input: DataInputStream, m: NesMachine) {
        val a = m.apu
        input.readFully(a.registers)
        a.irqRequested = input.readBoolean()
        a.p1Enable = input.readBoolean()
        a.p2Enable = input.readBoolean()
        a.triEnable = input.readBoolean()
        a.noiseEnable = input.readBoolean()
        a.p1Duty = input.readInt()
        a.p1Period = input.readInt()
        a.p1FloatPhase = input.readFloat()
        a.p1LengthCounter = input.readInt()
        a.p1Halt = input.readBoolean()
        readEnvelope(input, a.p1Envelope)
        readSweep(input, a.p1Sweep)
        a.p2Duty = input.readInt()
        a.p2Period = input.readInt()
        a.p2FloatPhase = input.readFloat()
        a.p2LengthCounter = input.readInt()
        a.p2Halt = input.readBoolean()
        readEnvelope(input, a.p2Envelope)
        readSweep(input, a.p2Sweep)
        a.triPeriod = input.readInt()
        a.triTimer = input.readInt()
        a.triPhase = input.readInt()
        a.triLengthCounter = input.readInt()
        a.triHalt = input.readBoolean()
        a.triLinearReload = input.readInt()
        a.triLinearCounter = input.readInt()
        a.triLinearReloadFlag = input.readBoolean()
        a.triControlFlag = input.readBoolean()
        a.noisePeriod = input.readInt()
        a.noiseTimer = input.readInt()
        a.noiseShiftReg = input.readInt()
        a.noiseMode = input.readBoolean()
        a.noiseLengthCounter = input.readInt()
        a.noiseHalt = input.readBoolean()
        readEnvelope(input, a.noiseEnvelope)
        a.frameCounterCycles = input.readInt()
        a.frameStep = input.readInt()
        a.apuClockEven = input.readBoolean()
        a.cycleAccumulator = input.readInt()
        a.lastX = input.readFloat()
        a.filterOut = input.readFloat()
        a.lpOut = input.readFloat()
    }

    private fun writeEnvelope(out: DataOutputStream, e: com.remag.aines.emulator.Envelope) {
        out.writeBoolean(e.startFlag)
        out.writeBoolean(e.useConstantVolume)
        out.writeBoolean(e.loop)
        out.writeInt(e.volumeReload)
        out.writeInt(e.divider)
        out.writeInt(e.decayLevel)
    }

    private fun readEnvelope(input: DataInputStream, e: com.remag.aines.emulator.Envelope) {
        e.startFlag = input.readBoolean()
        e.useConstantVolume = input.readBoolean()
        e.loop = input.readBoolean()
        e.volumeReload = input.readInt()
        e.divider = input.readInt()
        e.decayLevel = input.readInt()
    }

    private fun writeSweep(out: DataOutputStream, s: com.remag.aines.emulator.Sweep) {
        out.writeBoolean(s.enabled)
        out.writeInt(s.period)
        out.writeBoolean(s.negate)
        out.writeInt(s.shift)
        out.writeInt(s.divider)
        out.writeBoolean(s.reloadFlag)
    }

    private fun readSweep(input: DataInputStream, s: com.remag.aines.emulator.Sweep) {
        s.enabled = input.readBoolean()
        s.period = input.readInt()
        s.negate = input.readBoolean()
        s.shift = input.readInt()
        s.divider = input.readInt()
        s.reloadFlag = input.readBoolean()
    }

    private fun writeControllers(out: DataOutputStream, m: NesMachine) {
        out.writeInt(m.controller1.buttonState)
        out.writeInt(m.controller1.shiftRegister)
        out.writeBoolean(m.controller1.strobe)
        out.writeInt(m.controller2.buttonState)
        out.writeInt(m.controller2.shiftRegister)
        out.writeBoolean(m.controller2.strobe)
    }

    private fun readControllers(input: DataInputStream, m: NesMachine) {
        m.controller1.buttonState = input.readInt()
        m.controller1.shiftRegister = input.readInt()
        m.controller1.strobe = input.readBoolean()
        m.controller2.buttonState = input.readInt()
        m.controller2.shiftRegister = input.readInt()
        m.controller2.strobe = input.readBoolean()
    }
}

package com.remag.aines.emulator

class Cartridge(romBytes: ByteArray) {
    val prgRom: ByteArray
    val chrRom: ByteArray
    val mapperId: Int
    var mirrorVertical: Boolean
    val hasBatteryRam: Boolean
    val isChrRam: Boolean

    // PRG RAM (8KB)
    val prgRam = ByteArray(8192)

    // MMC3 State (Mapper 4). `internal` so the save-state codec can snapshot them without adding
    // per-field accessors; nothing outside the emulator package touches them directly.
    internal var mmc3BankSelect = 0
    internal val mmc3Registers = IntArray(8)
    internal var mmc3PrgMode = false
    internal var mmc3ChrMode = false
    internal var mmc3IrqLatch = 0
    internal var mmc3IrqCounter = 0
    internal var mmc3IrqReload = false
    internal var mmc3IrqEnabled = false
    var irqRequested = false

    // Diagnostic hook: observes every MMC3 register write. Never assigned by shipping code, so it
    // costs one null check per write; trace harnesses set it to record bank/mirror activity.
    var onBankWrite: ((address: Int, value: Int) -> Unit)? = null

    private val prgBankSize = 8192
    private val chrBankSize = 1024
    private val numPrgBanks: Int
    private val numChrBanks: Int

    init {
        require(romBytes.size >= 16) { "ROM file too small to contain iNES header" }
        
        // Check magic bytes "NES\x1A"
        require(
            romBytes[0] == 0x4E.toByte() &&
            romBytes[1] == 0x45.toByte() &&
            romBytes[2] == 0x53.toByte() &&
            romBytes[3] == 0x1A.toByte()
        ) { "Invalid iNES ROM header magic bytes" }

        val prgRomChunks = romBytes[4].toInt() and 0xFF
        val chrRomChunks = romBytes[5].toInt() and 0xFF
        val flags6 = romBytes[6].toInt() and 0xFF
        val flags7 = romBytes[7].toInt() and 0xFF

        mirrorVertical = (flags6 and 0x01) != 0
        hasBatteryRam = (flags6 and 0x02) != 0
        val hasTrainer = (flags6 and 0x04) != 0

        mapperId = ((flags7 and 0xF0) or ((flags6 ushr 4) and 0x0F))

        var offset = 16
        if (hasTrainer) {
            offset += 512
        }

        val prgRomSize = prgRomChunks * 16384
        require(offset + prgRomSize <= romBytes.size) { "ROM file truncated at PRG ROM" }
        prgRom = romBytes.copyOfRange(offset, offset + prgRomSize)
        offset += prgRomSize

        val chrRomSize = chrRomChunks * 8192
        if (chrRomSize > 0) {
            require(offset + chrRomSize <= romBytes.size) { "ROM file truncated at CHR ROM" }
            chrRom = romBytes.copyOfRange(offset, offset + chrRomSize)
            isChrRam = false
        } else {
            chrRom = ByteArray(8192)
            isChrRam = true
        }

        numPrgBanks = prgRom.size / prgBankSize
        numChrBanks = chrRom.size / chrBankSize
    }

    fun writePrg(address: Int, value: Int) {
        val addr = address and 0xFFFF
        val v = value and 0xFF
        if (addr in 0x6000..0x7FFF) {
            prgRam[addr - 0x6000] = v.toByte()
            return
        }
        if (addr < 0x8000) return

        if (mapperId == 4) {
            onBankWrite?.invoke(addr, v)
            when (addr and 0xE001) {
                0x8000 -> {
                    mmc3BankSelect = v and 0x07
                    mmc3PrgMode = (v and 0x40) != 0
                    mmc3ChrMode = (v and 0x80) != 0
                }
                0x8001 -> {
                    mmc3Registers[mmc3BankSelect] = v
                }
                0xA000 -> {
                    // MMC3's mirror bit is INVERTED relative to the iNES header bit 0. Here bit 0 = 0
                    // drives CIRAM A10 from PPU A10 (this codebase's mirrorVertical=true / "table and 1"
                    // arrangement) and bit 0 = 1 drives it from PPU A11 ("table ushr 1"). Super Mario
                    // Bros. 3 writes $A000=1 for its levels, so it needs the A11 arrangement; treating
                    // the bit like the header (set => vertical) scrambles the status-bar nametable.
                    mirrorVertical = (v and 0x01) == 0
                }
                0xA001 -> {
                    // PRG RAM protect
                }
                0xC000 -> {
                    mmc3IrqLatch = v
                    irqRequested = false // Writing the latch clears the pending IRQ line
                }
                0xC001 -> {
                    mmc3IrqReload = true
                    irqRequested = false
                }
                0xE000 -> {
                    mmc3IrqEnabled = false
                    irqRequested = false
                }
                0xE001 -> {
                    mmc3IrqEnabled = true
                }
            }
        }
    }

    fun readPrg(address: Int): Int? {
        val addr = address and 0xFFFF
        if (addr in 0x6000..0x7FFF) {
            return prgRam[addr - 0x6000].toInt() and 0xFF
        }
        if (addr < 0x8000) return null

        if (mapperId == 0) {
            val mappedAddr = if (prgRom.size == 16384) {
                (addr - 0x8000) and 0x3FFF
            } else {
                (addr - 0x8000) and 0x7FFF
            }
            return prgRom[mappedAddr].toInt() and 0xFF
        } else if (mapperId == 4) {
            val bank8k = (addr - 0x8000) / 8192
            val offset = (addr - 0x8000) % 8192
            val physicalBank = getMmc3PrgBank(bank8k)
            val physicalAddress = (physicalBank * 8192 + offset) % prgRom.size
            return prgRom[physicalAddress].toInt() and 0xFF
        }
        return null
    }

    private fun getMmc3PrgBank(bank8k: Int): Int {
        // MMC3 only ever looks at the low 6 bits of the PRG bank registers.
        val r6 = mmc3Registers[6] and 0x3F
        val r7 = mmc3Registers[7] and 0x3F
        val lastBank = maxOf(0, numPrgBanks - 1)
        val secondLastBank = maxOf(0, numPrgBanks - 2)

        return if (!mmc3PrgMode) {
            when (bank8k) {
                0 -> r6
                1 -> r7
                2 -> secondLastBank
                else -> lastBank
            }
        } else {
            when (bank8k) {
                0 -> secondLastBank
                1 -> r7
                2 -> r6
                else -> lastBank
            }
        }
    }

    fun readChr(address: Int): Int {
        val addr = address and 0x1FFF
        if (isChrRam) {
            return chrRom[addr].toInt() and 0xFF
        }
        if (mapperId == 0) {
            return chrRom[addr].toInt() and 0xFF
        } else if (mapperId == 4) {
            val bank1k = addr / 1024
            val offset = addr % 1024
            val physicalBank = getMmc3ChrBank(bank1k)
            val physicalAddress = (physicalBank * 1024 + offset) % chrRom.size
            return chrRom[physicalAddress].toInt() and 0xFF
        }
        return chrRom[addr].toInt() and 0xFF
    }

    private fun getMmc3ChrBank(bank1k: Int): Int {
        val r0 = mmc3Registers[0] and 0xFE
        val r1 = mmc3Registers[1] and 0xFE
        val r2 = mmc3Registers[2]
        val r3 = mmc3Registers[3]
        val r4 = mmc3Registers[4]
        val r5 = mmc3Registers[5]

        return if (!mmc3ChrMode) {
            when (bank1k) {
                0 -> r0
                1 -> r0 + 1
                2 -> r1
                3 -> r1 + 1
                4 -> r2
                5 -> r3
                6 -> r4
                else -> r5
            }
        } else {
            when (bank1k) {
                0 -> r2
                1 -> r3
                2 -> r4
                3 -> r5
                4 -> r0
                5 -> r0 + 1
                6 -> r1
                7 -> r1 + 1
                else -> 0
            }
        }
    }

    fun writeChr(address: Int, value: Int) {
        if (isChrRam) {
            val addr = address and 0x1FFF
            chrRom[addr] = value.toByte()
        }
    }

    /**
     * Diagnostic snapshot of the MMC3 state: irq counter, latch, reload flag, enable flag, the eight
     * bank registers, then the chr/prg mode bits. Only read by trace harnesses.
     */
    fun mmc3DebugState(): IntArray =
        intArrayOf(
            mmc3IrqCounter, mmc3IrqLatch, if (mmc3IrqReload) 1 else 0, if (mmc3IrqEnabled) 1 else 0,
            mmc3Registers[0], mmc3Registers[1], mmc3Registers[2], mmc3Registers[3],
            mmc3Registers[4], mmc3Registers[5], mmc3Registers[6], mmc3Registers[7],
            if (mmc3ChrMode) 1 else 0, if (mmc3PrgMode) 1 else 0,
        )

    fun clockIrqScanline() {
        if (mapperId != 4) return
        // The counter keeps running while the IRQ is disabled, it only stops signalling.
        if (mmc3IrqCounter == 0 || mmc3IrqReload) {
            mmc3IrqCounter = mmc3IrqLatch
            mmc3IrqReload = false
        } else {
            mmc3IrqCounter--
        }
        // A latch of 0 means the counter reaches 0 on every scanline, i.e. an IRQ per line.
        if (mmc3IrqCounter == 0 && mmc3IrqEnabled) {
            irqRequested = true
        }
    }
}

package com.remag.aines.emulator

class Bus {
    val cpuRam = ByteArray(2048)
    var cartridge: Cartridge? = null
    var ppu: Ppu2c02? = null
    var apu: Apu2a03? = null
    var controller1: Controller? = null
    var controller2: Controller? = null

    // Open bus emulation value
    var openBus: Int = 0

    fun read(address: Int, readOnly: Boolean = false): Int {
        val addr = address and 0xFFFF
        val value = if (addr < 0x2000) {
            cpuRam[addr and 0x07FF].toInt() and 0xFF
        } else if (addr < 0x4000) {
            ppu?.readRegister(0x2000 or (addr and 0x0007), readOnly, openBus) ?: openBus
        } else if (addr < 0x4020) {
            when (addr) {
                0x4015 -> apu?.readRegister(addr) ?: openBus
                0x4016 -> controller1?.read() ?: openBus
                0x4017 -> controller2?.read() ?: openBus
                else -> openBus
            }
        } else {
            cartridge?.readPrg(addr) ?: openBus
        }
        if (!readOnly && addr != 0x4015) {
            openBus = value
        }
        return value
    }

    fun write(address: Int, value: Int) {
        val addr = address and 0xFFFF
        val byteVal = value and 0xFF
        openBus = byteVal
        if (addr < 0x2000) {
            cpuRam[addr and 0x07FF] = byteVal.toByte()
        } else if (addr < 0x4000) {
            ppu?.writeRegister(0x2000 or (addr and 0x0007), byteVal)
        } else if (addr < 0x4020) {
            when (addr) {
                0x4014 -> {
                    val dmaPage = byteVal shl 8
                    ppu?.let { p ->
                        // The beam may be in the middle of a visible line, and the sprites it is
                        // about to draw come from the old table.
                        p.flushRaster()
                        for (i in 0 until 256) {
                            val byte = read(dmaPage + i)
                            p.oam[(p.oamAddr + i) and 0xFF] = byte.toByte()
                        }
                    }
                }
                0x4016 -> {
                    controller1?.write(byteVal)
                    controller2?.write(byteVal)
                }
                else -> {
                    apu?.writeRegister(addr, byteVal)
                }
            }
        } else {
            // A mapper write can swap the CHR the raster is reading from, so the pixels already
            // drawn on this line have to be committed with the banks that were active then.
            ppu?.flushRaster()
            cartridge?.writePrg(addr, byteVal)
        }
    }

    fun read16(address: Int): Int {
        val lo = read(address)
        val hi = read(address + 1)
        return (hi shl 8) or lo
    }
}

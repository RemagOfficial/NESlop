package com.remag.aines.emulator

class Cpu6502(val bus: Bus) {
    var regA: Int = 0
    var regX: Int = 0
    var regY: Int = 0
    var regStkp: Int = 0xFD
    var regPc: Int = 0xC000
    var status: Int = 0x24 // U and I flags set by default

    var cycles: Long = 0

    companion object {
        const val FLAG_C = 0x01
        const val FLAG_Z = 0x02
        const val FLAG_I = 0x04
        const val FLAG_D = 0x08
        const val FLAG_B = 0x10
        const val FLAG_U = 0x20
        const val FLAG_V = 0x40
        const val FLAG_N = 0x80
    }

    fun getFlag(flag: Int): Boolean = (status and flag) != 0

    fun setFlag(flag: Int, v: Boolean) {
        status = if (v) status or flag else status and flag.inv()
    }

    fun reset() {
        regA = 0
        regX = 0
        regY = 0
        regStkp = 0xFD
        status = 0x24
        regPc = bus.read16(0xFFFC)
    }

    var irqRequested: Boolean = false
    var nmiRequested: Boolean = false

    // Diagnostic hooks, never assigned by shipping code so they cost one null check each.
    // onInterruptTaken receives 'N'/'I' and the vector the CPU jumped to; onIrqDeferred fires once
    // per instruction boundary where an IRQ was pending but masked by the I flag.
    var onInterruptTaken: ((kind: Char, vectorPc: Int) -> Unit)? = null
    var onIrqDeferred: (() -> Unit)? = null

    fun irq() {
        if (!getFlag(FLAG_I)) {
            push16(regPc)
            push((status and FLAG_B.inv()) or FLAG_U)
            setFlag(FLAG_I, true)
            regPc = bus.read16(0xFFFE)
            irqRequested = false
            onInterruptTaken?.invoke('I', regPc)
        }
    }

    fun nmi() {
        push16(regPc)
        push((status and FLAG_B.inv()) or FLAG_U)
        setFlag(FLAG_I, true)
        regPc = bus.read16(0xFFFA)
        nmiRequested = false
        onInterruptTaken?.invoke('N', regPc)
    }

    private fun pollInterrupts(): Boolean {
        if (nmiRequested) {
            nmi()
            return true
        } else if (irqRequested) {
            if (getFlag(FLAG_I)) {
                onIrqDeferred?.invoke()
            } else {
                irq()
                return true
            }
        }
        return false
    }

    private fun push(value: Int) {
        bus.write(0x0100 or regStkp, value)
        regStkp = (regStkp - 1) and 0xFF
    }

    private fun pull(): Int {
        regStkp = (regStkp + 1) and 0xFF
        return bus.read(0x0100 or regStkp)
    }

    private fun push16(value: Int) {
        push((value shr 8) and 0xFF)
        push(value and 0xFF)
    }

    private fun pull16(): Int {
        val lo = pull()
        val hi = pull()
        return (hi shl 8) or lo
    }

    private fun updateZeroAndNegativeFlags(v: Int) {
        setFlag(FLAG_Z, (v and 0xFF) == 0)
        setFlag(FLAG_N, (v and 0x80) != 0)
    }

    private fun readPc(): Int {
        val v = bus.read(regPc)
        regPc = (regPc + 1) and 0xFFFF
        return v
    }

    fun step(): Int {
        val opcode = readPc()
        cycles++
        var cyc = executeOpcode(opcode)
        // Acknowledging an interrupt costs seven cycles on a 6502: two dummy reads plus the
        // register and program counter pushes. Games that drive raster effects from an IRQ count
        // on that delay - without it the handler's writes reach the picture more than two dots
        // sooner per cycle, which is enough to land them while the beam is still drawing.
        if (pollInterrupts()) cyc += 7
        return cyc
    }

    private fun executeOpcode(op: Int): Int {
        return when (op) {
            // LDA
            0xA9 -> { regA = fetchImmediate(); updateZeroAndNegativeFlags(regA); 2 }
            0xA5 -> { regA = fetchZeroPage(); updateZeroAndNegativeFlags(regA); 3 }
            0xB5 -> { regA = fetchZeroPageX(); updateZeroAndNegativeFlags(regA); 4 }
            0xAD -> { regA = fetchAbsolute(); updateZeroAndNegativeFlags(regA); 4 }
            0xBD -> { val res = fetchAbsoluteX(); regA = res.first; updateZeroAndNegativeFlags(regA); if (res.second) 5 else 4 }
            0xB9 -> { val res = fetchAbsoluteY(); regA = res.first; updateZeroAndNegativeFlags(regA); if (res.second) 5 else 4 }
            0xA1 -> { regA = fetchIndirectX(); updateZeroAndNegativeFlags(regA); 6 }
            0xB1 -> { val res = fetchIndirectY(); regA = res.first; updateZeroAndNegativeFlags(regA); if (res.second) 6 else 5 }

            // LDX
            0xA2 -> { regX = fetchImmediate(); updateZeroAndNegativeFlags(regX); 2 }
            0xA6 -> { regX = fetchZeroPage(); updateZeroAndNegativeFlags(regX); 3 }
            0xB6 -> { regX = fetchZeroPageY(); updateZeroAndNegativeFlags(regX); 4 }
            0xAE -> { regX = fetchAbsolute(); updateZeroAndNegativeFlags(regX); 4 }
            0xBE -> { val res = fetchAbsoluteY(); regX = res.first; updateZeroAndNegativeFlags(regX); if (res.second) 5 else 4 }

            // LDY
            0xA0 -> { regY = fetchImmediate(); updateZeroAndNegativeFlags(regY); 2 }
            0xA4 -> { regY = fetchZeroPage(); updateZeroAndNegativeFlags(regY); 3 }
            0xB4 -> { regY = fetchZeroPageX(); updateZeroAndNegativeFlags(regY); 4 }
            0xAC -> { regY = fetchAbsolute(); updateZeroAndNegativeFlags(regY); 4 }
            0xBC -> { val res = fetchAbsoluteX(); regY = res.first; updateZeroAndNegativeFlags(regY); if (res.second) 5 else 4 }

            // STA
            0x85 -> { bus.write(getZeroPageAddress(), regA); 3 }
            0x95 -> { bus.write(getZeroPageXAddress(), regA); 4 }
            0x8D -> { bus.write(getAbsoluteAddress(), regA); 4 }
            0x9D -> {
                val base = getAbsoluteAddress()
                val addr = (base + regX) and 0xFFFF
                val dummyAddr = (base and 0xFF00) or ((base + regX) and 0xFF)
                bus.read(dummyAddr)
                bus.write(addr, regA)
                5
            }
            0x99 -> {
                val base = getAbsoluteAddress()
                val addr = (base + regY) and 0xFFFF
                val dummyAddr = (base and 0xFF00) or ((base + regY) and 0xFF)
                bus.read(dummyAddr)
                bus.write(addr, regA)
                5
            }
            0x81 -> { bus.write(getIndirectXAddress(), regA); 6 }
            0x91 -> { bus.write(getIndirectYAddress(), regA); 6 }

            // STX, STY
            0x86 -> { bus.write(getZeroPageAddress(), regX); 3 }
            0x96 -> { bus.write(getZeroPageYAddress(), regX); 4 }
            0x8E -> { bus.write(getAbsoluteAddress(), regX); 4 }
            0x84 -> { bus.write(getZeroPageAddress(), regY); 3 }
            0x94 -> { bus.write(getZeroPageXAddress(), regY); 4 }
            0x8C -> { bus.write(getAbsoluteAddress(), regY); 4 }

            // Transfers & Stack
            0xAA -> { regX = regA; updateZeroAndNegativeFlags(regX); 2 }
            0xA8 -> { regY = regA; updateZeroAndNegativeFlags(regY); 2 }
            0x8A -> { regA = regX; updateZeroAndNegativeFlags(regA); 2 }
            0x98 -> { regA = regY; updateZeroAndNegativeFlags(regA); 2 }
            0xBA -> { regX = regStkp; updateZeroAndNegativeFlags(regX); 2 }
            0x9A -> { regStkp = regX; 2 }

            // Increments & Decrements
            0xE8 -> { regX = (regX + 1) and 0xFF; updateZeroAndNegativeFlags(regX); 2 }
            0xC8 -> { regY = (regY + 1) and 0xFF; updateZeroAndNegativeFlags(regY); 2 }
            0xCA -> { regX = (regX - 1) and 0xFF; updateZeroAndNegativeFlags(regX); 2 }
            0x88 -> { regY = (regY - 1) and 0xFF; updateZeroAndNegativeFlags(regY); 2 }

            0xE6 -> { val addr = getZeroPageAddress(); val oldV = bus.read(addr); bus.write(addr, oldV); val v = (oldV + 1) and 0xFF; bus.write(addr, v); updateZeroAndNegativeFlags(v); 5 }
            0xF6 -> { val addr = getZeroPageXAddress(); val oldV = bus.read(addr); bus.write(addr, oldV); val v = (oldV + 1) and 0xFF; bus.write(addr, v); updateZeroAndNegativeFlags(v); 6 }
            0xEE -> { val addr = getAbsoluteAddress(); val oldV = bus.read(addr); bus.write(addr, oldV); val v = (oldV + 1) and 0xFF; bus.write(addr, v); updateZeroAndNegativeFlags(v); 6 }
            0xFE -> { val addr = getAbsoluteXAddress(); val oldV = bus.read(addr); bus.write(addr, oldV); val v = (oldV + 1) and 0xFF; bus.write(addr, v); updateZeroAndNegativeFlags(v); 7 }
            0xC6 -> { val addr = getZeroPageAddress(); val oldV = bus.read(addr); bus.write(addr, oldV); val v = (oldV - 1) and 0xFF; bus.write(addr, v); updateZeroAndNegativeFlags(v); 5 }
            0xD6 -> { val addr = getZeroPageXAddress(); val oldV = bus.read(addr); bus.write(addr, oldV); val v = (oldV - 1) and 0xFF; bus.write(addr, v); updateZeroAndNegativeFlags(v); 6 }
            0xCE -> { val addr = getAbsoluteAddress(); val oldV = bus.read(addr); bus.write(addr, oldV); val v = (oldV - 1) and 0xFF; bus.write(addr, v); updateZeroAndNegativeFlags(v); 6 }
            0xDE -> { val addr = getAbsoluteXAddress(); val oldV = bus.read(addr); bus.write(addr, oldV); val v = (oldV - 1) and 0xFF; bus.write(addr, v); updateZeroAndNegativeFlags(v); 7 }

            // Jumps & Subroutines
            0x4C -> { regPc = getAbsoluteAddress(); 3 }
            0x6C -> {
                val ptr = getAbsoluteAddress()
                val lo = bus.read(ptr)
                val hi = bus.read(if ((ptr and 0x00FF) == 0x00FF) ptr and 0xFF00 else (ptr + 1) and 0xFFFF)
                regPc = (hi shl 8) or lo
                5
            }
            0x20 -> {
                val target = getAbsoluteAddress()
                push16((regPc - 1) and 0xFFFF)
                regPc = target
                6
            }
            0x60 -> { regPc = (pull16() + 1) and 0xFFFF; 6 }
            0x40 -> { status = (pull() and FLAG_B.inv()) or FLAG_U; regPc = pull16() and 0xFFFF; 6 }

            // Branches
            0xF0 -> branch(getFlag(FLAG_Z))
            0xD0 -> branch(!getFlag(FLAG_Z))
            0x50 -> branch(!getFlag(FLAG_V))
            0x70 -> branch(getFlag(FLAG_V))
            0x10 -> branch(!getFlag(FLAG_N))
            0x30 -> branch(getFlag(FLAG_N))
            0x90 -> branch(!getFlag(FLAG_C))
            0xB0 -> branch(getFlag(FLAG_C))

            // Comparisons
            0xC9 -> { compare(regA, fetchImmediate()); 2 }
            0xC5 -> { compare(regA, fetchZeroPage()); 3 }
            0xD5 -> { compare(regA, fetchZeroPageX()); 4 }
            0xCD -> { compare(regA, fetchAbsolute()); 4 }
            0xDD -> { compare(regA, fetchAbsoluteX().first); 4 }
            0xD9 -> { compare(regA, fetchAbsoluteY().first); 4 }
            0xC1 -> { compare(regA, fetchIndirectX()); 6 }
            0xD1 -> { compare(regA, fetchIndirectY().first); 5 }

            0xE0 -> { compare(regX, fetchImmediate()); 2 }
            0xE4 -> { compare(regX, fetchZeroPage()); 3 }
            0xEC -> { compare(regX, fetchAbsolute()); 4 }

            0xC0 -> { compare(regY, fetchImmediate()); 2 }
            0xC4 -> { compare(regY, fetchZeroPage()); 3 }
            0xCC -> { compare(regY, fetchAbsolute()); 4 }

            // Arithmetic
            0x69 -> { addWithCarry(fetchImmediate()); 2 }
            0x65 -> { addWithCarry(fetchZeroPage()); 3 }
            0x75 -> { addWithCarry(fetchZeroPageX()); 4 }
            0x6D -> { addWithCarry(fetchAbsolute()); 4 }
            0x7D -> { addWithCarry(fetchAbsoluteX().first); 4 }
            0x79 -> { addWithCarry(fetchAbsoluteY().first); 4 }
            0x61 -> { addWithCarry(fetchIndirectX()); 6 }
            0x71 -> { addWithCarry(fetchIndirectY().first); 5 }

            0xE9, 0xEB -> { subtractWithCarry(fetchImmediate()); 2 }
            0xE5 -> { subtractWithCarry(fetchZeroPage()); 3 }
            0xF5 -> { subtractWithCarry(fetchZeroPageX()); 4 }
            0xED -> { subtractWithCarry(fetchAbsolute()); 4 }
            0xFD -> { subtractWithCarry(fetchAbsoluteX().first); 4 }
            0xF9 -> { subtractWithCarry(fetchAbsoluteY().first); 4 }
            0xE1 -> { subtractWithCarry(fetchIndirectX()); 6 }
            0xF1 -> { subtractWithCarry(fetchIndirectY().first); 5 }

            // Logic
            0x29 -> { logicOp(regA and fetchImmediate()); 2 }
            0x25 -> { logicOp(regA and fetchZeroPage()); 3 }
            0x35 -> { logicOp(regA and fetchZeroPageX()); 4 }
            0x2D -> { logicOp(regA and fetchAbsolute()); 4 }
            0x3D -> { logicOp(regA and fetchAbsoluteX().first); 4 }
            0x39 -> { logicOp(regA and fetchAbsoluteY().first); 4 }
            0x21 -> { logicOp(regA and fetchIndirectX()); 6 }
            0x31 -> { logicOp(regA and fetchIndirectY().first); 5 }

            0x09 -> { logicOp(regA or fetchImmediate()); 2 }
            0x05 -> { logicOp(regA or fetchZeroPage()); 3 }
            0x15 -> { logicOp(regA or fetchZeroPageX()); 4 }
            0x0D -> { logicOp(regA or fetchAbsolute()); 4 }
            0x1D -> { logicOp(regA or fetchAbsoluteX().first); 4 }
            0x19 -> { logicOp(regA or fetchAbsoluteY().first); 4 }
            0x01 -> { logicOp(regA or fetchIndirectX()); 6 }
            0x11 -> { logicOp(regA or fetchIndirectY().first); 5 }

            0x49 -> { logicOp(regA xor fetchImmediate()); 2 }
            0x45 -> { logicOp(regA xor fetchZeroPage()); 3 }
            0x55 -> { logicOp(regA xor fetchZeroPageX()); 4 }
            0x4D -> { logicOp(regA xor fetchAbsolute()); 4 }
            0x5D -> { logicOp(regA xor fetchAbsoluteX().first); 4 }
            0x59 -> { logicOp(regA xor fetchAbsoluteY().first); 4 }
            0x41 -> { logicOp(regA xor fetchIndirectX()); 6 }
            0x51 -> { logicOp(regA xor fetchIndirectY().first); 5 }

            0x24 -> { bitTest(fetchZeroPage()); 3 }
            0x2C -> { bitTest(fetchAbsolute()); 4 }

            // Shifts & Rotates
            0x0A -> { regA = shiftLeft(regA); 2 }
            0x06 -> { val addr = getZeroPageAddress(); bus.write(addr, shiftLeft(bus.read(addr))); 5 }
            0x16 -> { val addr = getZeroPageXAddress(); bus.write(addr, shiftLeft(bus.read(addr))); 6 }
            0x0E -> { val addr = getAbsoluteAddress(); bus.write(addr, shiftLeft(bus.read(addr))); 6 }
            0x1E -> { val addr = getAbsoluteXAddress(); bus.write(addr, shiftLeft(bus.read(addr))); 7 }

            0x4A -> { regA = shiftRight(regA); 2 }
            0x46 -> { val addr = getZeroPageAddress(); bus.write(addr, shiftRight(bus.read(addr))); 5 }
            0x56 -> { val addr = getZeroPageXAddress(); bus.write(addr, shiftRight(bus.read(addr))); 6 }
            0x4E -> { val addr = getAbsoluteAddress(); bus.write(addr, shiftRight(bus.read(addr))); 6 }
            0x5E -> { val addr = getAbsoluteXAddress(); bus.write(addr, shiftRight(bus.read(addr))); 7 }

            0x2A -> { regA = rotateLeft(regA); 2 }
            0x26 -> { val addr = getZeroPageAddress(); bus.write(addr, rotateLeft(bus.read(addr))); 5 }
            0x36 -> { val addr = getZeroPageXAddress(); bus.write(addr, rotateLeft(bus.read(addr))); 6 }
            0x2E -> { val addr = getAbsoluteAddress(); bus.write(addr, rotateLeft(bus.read(addr))); 6 }
            0x3E -> { val addr = getAbsoluteXAddress(); bus.write(addr, rotateLeft(bus.read(addr))); 7 }

            0x6A -> { regA = rotateRight(regA); 2 }
            0x66 -> { val addr = getZeroPageAddress(); bus.write(addr, rotateRight(bus.read(addr))); 5 }
            0x76 -> { val addr = getZeroPageXAddress(); bus.write(addr, rotateRight(bus.read(addr))); 6 }
            0x6E -> { val addr = getAbsoluteAddress(); bus.write(addr, rotateRight(bus.read(addr))); 6 }
            0x7E -> { val addr = getAbsoluteXAddress(); bus.write(addr, rotateRight(bus.read(addr))); 7 }

            // Unofficial Opcodes
            0xA7 -> { val v = bus.read(getZeroPageAddress()); regA = v; regX = v; updateZeroAndNegativeFlags(v); 3 }
            0xB7 -> { val v = bus.read(getZeroPageYAddress()); regA = v; regX = v; updateZeroAndNegativeFlags(v); 4 }
            0xAF -> { val v = bus.read(getAbsoluteAddress()); regA = v; regX = v; updateZeroAndNegativeFlags(v); 4 }
            0xBF -> { val res = fetchAbsoluteY(); regA = res.first; regX = res.first; updateZeroAndNegativeFlags(res.first); if (res.second) 5 else 4 }
            0xA3 -> { val v = bus.read(getIndirectXAddress()); regA = v; regX = v; updateZeroAndNegativeFlags(v); 6 }
            0xB3 -> { val res = fetchIndirectY(); regA = res.first; regX = res.first; updateZeroAndNegativeFlags(res.first); if (res.second) 6 else 5 }

            0x87 -> { bus.write(getZeroPageAddress(), regA and regX); 3 }
            0x97 -> { bus.write(getZeroPageYAddress(), regA and regX); 4 }
            0x8F -> { bus.write(getAbsoluteAddress(), regA and regX); 4 }
            0x83 -> { bus.write(getIndirectXAddress(), regA and regX); 6 }

            0xC7 -> { val addr = getZeroPageAddress(); val v = (bus.read(addr) - 1) and 0xFF; bus.write(addr, v); compare(regA, v); 5 }
            0xD7 -> { val addr = getZeroPageXAddress(); val v = (bus.read(addr) - 1) and 0xFF; bus.write(addr, v); compare(regA, v); 6 }
            0xCF -> { val addr = getAbsoluteAddress(); val v = (bus.read(addr) - 1) and 0xFF; bus.write(addr, v); compare(regA, v); 6 }
            0xDF -> { val addr = getAbsoluteXAddress(); val v = (bus.read(addr) - 1) and 0xFF; bus.write(addr, v); compare(regA, v); 7 }
            0xDB -> { val addr = getAbsoluteYAddress(); val v = (bus.read(addr) - 1) and 0xFF; bus.write(addr, v); compare(regA, v); 7 }
            0xC3 -> { val addr = getIndirectXAddress(); val v = (bus.read(addr) - 1) and 0xFF; bus.write(addr, v); compare(regA, v); 8 }
            0xD3 -> { val addr = getIndirectYAddress(); val v = (bus.read(addr) - 1) and 0xFF; bus.write(addr, v); compare(regA, v); 8 }

            0xE7 -> { val addr = getZeroPageAddress(); val v = (bus.read(addr) + 1) and 0xFF; bus.write(addr, v); subtractWithCarry(v); 5 }
            0xF7 -> { val addr = getZeroPageXAddress(); val v = (bus.read(addr) + 1) and 0xFF; bus.write(addr, v); subtractWithCarry(v); 6 }
            0xEF -> { val addr = getAbsoluteAddress(); val v = (bus.read(addr) + 1) and 0xFF; bus.write(addr, v); subtractWithCarry(v); 6 }
            0xFF -> { val addr = getAbsoluteXAddress(); val v = (bus.read(addr) + 1) and 0xFF; bus.write(addr, v); subtractWithCarry(v); 7 }
            0xFB -> { val addr = getAbsoluteYAddress(); val v = (bus.read(addr) + 1) and 0xFF; bus.write(addr, v); subtractWithCarry(v); 7 }
            0xE3 -> { val addr = getIndirectXAddress(); val v = (bus.read(addr) + 1) and 0xFF; bus.write(addr, v); subtractWithCarry(v); 8 }
            0xF3 -> { val addr = getIndirectYAddress(); val v = (bus.read(addr) + 1) and 0xFF; bus.write(addr, v); subtractWithCarry(v); 8 }

            0x07 -> { val addr = getZeroPageAddress(); val v = shiftLeft(bus.read(addr)); bus.write(addr, v); logicOp(regA and v); 5 }
            0x17 -> { val addr = getZeroPageXAddress(); val v = shiftLeft(bus.read(addr)); bus.write(addr, v); logicOp(regA and v); 6 }
            0x0F -> { val addr = getAbsoluteAddress(); val v = shiftLeft(bus.read(addr)); bus.write(addr, v); logicOp(regA and v); 6 }
            0x1F -> { val addr = getAbsoluteXAddress(); val v = shiftLeft(bus.read(addr)); bus.write(addr, v); logicOp(regA and v); 7 }
            0x1B -> { val addr = getAbsoluteYAddress(); val v = shiftLeft(bus.read(addr)); bus.write(addr, v); logicOp(regA and v); 7 }
            0x03 -> { val addr = getIndirectXAddress(); val v = shiftLeft(bus.read(addr)); bus.write(addr, v); logicOp(regA and v); 8 }
            0x13 -> { val addr = getIndirectYAddress(); val v = shiftLeft(bus.read(addr)); bus.write(addr, v); logicOp(regA and v); 8 }

            0x27 -> { val addr = getZeroPageAddress(); val v = rotateLeft(bus.read(addr)); bus.write(addr, v); logicOp(regA and v); 5 }
            0x37 -> { val addr = getZeroPageXAddress(); val v = rotateLeft(bus.read(addr)); bus.write(addr, v); logicOp(regA and v); 6 }
            0x2F -> { val addr = getAbsoluteAddress(); val v = rotateLeft(bus.read(addr)); bus.write(addr, v); logicOp(regA and v); 6 }
            0x3F -> { val addr = getAbsoluteXAddress(); val v = rotateLeft(bus.read(addr)); bus.write(addr, v); logicOp(regA and v); 7 }
            0x3B -> { val addr = getAbsoluteYAddress(); val v = rotateLeft(bus.read(addr)); bus.write(addr, v); logicOp(regA and v); 7 }
            0x23 -> { val addr = getIndirectXAddress(); val v = rotateLeft(bus.read(addr)); bus.write(addr, v); logicOp(regA and v); 8 }
            0x33 -> { val addr = getIndirectYAddress(); val v = rotateLeft(bus.read(addr)); bus.write(addr, v); logicOp(regA and v); 8 }

            0x47 -> { val addr = getZeroPageAddress(); val v = shiftRight(bus.read(addr)); bus.write(addr, v); logicOp(regA xor v); 5 }
            0x57 -> { val addr = getZeroPageXAddress(); val v = shiftRight(bus.read(addr)); bus.write(addr, v); logicOp(regA xor v); 6 }
            0x4F -> { val addr = getAbsoluteAddress(); val v = shiftRight(bus.read(addr)); bus.write(addr, v); logicOp(regA xor v); 6 }
            0x5F -> { val addr = getAbsoluteXAddress(); val v = shiftRight(bus.read(addr)); bus.write(addr, v); logicOp(regA xor v); 7 }
            0x5B -> { val addr = getAbsoluteYAddress(); val v = shiftRight(bus.read(addr)); bus.write(addr, v); logicOp(regA xor v); 7 }
            0x43 -> { val addr = getIndirectXAddress(); val v = shiftRight(bus.read(addr)); bus.write(addr, v); logicOp(regA xor v); 8 }
            0x53 -> { val addr = getIndirectYAddress(); val v = shiftRight(bus.read(addr)); bus.write(addr, v); logicOp(regA xor v); 8 }

            0x67 -> { val addr = getZeroPageAddress(); val v = rotateRight(bus.read(addr)); bus.write(addr, v); addWithCarry(v); 5 }
            0x77 -> { val addr = getZeroPageXAddress(); val v = rotateRight(bus.read(addr)); bus.write(addr, v); addWithCarry(v); 6 }
            0x6F -> { val addr = getAbsoluteAddress(); val v = rotateRight(bus.read(addr)); bus.write(addr, v); addWithCarry(v); 6 }
            0x7F -> { val addr = getAbsoluteXAddress(); val v = rotateRight(bus.read(addr)); bus.write(addr, v); addWithCarry(v); 7 }
            0x7B -> { val addr = getAbsoluteYAddress(); val v = rotateRight(bus.read(addr)); bus.write(addr, v); addWithCarry(v); 7 }
            0x63 -> { val addr = getIndirectXAddress(); val v = rotateRight(bus.read(addr)); bus.write(addr, v); addWithCarry(v); 8 }
            0x73 -> { val addr = getIndirectYAddress(); val v = rotateRight(bus.read(addr)); bus.write(addr, v); addWithCarry(v); 8 }

            // Stack & Flags
            0x48 -> { push(regA); 3 }
            0x68 -> { regA = pull(); updateZeroAndNegativeFlags(regA); 4 }
            0x08 -> { push(status or FLAG_B or FLAG_U); 3 }
            0x28 -> { status = (pull() and FLAG_B.inv()) or FLAG_U; 4 }

            0x18 -> { setFlag(FLAG_C, false); 2 }
            0x38 -> { setFlag(FLAG_C, true); 2 }
            0x58 -> { setFlag(FLAG_I, false); 2 }
            0x78 -> { setFlag(FLAG_I, true); 2 }
            0xB8 -> { setFlag(FLAG_V, false); 2 }
            0xD8 -> { setFlag(FLAG_D, false); 2 }
            0xF8 -> { setFlag(FLAG_D, true); 2 }

            // NOPs
            0xEA, 0x1A, 0x3A, 0x5A, 0x7A, 0xDA, 0xFA -> 2
            0x80, 0x82, 0x89, 0xC2, 0xE2 -> { readPc(); 2 }
            0x04, 0x44, 0x64 -> { readPc(); 3 }
            0x14, 0x34, 0x54, 0x74, 0xD4, 0xF4 -> { readPc(); 4 }
            0x0C -> { readPc(); readPc(); 4 }
            0x1C, 0x3C, 0x5C, 0x7C, 0xDC, 0xFC -> { readPc(); readPc(); 4 }

            // BRK
            0x00 -> {
                regPc = (regPc + 1) and 0xFFFF
                push16(regPc)
                push(status or FLAG_B or FLAG_U)
                setFlag(FLAG_I, true)
                regPc = bus.read16(0xFFFE)
                7
            }

            else -> 2
        }
    }

    private fun fetchImmediate(): Int = readPc()
    private fun fetchZeroPage(): Int = bus.read(readPc())
    private fun fetchZeroPageX(): Int = bus.read((readPc() + regX) and 0xFF)
    private fun fetchZeroPageY(): Int = bus.read((readPc() + regY) and 0xFF)
    private fun fetchAbsolute(): Int = bus.read(getAbsoluteAddress())
    private fun fetchAbsoluteX(): Pair<Int, Boolean> {
        val base = getAbsoluteAddress()
        val addr = (base + regX) and 0xFFFF
        val pageCrossed = (base and 0xFF00) != (addr and 0xFF00)
        if (pageCrossed) {
            val dummyAddr = (base and 0xFF00) or ((base + regX) and 0xFF)
            bus.read(dummyAddr)
        }
        return Pair(bus.read(addr), pageCrossed)
    }
    private fun fetchAbsoluteY(): Pair<Int, Boolean> {
        val base = getAbsoluteAddress()
        val addr = (base + regY) and 0xFFFF
        val pageCrossed = (base and 0xFF00) != (addr and 0xFF00)
        if (pageCrossed) {
            val dummyAddr = (base and 0xFF00) or ((base + regY) and 0xFF)
            bus.read(dummyAddr)
        }
        return Pair(bus.read(addr), pageCrossed)
    }
    private fun fetchIndirectX(): Int {
        val zp = (readPc() + regX) and 0xFF
        val lo = bus.read(zp)
        val hi = bus.read((zp + 1) and 0xFF)
        return bus.read((hi shl 8) or lo)
    }
    private fun fetchIndirectY(): Pair<Int, Boolean> {
        val zp = readPc()
        val lo = bus.read(zp)
        val hi = bus.read((zp + 1) and 0xFF)
        val base = (hi shl 8) or lo
        val addr = (base + regY) and 0xFFFF
        val pageCrossed = (base and 0xFF00) != (addr and 0xFF00)
        if (pageCrossed) {
            val dummyAddr = (base and 0xFF00) or ((base + regY) and 0xFF)
            bus.read(dummyAddr)
        }
        return Pair(bus.read(addr), pageCrossed)
    }

    private fun getZeroPageAddress(): Int = readPc()
    private fun getZeroPageXAddress(): Int = (readPc() + regX) and 0xFF
    private fun getZeroPageYAddress(): Int = (readPc() + regY) and 0xFF
    private fun getAbsoluteAddress(): Int {
        val lo = readPc()
        val hi = readPc()
        return (hi shl 8) or lo
    }
    private fun getAbsoluteXAddress(): Int = (getAbsoluteAddress() + regX) and 0xFFFF
    private fun getAbsoluteYAddress(): Int = (getAbsoluteAddress() + regY) and 0xFFFF
    private fun getIndirectXAddress(): Int {
        val zp = (readPc() + regX) and 0xFF
        val lo = bus.read(zp)
        val hi = bus.read((zp + 1) and 0xFF)
        return (hi shl 8) or lo
    }
    private fun getIndirectYAddress(): Int {
        val zp = readPc()
        val lo = bus.read(zp)
        val hi = bus.read((zp + 1) and 0xFF)
        val base = (hi shl 8) or lo
        val addr = (base + regY) and 0xFFFF
        val pageCrossed = (base and 0xFF00) != (addr and 0xFF00)
        if (pageCrossed) {
            val dummyAddr = (base and 0xFF00) or ((base + regY) and 0xFF)
            bus.read(dummyAddr)
        }
        return addr
    }

    private fun branch(condition: Boolean): Int {
        val offset = readPc().toByte()
        if (condition) {
            val addr = (regPc + offset) and 0xFFFF
            val cycles = if ((regPc and 0xFF00) != (addr and 0xFF00)) 4 else 3
            regPc = addr
            return cycles
        }
        return 2
    }

    private fun compare(reg: Int, value: Int) {
        val res = reg - value
        setFlag(FLAG_C, reg >= value)
        setFlag(FLAG_Z, (res and 0xFF) == 0)
        setFlag(FLAG_N, (res and 0x80) != 0)
    }

    private fun addWithCarry(value: Int) {
        val c = if (getFlag(FLAG_C)) 1 else 0
        val sum = regA + value + c
        setFlag(FLAG_C, sum > 0xFF)
        val res = sum and 0xFF
        setFlag(FLAG_V, ((regA xor res) and (value xor res) and 0x80) != 0)
        updateZeroAndNegativeFlags(res)
        regA = res
    }

    private fun subtractWithCarry(value: Int) {
        addWithCarry(value.inv() and 0xFF)
    }

    private fun logicOp(value: Int) {
        regA = value and 0xFF
        updateZeroAndNegativeFlags(regA)
    }

    private fun bitTest(value: Int) {
        setFlag(FLAG_Z, (regA and value) == 0)
        setFlag(FLAG_V, (value and 0x40) != 0)
        setFlag(FLAG_N, (value and 0x80) != 0)
    }

    private fun shiftLeft(v: Int): Int {
        setFlag(FLAG_C, (v and 0x80) != 0)
        val res = (v shl 1) and 0xFF
        updateZeroAndNegativeFlags(res)
        return res
    }

    private fun shiftRight(v: Int): Int {
        setFlag(FLAG_C, (v and 0x01) != 0)
        val res = v ushr 1
        updateZeroAndNegativeFlags(res)
        return res
    }

    private fun rotateLeft(v: Int): Int {
        val c = if (getFlag(FLAG_C)) 1 else 0
        setFlag(FLAG_C, (v and 0x80) != 0)
        val res = ((v shl 1) or c) and 0xFF
        updateZeroAndNegativeFlags(res)
        return res
    }

    private fun rotateRight(v: Int): Int {
        val c = if (getFlag(FLAG_C)) 0x80 else 0
        setFlag(FLAG_C, (v and 0x01) != 0)
        val res = (v ushr 1) or c
        updateZeroAndNegativeFlags(res)
        return res
    }
}

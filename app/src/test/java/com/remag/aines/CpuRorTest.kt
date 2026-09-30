package com.remag.aines

import com.remag.aines.emulator.Bus
import com.remag.aines.emulator.Cartridge
import com.remag.aines.emulator.Cpu6502
import org.junit.Assert.assertEquals
import org.junit.Test

class CpuRorTest {
    @Test
    fun officialRorOpcodesRotateAndConsumeCorrectCycles() {
        data class RorCase(
            val opcode: Int,
            val operands: ByteArray,
            val cycles: Int,
            val accumulator: Boolean = false,
            val x: Int = 0
        )

        val cases = listOf(
            RorCase(0x6A, byteArrayOf(), 2, accumulator = true),
            RorCase(0x66, byteArrayOf(0x10), 5),
            RorCase(0x76, byteArrayOf(0x0F), 6, x = 1),
            RorCase(0x6E, byteArrayOf(0x10, 0x00), 6),
            RorCase(0x7E, byteArrayOf(0x0F, 0x00), 7, x = 1)
        )

        for (case in cases) {
            val prg = ByteArray(16384)
            prg[0] = case.opcode.toByte()
            case.operands.copyInto(prg, destinationOffset = 1)
            val header = byteArrayOf(
                0x4E, 0x45, 0x53, 0x1A,
                1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
            )
            val bus = Bus().apply {
                cartridge = Cartridge(header + prg + ByteArray(8192))
                cpuRam[0x10] = 0x02
            }
            val cpu = Cpu6502(bus).apply {
                reset()
                regPc = 0x8000
                regX = case.x
                regA = 0x02
                setFlag(Cpu6502.FLAG_C, true)
            }

            assertEquals("Opcode ${case.opcode.toString(16)} cycle count", case.cycles, cpu.step())
            if (case.accumulator) {
                assertEquals("Accumulator result", 0x81, cpu.regA)
                assertEquals("Accumulator instruction length", 0x8001, cpu.regPc)
            } else {
                assertEquals("Memory result for opcode ${case.opcode.toString(16)}", 0x81, bus.read(0x10))
                assertEquals("Memory instruction length", 0x8000 + 1 + case.operands.size, cpu.regPc)
            }
            assertEquals("Rotate carry", false, cpu.getFlag(Cpu6502.FLAG_C))
            assertEquals("Negative flag", true, cpu.getFlag(Cpu6502.FLAG_N))
            assertEquals("Zero flag", false, cpu.getFlag(Cpu6502.FLAG_Z))
        }
    }
}

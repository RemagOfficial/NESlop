package com.remag.aines

import com.remag.aines.emulator.Cartridge
import com.remag.aines.emulator.NesMachine
import com.remag.aines.emulator.Ppu2c02
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Diagnostic harness for SMB3's mid-scanline raster effects (the MMC3 status-bar split and the
 * title-screen flicker). It boots Super Mario Bros. 3 headlessly, walks the route below into
 * World 1-1, then replays one frame with per-dot observers wired to the emulator's diagnostic
 * hooks: every $2000-$2007 write, every MMC3 register write, every interrupt the CPU takes and
 * every IRQ deferred by the I flag. Everything is dumped to smb3_trace/ - a PNG of the traced
 * frame, a per-line state trace, run-length rows of the band, the raw nametables and the
 * mirroring writes - so the split can be compared against a hardware screenshot dot by dot.
 *
 * The trace test is @Ignore'd so the suite stays fast; un-comment @Ignore to re-run it after
 * changing PPU raster timing or MMC3 IRQ behaviour. probePadReadback stays active as a real
 * regression guard for the controller shift-register readback order.
 */
class Smb3RasterTraceTest {
    private companion object {
        // Collect a contact sheet of frames instead of a single traced frame (heavy, rarely wanted).
        const val MAKE_SHEET = false
        // true: route all the way into World 1-1 and trace the in-level status bar.
        // false would stop at a title-screen frame to chase the band flicker instead.
        const val IN_LEVEL = true
        const val TRACE_FRAME = 3600
        const val FLICKER_FRAMES = 1
        const val SHEET_FROM = 1100
        const val SHEET_TO = 3900
        const val SHEET_STEP = 50
        // Scanline window the per-dot line trace records (status bar band plus a margin).
        const val WINDOW_FROM = 176
        const val WINDOW_TO = 239
        // Scanline range the run-length row dump covers.
        const val BAND_FROM = 176
        const val BAND_TO = 239
    }

    private data class Press(val frame: Int, val button: Int, val down: Boolean)

    /**
     * Menu navigation learned from manual runs: A/Start taps to get past the title and player
     * select, then hold Right, then Up, then A to accept and load World 1-1. Button values are
     * the Controller bit masks (A = 1, Start = 8, Up = 16, Right = 128).
     */
    private val route = buildList {
        if (IN_LEVEL) {
            var f = 1100
            while (f < 2600) {
                add(Press(f, 1, true)); add(Press(f + 3, 1, false))
                add(Press(f + 20, 8, true)); add(Press(f + 23, 8, false))
                f += 40
            }
            add(Press(2700, 128, true)); add(Press(2900, 128, false)) // right
            add(Press(3000, 16, true)); add(Press(3100, 16, false))   // up
            add(Press(3200, 1, true)); add(Press(3212, 1, false))     // A: enter the level
        }
    }

    private val outDir = File("../smb3_trace").apply { mkdirs() }
    private val romBytes = File("../Super Mario Bros 3.nes").readBytes()

    private fun hex(value: Int, width: Int = 4): String =
        Integer.toHexString(value).padStart(width, '0')

    /** One line describing everything that decides the picture at this instant. */
    private fun state(ppu: Ppu2c02, cart: Cartridge): String {
        val m = cart.mmc3DebugState()
        return "v=${hex(ppu.vramAddr)} t=${hex(ppu.tramAddr)} x=${ppu.fineX} " +
            "ctrl=${hex(ppu.ppuCtrl, 2)} mask=${hex(ppu.ppuMask, 2)} " +
            "cnt=${m[0]} latch=${m[1]} rld=${m[2]} en=${m[3]} irq=${cart.irqRequested} " +
            "r=[${(4..11).joinToString(",") { hex(m[it], 2) }}] chrM=${m[12]} prgM=${m[13]}"
    }

    /**
     * A copy of NesMachine.stepFrame() that gets the dot-by-dot callback: the CPU is stepped with
     * the same level-driven IRQ line, and after every PPU clock the current raster position is
     * reported so events can be stamped with the exact scanline/dot they landed at.
     */
    private fun runFrame(machine: NesMachine, event: (scanline: Int, dot: Int) -> Unit) {
        val ppu = machine.ppu
        val cpu = machine.cpu
        val apu = machine.apu
        val cart = machine.cartridge
        ppu.frameReady = false
        var steps = 0
        while (!ppu.frameReady && steps < 30000) {
            steps++
            if (ppu.nmiRequested) {
                cpu.nmiRequested = true
                ppu.nmiRequested = false
            }
            cpu.irqRequested = apu.irqRequested || cart.irqRequested
            val stepCycles = cpu.step()
            repeat(stepCycles) {
                repeat(3) {
                    ppu.clock()
                    event(ppu.scanline, ppu.dot)
                }
                apu.clockCycle()
            }
        }
    }

    /** The row as run-length colour ranges: "start-end#RRGGBBAA ..." - compact glitch signatures. */
    private fun runLengthRow(ppu: Ppu2c02, y: Int): String {
        val sb = StringBuilder()
        var start = 0
        var run = ppu.frameBuffer[y * 256]
        for (x in 1..256) {
            val next = if (x < 256) ppu.frameBuffer[y * 256 + x] else -2
            if (next != run) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(start).append('-').append(x - 1).append('#').append(hex(run, 8))
                run = next
                start = x
            }
        }
        return sb.toString()
    }

    private fun dumpRows(ppu: Ppu2c02, label: String, from: Int, to: Int) {
        val text = (from..to).joinToString(System.lineSeparator()) { "y$it: " + runLengthRow(ppu, it) }
        File(outDir, "rows_$label.txt").writeText(text + System.lineSeparator())
    }

    private fun dumpPng(ppu: Ppu2c02, label: String) {
        val img = BufferedImage(256, 240, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 240) {
            for (x in 0 until 256) {
                img.setRGB(x, y, ppu.frameBuffer[y * 256 + x])
            }
        }
        ImageIO.write(img, "png", File(outDir, "frame_$label.png"))
    }

    private fun snapshot(ppu: Ppu2c02): IntArray = ppu.frameBuffer.copyOf()

    /** Raw nametable bytes through the *current* mirror, plus the palette, at the traced instant. */
    private fun dumpNametables(ppu: Ppu2c02, cart: Cartridge, label: String) {
        val sep = System.lineSeparator()
        val sb = StringBuilder()
        sb.append("mirrorVertical=").append(cart.mirrorVertical).append(sep)
        for (table in 0 until 4) {
            sb.append("--- logical table ").append(table).append(" ($2000 +")
                .append(hex(table * 1024, 4)).append(") ---").append(sep)
            for (row in 0 until 30) {
                sb.append("r").append(row.toString().padStart(2, '0')).append(':')
                for (col in 0 until 32) {
                    val idx = table * 1024 + row * 32 + col
                    val phys = if (cart.mirrorVertical) table and 1 else table ushr 1
                    sb.append(' ').append(hex(ppu.vram[(phys shl 10) or (idx and 1023)].toInt() and 0xFF, 2))
                }
                sb.append(sep)
            }
        }
        sb.append("palette:").append(sep)
        for (i in 0 until 32) {
            sb.append(hex(ppu.paletteRam[i].toInt() and 0xFF, 2))
            sb.append(if (i % 8 == 7) sep else ' ')
        }
        File(outDir, "nt_$label.txt").writeText(sb.toString())
    }

    /** Half-scale contact sheet so several frames fit side by side with their numbers. */
    private fun dumpSheet(frames: List<Pair<Int, IntArray>>) {
        val cols = 4
        val rows = (frames.size + cols - 1) / cols
        val img = BufferedImage(cols * 128, rows * 124, BufferedImage.TYPE_INT_RGB)
        frames.forEachIndexed { index, (_, pixels) ->
            val ox = index % cols * 128
            val oy = index / cols * 124
            for (y in 0 until 120) {
                for (x in 0 until 128) {
                    img.setRGB(ox + x, oy + 4 + y, pixels[(y * 2) * 256 + x * 2])
                }
            }
        }
        val g = img.createGraphics()
        g.font = Font("Monospaced", Font.PLAIN, 10)
        frames.forEachIndexed { index, (frame, _) ->
            val ox = index % cols * 128
            val oy = index / cols * 124
            g.color = Color.BLACK
            g.fillRect(ox, oy, 60, 12)
            g.color = Color.WHITE
            g.drawString("f$frame", ox + 2, oy + 10)
        }
        g.dispose()
        ImageIO.write(img, "png", File(outDir, "sheet.png"))
    }

    @Ignore("Diagnostic harness: un-ignore to re-trace SMB3's status-bar raster. Toggles: IN_LEVEL picks title-vs-level, MAKE_SHEET writes a contact sheet; outputs land in smb3_trace/.")
    @Test
    fun traceSmb3Raster() {
        val machine = NesMachine(romBytes.copyOf(romBytes.size))
        val ppu = machine.ppu
        val cart = machine.cartridge
        val cpu = machine.cpu

        val trace = StringBuilder()
        val mirroring = StringBuilder()
        var traceOn = false
        var irqDeferred = 0
        var prevIrq = false
        var currentFrame = 0

        fun event(label: String) {
            if (traceOn) {
                trace.append("L").append(ppu.scanline).append('.').append(ppu.dot)
                    .append(" ").append(label).append(": ").append(state(ppu, cart))
                    .append(System.lineSeparator())
            }
        }

        ppu.onRegisterWrite = { addr, value -> event("$$ {hex(addr, 4)}<-${hex(value, 2)}") }
        cart.onBankWrite = { addr, value ->
            if (addr in 0xA000..0xAFFF) {
                mirroring.append("f").append(currentFrame).append(" $").append(hex(addr, 4))
                    .append("<-").append(hex(value, 2))
                    .append("   mirrorVertical=").append(cart.mirrorVertical)
                    .append(System.lineSeparator())
            }
            event("$$ {hex(addr, 4)}<-${hex(value, 2)}")
        }
        cpu.onInterruptTaken = { kind, vector -> event(">>> $kind taken at pc=${hex(vector)}") }
        cpu.onIrqDeferred = { if (traceOn) irqDeferred++ }

        val snapshots = ArrayList<Pair<Int, IntArray>>()
        val endFrame = maxOf(SHEET_TO, TRACE_FRAME + 1) + 5
        var reached = false
        var frame = 0
        while (frame < endFrame) {
            frame++
            currentFrame = frame
            route.filter { it.frame == frame }.forEach { machine.pressButton(it.button, it.down) }

            if (frame == TRACE_FRAME) {
                traceOn = true
                irqDeferred = 0
                val label = hex(frame, 4)
                val lines = StringBuilder()
                var lastLine = -1
                runFrame(machine) { scanline, dot ->
                    if (cart.irqRequested != prevIrq) {
                        event(if (cart.irqRequested) "=== IRQ ASSERTED" else "=== irq cleared")
                        prevIrq = cart.irqRequested
                    }
                    if (dot == 0 && scanline != lastLine && scanline in WINDOW_FROM..WINDOW_TO) {
                        lastLine = scanline
                        lines.append("line").append(scanline).append(" ")
                            .append(state(ppu, cart)).append(System.lineSeparator())
                    }
                }
                traceOn = false
                event("--- end of frame $label, irqDeferred=$irqDeferred")
                File(outDir, "lines_$label.txt").writeText(lines.toString())
                dumpRows(ppu, label, BAND_FROM, BAND_TO)
                dumpPng(ppu, label)
                dumpNametables(ppu, cart, label)
                reached = true
            } else {
                machine.stepFrame()
                if (frame == endFrame - 3) reached = true
            }
            if (MAKE_SHEET && frame >= SHEET_FROM && frame <= SHEET_TO && (frame - SHEET_FROM) % SHEET_STEP == 0) {
                snapshots.add(frame to snapshot(ppu))
            }
        }

        if (MAKE_SHEET) dumpSheet(snapshots)
        File(outDir, "trace.txt").writeText(trace.toString())
        File(outDir, "mirroring.txt").writeText(mirroring.toString())
        assertTrue("expected to reach the dump frames, stopped at $frame", reached)
    }

    /**
     * Real regression guard: strobes $4016 and checks the eight bits that come back for a held
     * button, so the controller shift-register order can't silently flip. Also proves the harness
     * ROM boots far enough for pad polling at all (it runs 600 frames first).
     */
    @Test
    fun probePadReadback() {
        val machine = NesMachine(romBytes.copyOf(romBytes.size))
        val sb = StringBuilder()
        repeat(600) { machine.stepFrame() }

        fun sample(button: Int): List<Int> {
            machine.pressButton(button, true)
            machine.bus.write(0x4016, 1)
            machine.bus.write(0x4016, 0)
            val bits = (0 until 8).map { machine.bus.read(0x4016, true) and 1 }
            machine.pressButton(button, false)
            return bits
        }

        val start = sample(8)
        val right = sample(128)
        val idle = sample(0)

        sb.append("START held -> $start").append(System.lineSeparator())
        sb.append("RIGHT held -> $right").append(System.lineSeparator())
        sb.append("nothing    -> $idle").append(System.lineSeparator())
        File(outDir, "pad.txt").writeText(sb.toString())

        assertTrue("START is button bit 3, got $start", start == listOf(0, 0, 0, 1, 0, 0, 0, 0))
        assertTrue("RIGHT is button bit 7, got $right", right == listOf(0, 0, 0, 0, 0, 0, 0, 1))
        assertTrue("idle pad must read all zeros, got $idle", idle.all { it == 0 })
    }
}

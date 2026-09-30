package com.remag.aines.emulator

class AudioRingBuffer(capacity: Int = 8192) {
    private val buffer = ShortArray(capacity)
    @Volatile private var head = 0
    @Volatile private var tail = 0

    fun write(sample: Short): Boolean {
        val currentTail = tail
        val nextTail = (currentTail + 1) % buffer.size
        if (nextTail != head) {
            buffer[currentTail] = sample
            tail = nextTail
            return true
        }
        return false
    }

    fun available(): Int {
        val h = head
        val t = tail
        return if (t >= h) t - h else buffer.size - (h - t)
    }

    fun read(dest: ShortArray, offset: Int, length: Int): Int {
        val h = head
        val t = tail
        val avail = if (t >= h) t - h else buffer.size - (h - t)
        val count = minOf(length, avail)
        var currentHead = h
        for (i in 0 until count) {
            dest[offset + i] = buffer[currentHead]
            currentHead = (currentHead + 1) % buffer.size
        }
        head = currentHead
        return count
    }
}

class Envelope {
    var startFlag = false
    var useConstantVolume = false
    var loop = false
    var volumeReload = 0
    var divider = 0
    var decayLevel = 0

    fun clock() {
        if (startFlag) {
            startFlag = false
            decayLevel = 15
            divider = volumeReload
        } else {
            if (divider == 0) {
                divider = volumeReload
                if (decayLevel > 0) {
                    decayLevel--
                } else if (loop) {
                    decayLevel = 15
                }
            } else {
                divider--
            }
        }
    }

    val output: Int
        get() = if (useConstantVolume) volumeReload else decayLevel
}

class Sweep {
    var enabled = false
    var period = 0
    var negate = false
    var shift = 0
    var divider = 0
    var reloadFlag = false

    fun isMuted(p: Int, channel: Int): Boolean {
        val change = p ushr shift
        val target = if (negate) {
            if (channel == 1) p - change else p - change - 1
        } else {
            p + change
        }
        return p < 8 || target > 2047
    }

    fun clock(periodVal: Int, channel: Int): Int {
        var p = periodVal
        val change = p ushr shift
        val target = if (negate) {
            if (channel == 1) p - change else p - change - 1
        } else {
            p + change
        }

        val isMuted = p < 8 || target > 2047

        if (divider == 0 && enabled && shift > 0 && !isMuted) {
            p = target
        }

        if (divider == 0 || reloadFlag) {
            divider = period
            reloadFlag = false
        } else {
            divider--
        }
        return p
    }
}

class Apu2a03 {
    val registers = ByteArray(0x20)
    var irqRequested: Boolean = false

    // Channel enable flags ($4015)
    var p1Enable = false
    var p2Enable = false
    var triEnable = false
    var noiseEnable = false

    // Pulse 1
    var p1Duty = 0
    var p1Period = 0
    var p1FloatPhase = 0f
    val p1Envelope = Envelope()
    val p1Sweep = Sweep()
    var p1LengthCounter = 0
    var p1Halt = false

    // Pulse 2
    var p2Duty = 0
    var p2Period = 0
    var p2FloatPhase = 0f
    val p2Envelope = Envelope()
    val p2Sweep = Sweep()
    var p2LengthCounter = 0
    var p2Halt = false

    // Triangle
    var triPeriod = 0
    var triTimer = 0
    var triPhase = 0
    var triLengthCounter = 0
    var triHalt = false
    var triLinearReload = 0
    var triLinearCounter = 0
    var triLinearReloadFlag = false
    var triControlFlag = false

    // Noise
    var noisePeriod = 0
    var noiseTimer = 0
    var noiseShiftReg = 1
    var noiseMode = false
    val noiseEnvelope = Envelope()
    var noiseLengthCounter = 0
    var noiseHalt = false

    // Frame counter cycles & clocks. `internal` so the save-state codec can snapshot them.
    var frameCounterCycles = 0
    var frameStep = 0
    internal var apuClockEven = false

    // Audio Ring Buffer (FIFO)
    val audioQueue = AudioRingBuffer(8192)

    // Triangle sequence (32 steps)
    private val triangleTable = intArrayOf(
        15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    )

    // Noise period table (NTSC APU clocks)
    private val noisePeriodTable = intArrayOf(
        2, 4, 8, 16, 32, 48, 64, 80, 101, 127, 190, 254, 381, 508, 1017, 2034
    )

    // Length counter load table
    private val lengthTable = intArrayOf(
        10, 254, 20, 2, 40, 4, 80, 6, 160, 8, 60, 10, 14, 12, 26, 14,
        12, 16, 24, 18, 48, 20, 96, 22, 192, 24, 72, 26, 16, 28, 32, 30
    )

    // Official Nesdev NTSC DAC Mixing Tables
    private val pulseTable = FloatArray(31) { if (it == 0) 0f else 95.88f / (8128.0f / it + 100.0f) }
    private val tndTable = FloatArray(16 * 16) { index ->
        val tri = index / 16
        val noise = index % 16
        val tndSum = tri / 8227.0f + noise / 12241.0f
        if (tndSum > 0f) 159.79f / (1.0f / tndSum + 100.0f) else 0f
    }

    // Resampling accumulator (1.789773 MHz / 44.1 kHz). `internal` for the save-state codec.
    internal var cycleAccumulator = 0
    private val cpuFrequency = 1789773
    private val sampleRate = 44100

    // Filters state. `internal` for the save-state codec; restoring them avoids an audible pop.
    internal var lastX = 0f
    internal var filterOut = 0f
    internal var lpOut = 0f
    private val lpAlpha = 0.6f

    fun readRegister(address: Int): Int {
        val addr = address and 0x001F
        if (addr == 0x15) {
            var status = 0
            if (p1LengthCounter > 0) status = status or 0x01
            if (p2LengthCounter > 0) status = status or 0x02
            if (triLengthCounter > 0) status = status or 0x04
            if (noiseLengthCounter > 0) status = status or 0x08
            if (irqRequested) status = status or 0x40
            irqRequested = false
            return status
        }
        return 0
    }

    fun writeRegister(address: Int, value: Int) {
        val addr = address and 0x001F
        val v = value and 0xFF
        registers[addr] = v.toByte()

        when (addr) {
            0x00 -> {
                p1Duty = (v ushr 6) and 0x03
                p1Halt = (v and 0x20) != 0
                p1Envelope.useConstantVolume = (v and 0x10) != 0
                p1Envelope.loop = p1Halt
                p1Envelope.volumeReload = v and 0x0F
            }
            0x01 -> {
                p1Sweep.enabled = (v and 0x80) != 0
                p1Sweep.period = (v ushr 4) and 0x07
                p1Sweep.negate = (v and 0x08) != 0
                p1Sweep.shift = v and 0x07
                p1Sweep.reloadFlag = true
            }
            0x02 -> {
                p1Period = (p1Period and 0x0700) or v
            }
            0x03 -> {
                p1Period = (p1Period and 0x00FF) or ((v and 0x07) shl 8)
                if (p1Enable) p1LengthCounter = lengthTable[(v ushr 3) and 0x1F]
                p1Envelope.startFlag = true
                p1FloatPhase = 0f
            }
            0x04 -> {
                p2Duty = (v ushr 6) and 0x03
                p2Halt = (v and 0x20) != 0
                p2Envelope.useConstantVolume = (v and 0x10) != 0
                p2Envelope.loop = p2Halt
                p2Envelope.volumeReload = v and 0x0F
            }
            0x05 -> {
                p2Sweep.enabled = (v and 0x80) != 0
                p2Sweep.period = (v ushr 4) and 0x07
                p2Sweep.negate = (v and 0x08) != 0
                p2Sweep.shift = v and 0x07
                p2Sweep.reloadFlag = true
            }
            0x06 -> {
                p2Period = (p2Period and 0x0700) or v
            }
            0x07 -> {
                p2Period = (p2Period and 0x00FF) or ((v and 0x07) shl 8)
                if (p2Enable) p2LengthCounter = lengthTable[(v ushr 3) and 0x1F]
                p2Envelope.startFlag = true
                p2FloatPhase = 0f
            }
            0x08 -> {
                triControlFlag = (v and 0x80) != 0
                triHalt = triControlFlag
                triLinearReload = v and 0x7F
            }
            0x0A -> {
                triPeriod = (triPeriod and 0x0700) or v
            }
            0x0B -> {
                triPeriod = (triPeriod and 0x00FF) or ((v and 0x07) shl 8)
                if (triEnable) triLengthCounter = lengthTable[(v ushr 3) and 0x1F]
                triLinearReloadFlag = true
            }
            0x0C -> {
                noiseHalt = (v and 0x20) != 0
                noiseEnvelope.useConstantVolume = (v and 0x10) != 0
                noiseEnvelope.loop = noiseHalt
                noiseEnvelope.volumeReload = v and 0x0F
            }
            0x0E -> {
                noiseMode = (v and 0x80) != 0
                noisePeriod = noisePeriodTable[v and 0x0F]
            }
            0x0F -> {
                if (noiseEnable) noiseLengthCounter = lengthTable[(v ushr 3) and 0x1F]
                noiseEnvelope.startFlag = true
            }
            0x10, 0x11, 0x12, 0x13 -> {}
            0x15 -> {
                p1Enable = (v and 0x01) != 0
                if (!p1Enable) p1LengthCounter = 0

                p2Enable = (v and 0x02) != 0
                if (!p2Enable) p2LengthCounter = 0

                triEnable = (v and 0x04) != 0
                if (!triEnable) {
                    triLengthCounter = 0
                    triLinearCounter = 0
                }

                noiseEnable = (v and 0x08) != 0
                if (!noiseEnable) noiseLengthCounter = 0
            }
            0x17 -> {
                val inhibit = (v and 0x40) != 0
                val mode5 = (v and 0x80) != 0
                if (inhibit) irqRequested = false
                if (mode5) {
                    p1Envelope.clock()
                    p2Envelope.clock()
                    noiseEnvelope.clock()
                    if (triLinearReloadFlag) {
                        triLinearCounter = triLinearReload
                    } else if (triLinearCounter > 0) {
                        triLinearCounter--
                    }
                    if (!triControlFlag) {
                        triLinearReloadFlag = false
                    }
                    if (!p1Halt && p1LengthCounter > 0) p1LengthCounter--
                    if (!p2Halt && p2LengthCounter > 0) p2LengthCounter--
                    if (!triHalt && triLengthCounter > 0) triLengthCounter--
                    if (!noiseHalt && noiseLengthCounter > 0) noiseLengthCounter--
                    val p1Holder = intArrayOf(p1Period)
                    p1Period = p1Sweep.clock(p1Holder[0], 1)
                    val p2Holder = intArrayOf(p2Period)
                    p2Period = p2Sweep.clock(p2Holder[0], 2)
                }
            }
        }
    }

    private fun polyBlep(t: Float, dt: Float): Float {
        if (dt <= 0f) return 0.0f
        if (t < dt) {
            val x = t / dt
            return x + x - x * x - 1.0f
        } else if (t > 1.0f - dt) {
            val x = (t - 1.0f) / dt
            return x * x + x + x + 1.0f
        }
        return 0.0f
    }

    fun clockCycle() {
        apuClockEven = !apuClockEven

        // Triangle timer counts down at CPU clock speed (every 1 CPU cycle)
        if (triPeriod >= 2 && triLengthCounter > 0 && triLinearCounter > 0) {
            triTimer--
            if (triTimer < 0) {
                triTimer = triPeriod
                triPhase = (triPhase + 1) % 32
            }
        }

        // Pulse, noise, and frame counter run at APU clock speed (CPU clock / 2)
        if (apuClockEven) {
            frameCounterCycles++
            if (frameCounterCycles >= 7457) {
                frameCounterCycles = 0
                frameStep = (frameStep + 1) % 4

                p1Envelope.clock()
                p2Envelope.clock()
                noiseEnvelope.clock()

                if (triLinearReloadFlag) {
                    triLinearCounter = triLinearReload
                } else if (triLinearCounter > 0) {
                    triLinearCounter--
                }
                if (!triControlFlag) {
                    triLinearReloadFlag = false
                }

                if (frameStep == 1 || frameStep == 3) {
                    if (!p1Halt && p1LengthCounter > 0) p1LengthCounter--
                    if (!p2Halt && p2LengthCounter > 0) p2LengthCounter--
                    if (!triHalt && triLengthCounter > 0) triLengthCounter--
                    if (!noiseHalt && noiseLengthCounter > 0) noiseLengthCounter--

                    val p1Holder = intArrayOf(p1Period)
                    p1Period = p1Sweep.clock(p1Holder[0], 1)

                    val p2Holder = intArrayOf(p2Period)
                    p2Period = p2Sweep.clock(p2Holder[0], 2)
                }
            }

            // --- Pulse 1 Phase Advance (Band-limited PolyBLEP) ---
            if (p1Period >= 8) {
                val dt = 0.125f / (p1Period + 1)
                p1FloatPhase += dt
                if (p1FloatPhase >= 1.0f) p1FloatPhase -= 1.0f
            }

            // --- Pulse 2 Phase Advance (Band-limited PolyBLEP) ---
            if (p2Period >= 8) {
                val dt = 0.125f / (p2Period + 1)
                p2FloatPhase += dt
                if (p2FloatPhase >= 1.0f) p2FloatPhase -= 1.0f
            }

            // --- Noise Timer ---
            if (noisePeriod > 0) {
                noiseTimer--
                if (noiseTimer < 0) {
                    noiseTimer = noisePeriod
                    if (noiseShiftReg == 0) noiseShiftReg = 1
                    val bit0 = noiseShiftReg and 0x01
                    val bitTap = if (noiseMode) (noiseShiftReg ushr 6) and 0x01 else (noiseShiftReg ushr 1) and 0x01
                    val feedback = bit0 xor bitTap
                    noiseShiftReg = (noiseShiftReg ushr 1) or (feedback shl 14)
                }
            }
        }

        // --- 44.1kHz Resampling Accumulator with Band-Limited Synthesis ---
        cycleAccumulator += sampleRate
        if (cycleAccumulator >= cpuFrequency) {
            cycleAccumulator -= cpuFrequency

            val p1OutSample = if (p1Enable && !p1Sweep.isMuted(p1Period, 1) && p1LengthCounter > 0) {
                val dutyRatio = when (p1Duty) {
                    0 -> 0.125f
                    1 -> 0.25f
                    2 -> 0.5f
                    else -> 0.75f
                }
                val dt = 2.536537f / (p1Period + 1)
                val base = if (p1FloatPhase < dutyRatio) 1.0f else 0.0f
                val blep = polyBlep(p1FloatPhase, dt) - polyBlep((p1FloatPhase + dutyRatio) % 1.0f, dt)
                (base + blep) * p1Envelope.output
            } else 0f

            val p2OutSample = if (p2Enable && !p2Sweep.isMuted(p2Period, 2) && p2LengthCounter > 0) {
                val dutyRatio = when (p2Duty) {
                    0 -> 0.125f
                    1 -> 0.25f
                    2 -> 0.5f
                    else -> 0.75f
                }
                val dt = 2.536537f / (p2Period + 1)
                val base = if (p2FloatPhase < dutyRatio) 1.0f else 0.0f
                val blep = polyBlep(p2FloatPhase, dt) - polyBlep((p2FloatPhase + dutyRatio) % 1.0f, dt)
                (base + blep) * p2Envelope.output
            } else 0f

            val p1IntOut = (p1OutSample + 0.5f).toInt().coerceIn(0, 15)
            val p2IntOut = (p2OutSample + 0.5f).toInt().coerceIn(0, 15)

            val pulseIndex = p1IntOut + p2IntOut
            val pulseSample = if (pulseIndex in pulseTable.indices) pulseTable[pulseIndex] else 0f

            val triOut = if (triEnable) triangleTable[triPhase] else 0
            val noiseOut = if (noiseEnable && noisePeriod > 0 && noiseLengthCounter > 0 && (noiseShiftReg and 0x01) == 0) noiseEnvelope.output else 0

            val tndSample = tndTable[(triOut shl 4) or noiseOut]

            val rawSample = pulseSample + tndSample

            lpOut += lpAlpha * (rawSample - lpOut)

            val filtered = lpOut - lastX + 0.995f * filterOut
            lastX = lpOut
            filterOut = filtered

            val sampleShort = (filterOut.coerceIn(-1.0f, 1.0f) * 28000).toInt().toShort()
            audioQueue.write(sampleShort)
        }
    }

    fun startFrame() {
        // Handled dynamically by AudioRingBuffer
    }
}

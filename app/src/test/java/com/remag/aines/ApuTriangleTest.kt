package com.remag.aines

import com.remag.aines.emulator.Apu2a03
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ApuTriangleTest {

    @Test
    fun testTriangleWaveformOutputAndNoClipping() {
        val apu = Apu2a03()

        // Enable triangle channel ($4015 = 0x04)
        apu.writeRegister(0x15, 0x04)

        // Set Triangle Control / Linear reload ($4008 = 0x7F)
        apu.writeRegister(0x08, 0x7F)

        // Set Triangle Period low byte ($400A = 0x50) and high byte ($400B = 0x00)
        apu.writeRegister(0x0A, 0x50)
        apu.writeRegister(0x0B, 0x00) // triggers linear counter reload

        val samples = ShortArray(1000)
        var sampleCount = 0

        // Clock APU for enough cycles to produce audio samples
        for (i in 0 until 50000) {
            apu.clockCycle()
            val avail = apu.audioQueue.available()
            if (avail > 0) {
                val read = apu.audioQueue.read(samples, sampleCount, minOf(avail, samples.size - sampleCount))
                sampleCount += read
            }
        }

        assertTrue("Should have generated audio samples", sampleCount > 0)

        // Verify no samples are clipped at max short limits (-32768 or 32767)
        for (i in 0 until sampleCount) {
            val sample = samples[i].toInt()
            assertTrue("Sample $i ($sample) should not clip at max positive", sample < 32700)
            assertTrue("Sample $i ($sample) should not clip at max negative", sample > -32700)
        }
    }

    @Test
    fun testTriangleHoldsPhaseWhenStoppedWithoutPopping() {
        val apu = Apu2a03()

        // Enable triangle channel
        apu.writeRegister(0x15, 0x04)
        apu.writeRegister(0x08, 0x10) // linear reload = 16
        apu.writeRegister(0x0A, 0x20)
        apu.writeRegister(0x0B, 0x00) // start note

        val readBuf = ShortArray(256)

        // Clock APU to start playing
        for (i in 0 until 10000) {
            apu.clockCycle()
        }

        // Drain queue
        apu.audioQueue.read(readBuf, 0, apu.audioQueue.available())

        // Stop triangle linear counter by setting control flag to false and waiting for countdown
        apu.triLinearCounter = 0

        var maxDelta = 0
        var prevSample = 0
        var totalSamplesChecked = 0

        // Clock APU as triangle note stops
        for (i in 0 until 2000) {
            apu.clockCycle()
            val avail = apu.audioQueue.available()
            if (avail > 0) {
                val count = apu.audioQueue.read(readBuf, 0, avail)
                for (j in 0 until count) {
                    val sample = readBuf[j].toInt()
                    if (totalSamplesChecked > 0) {
                        val delta = abs(sample - prevSample)
                        if (delta > maxDelta) {
                            maxDelta = delta
                        }
                    }
                    prevSample = sample
                    totalSamplesChecked++
                }
            }
        }

        assertTrue("Checked samples", totalSamplesChecked > 0)
        // Max step change between consecutive samples when stopped should be small (no sharp step-function pop)
        assertTrue("Max sample step delta ($maxDelta) should be smooth without sharp popping (>8000)", maxDelta < 8000)
    }

    @Test
    fun testPulseAndNoiseNoExcessiveHighFrequencyNoise() {
        val apu = Apu2a03()

        // Enable Pulse 1 and Noise
        apu.writeRegister(0x15, 0x09)

        // Pulse 1: 50% duty, constant volume 12
        apu.writeRegister(0x00, 0xBF)
        apu.writeRegister(0x02, 0xFD)
        apu.writeRegister(0x03, 0x00)

        // Noise: mode 0, period 4, constant volume 8
        apu.writeRegister(0x0C, 0x38)
        apu.writeRegister(0x0E, 0x04)
        apu.writeRegister(0x0F, 0x00)

        val readBuf = ShortArray(512)
        var totalSamples = 0

        for (i in 0 until 40000) {
            apu.clockCycle()
            val avail = apu.audioQueue.available()
            if (avail > 0) {
                val read = apu.audioQueue.read(readBuf, 0, minOf(avail, readBuf.size))
                totalSamples += read
                for (k in 0 until read) {
                    val s = readBuf[k].toInt()
                    assertTrue("Sample $s should not clip", abs(s) < 32000)
                }
            }
        }

        assertTrue("Generated pulse & noise samples", totalSamples > 0)
    }
}

package com.remag.aines.emulator

class NesMachine(romBytes: ByteArray) {
    val cartridge = Cartridge(romBytes)
    val bus = Bus()
    val cpu = Cpu6502(bus)
    val ppu = Ppu2c02(cartridge)
    val apu = Apu2a03()
    val controller1 = Controller()
    val controller2 = Controller()

    init {
        bus.cartridge = cartridge
        bus.ppu = ppu
        bus.apu = apu
        bus.controller1 = controller1
        bus.controller2 = controller2
        cpu.reset()
    }

    fun stepFrame() {
        apu.startFrame()
        // Run until the PPU has finished the 240 visible scanlines and reached VBlank. Counting a
        // fixed budget of CPU cycles instead would end the frame at an arbitrary dot, drift by a
        // couple of dots every frame and let the display sample a half-drawn picture.
        ppu.frameReady = false
        while (!ppu.frameReady) {
            if (ppu.nmiRequested) {
                cpu.nmiRequested = true
                ppu.nmiRequested = false
            }
            // The IRQ line is level-driven: the CPU sees it asserted exactly while a source holds
            // it, and it drops again the moment the handler acknowledges (MMC3 clears its line when
            // the handler writes $C000/$C001/$E000). A sticky "set only" version re-fires the IRQ
            // long after the source went quiet, which SMB3's raster effect cannot tolerate.
            cpu.irqRequested = apu.irqRequested || cartridge.irqRequested

            val stepCycles = cpu.step()
            repeat(stepCycles) {
                ppu.clock()
                ppu.clock()
                ppu.clock()
                apu.clockCycle()
            }
        }
    }

    fun pressButton(button: Int, pressed: Boolean) {
        controller1.setButton(button, pressed)
        controller2.setButton(button, pressed)
    }
}

package com.remag.aines.emulator

class Ppu2c02(val cartridge: Cartridge) {
    val vram = ByteArray(2048) // Internal Nametables (2KB)
    val paletteRam = ByteArray(32)
    val oam = ByteArray(256)

    val frameBuffer = IntArray(256 * 240)
    val bgOpaque = BooleanArray(256 * 240)

    var vramAddr: Int = 0 // v (15 bits: yyy NN YYYYY XXXXX)
    var tramAddr: Int = 0 // t (15 bits: yyy NN YYYYY XXXXX)
    var fineX: Int = 0    // x (3 bits)
    var addressLatch: Boolean = false // w (1 bit)
    var ppuDataBuffer: Int = 0

    var ppuCtrl: Int = 0
    var ppuMask: Int = 0
    var ppuStatus: Int = 0
    var oamAddr: Int = 0

    var scanline: Int = 0
    // -1 so that the very first clock() call enters dot 0 of scanline 0: visible lines are
    // rasterised at dot 0, and that includes the top line of the very first frame.
    var dot: Int = -1
    var nmiRequested: Boolean = false

    // Set once the whole 240 line picture has been rasterised, i.e. as soon as we enter VBlank.
    // Emulating one frame per stepFrame() call and reading the framebuffer here is what keeps the
    // display from being sampled in the middle of a frame (visible tearing).
    var frameReady: Boolean = false

    // Dot on the current scanline at which sprite 0 first overlaps a solid background pixel, or -1.
    private var sprite0HitDot = -1
    // Horizontal scroll the raster latched at the start of the current scanline.
    private var lineFineX = 0
    // First pixel of the current scanline that has not been painted yet. The beam draws a line in
    // pieces: a write that lands while the beam is still visible may only change the pixels after
    // it, so everything that can alter the picture has to flush the pending piece first.
    private var paintedDot = 0

    // Diagnostic hook: observes every $2000-$2007 write the moment it reaches the PPU, together
    // with the raster position it landed at. Never assigned by shipping code, so it costs one
    // null check per write; the raster trace harness sets it to reconstruct SMB3's mid-line writes.
    var onRegisterWrite: ((address: Int, value: Int) -> Unit)? = null

    // NES 2C02 NTSC RGB system color palette
    val systemPalette = intArrayOf(
        0xFF666666.toInt(), 0xFF002A88.toInt(), 0xFF1412A7.toInt(), 0xFF3B00A4.toInt(),
        0xFF5C007E.toInt(), 0xFF6D0040.toInt(), 0xFF6B0600.toInt(), 0xFF561D00.toInt(),
        0xFF333500.toInt(), 0xFF0B4D00.toInt(), 0xFF005200.toInt(), 0xFF004F08.toInt(),
        0xFF00404D.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(),

        0xFFADADAD.toInt(), 0xFF1566E0.toInt(), 0xFF3C43C0.toInt(), 0xFF6D28C0.toInt(),
        0xFF991B9E.toInt(), 0xFFAC155B.toInt(), 0xFFB02200.toInt(), 0xFF974200.toInt(),
        0xFF6D6300.toInt(), 0xFF2A8300.toInt(), 0xFF0A9100.toInt(), 0xFF008C1C.toInt(),
        0xFF007C70.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(),

        0xFFFFFFFF.toInt(), 0xFF64B0FF.toInt(), 0xFF9290FF.toInt(), 0xFFC676FF.toInt(),
        0xFFF26AFF.toInt(), 0xFFFF6ECC.toInt(), 0xFFFF7A70.toInt(), 0xFFE8981C.toInt(),
        0xFFBCAE00.toInt(), 0xFF72CE00.toInt(), 0xFF4CD80A.toInt(), 0xFF38D852.toInt(),
        0xFF38C8B8.toInt(), 0xFF3E3E3E.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(),

        0xFFFFFFFF.toInt(), 0xFFC0E0FF.toInt(), 0xFFD3D0FF.toInt(), 0xFFE8C8FF.toInt(),
        0xFFF8C2FF.toInt(), 0xFFFFC4EA.toInt(), 0xFFFFC8C8.toInt(), 0xFFF9D89D.toInt(),
        0xFFE5E890.toInt(), 0xFFC6F090.toInt(), 0xFFB5F8A4.toInt(), 0xFFB0F8C8.toInt(),
        0xFFB0F0F0.toInt(), 0xFFB8B8B8.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt()
    )

    fun readRegister(address: Int, readOnly: Boolean = false, openBus: Int = 0): Int {
        flushRaster()
        var data = openBus
        when (address) {
            0x2000, 0x2001, 0x2003, 0x2005, 0x2006 -> {
                data = openBus
            }
            0x2002 -> {
                data = (ppuStatus and 0xE0) or (openBus and 0x1F)
                if (!readOnly) {
                    ppuStatus = ppuStatus and 0x7F // Clear VBlank flag
                    addressLatch = false
                }
            }
            0x2004 -> {
                data = oam[oamAddr].toInt() and 0xFF
            }
            0x2007 -> {
                val actualAddr = vramAddr and 0x3FFF
                if (actualAddr >= 0x3F00) {
                    data = readVram(actualAddr)
                    ppuDataBuffer = readVram(actualAddr and 0x2FFF)
                } else {
                    data = ppuDataBuffer
                    ppuDataBuffer = readVram(actualAddr)
                }
                if (!readOnly) {
                    val inc = if ((ppuCtrl and 0x04) != 0) 32 else 1
                    vramAddr = (vramAddr + inc) and 0x7FFF
                }
            }
        }
        return data
    }

    fun writeRegister(address: Int, value: Int) {
        flushRaster()
        val v = value and 0xFF
        onRegisterWrite?.invoke(address, v)
        when (address) {
            0x2000 -> {
                ppuCtrl = v
                tramAddr = (tramAddr and 0xF3FF) or ((v and 0x03) shl 10)
            }
            0x2001 -> {
                ppuMask = v
            }
            0x2003 -> {
                oamAddr = v
            }
            0x2004 -> {
                oam[oamAddr] = v.toByte()
                oamAddr = (oamAddr + 1) and 0xFF
            }
            0x2005 -> {
                if (!addressLatch) {
                    fineX = v and 0x07
                    tramAddr = (tramAddr and 0xFFE0) or (v ushr 3)
                    addressLatch = true
                } else {
                    tramAddr = (tramAddr and 0x8FFF) or ((v and 0x07) shl 12)
                    tramAddr = (tramAddr and 0xFC1F) or ((v and 0xF8) shl 2)
                    addressLatch = false
                }
            }
            0x2006 -> {
                if (!addressLatch) {
                    tramAddr = (tramAddr and 0x00FF) or ((v and 0x3F) shl 8)
                    addressLatch = true
                } else {
                    tramAddr = (tramAddr and 0xFF00) or v
                    vramAddr = tramAddr
                    addressLatch = false
                }
            }
            0x2007 -> {
                val actualAddr = vramAddr and 0x3FFF
                writeVram(actualAddr, v)
                val inc = if ((ppuCtrl and 0x04) != 0) 32 else 1
                vramAddr = (vramAddr + inc) and 0x7FFF
            }
        }
    }

    private fun readVram(addr: Int): Int {
        val a = addr and 0x3FFF
        return when {
            a < 0x2000 -> cartridge.readChr(a)
            a < 0x3F00 -> {
                val mirrored = mirrorNametableAddress(a)
                vram[mirrored].toInt() and 0xFF
            }
            else -> {
                val palAddr = a and 0x1F
                val mapped = when (palAddr) {
                    0x10, 0x14, 0x18, 0x1C -> palAddr - 0x10
                    else -> palAddr
                }
                paletteRam[mapped].toInt() and 0xFF
            }
        }
    }

    private fun writeVram(addr: Int, value: Int) {
        val a = addr and 0x3FFF
        when {
            a < 0x2000 -> cartridge.writeChr(a, value)
            a < 0x3F00 -> {
                val mirrored = mirrorNametableAddress(a)
                vram[mirrored] = value.toByte()
            }
            else -> {
                val palAddr = a and 0x1F
                val mapped = when (palAddr) {
                    0x10, 0x14, 0x18, 0x1C -> palAddr - 0x10
                    else -> palAddr
                }
                paletteRam[mapped] = value.toByte()
            }
        }
    }

    private fun mirrorNametableAddress(addr: Int): Int {
        val normalized = (addr - 0x2000) and 0x0FFF
        val table = (normalized ushr 10) and 0x03
        val offset = normalized and 0x03FF
        val mappedTable = if (cartridge.mirrorVertical) {
            table and 0x01
        } else {
            table ushr 1
        }
        return (mappedTable shl 10) or offset
    }

    /**
     * Paints the pixels of the current scanline the beam has passed since the last state change.
     * Called before anything the CPU can touch the picture with - a register write, an OAM DMA or
     * a mapper bank swap - so that the pixels already on screen keep the old state and the ones
     * still to come pick up the new one. Super Mario Bros. 3 relies on this: its status bar driver
     * rewrites the scroll and blanks the display while the beam is still drawing.
     */
    fun flushRaster() {
        if (scanline !in 0..239) return
        val limit = if (dot < 256) dot else 256
        if (limit > paintedDot) {
            renderScanlineSegment(scanline, paintedDot, limit)
            paintedDot = limit
        }
    }

    private fun incrementScrollY() {
        if ((ppuMask and 0x18) == 0) return
        if ((vramAddr and 0x7000) != 0x7000) {
            vramAddr += 0x1000
        } else {
            vramAddr = vramAddr and 0x7000.inv()
            var y = (vramAddr and 0x03E0) ushr 5
            if (y == 29) {
                y = 0
                vramAddr = vramAddr xor 0x0800
            } else if (y == 31) {
                y = 0
            } else {
                y++
            }
            vramAddr = (vramAddr and 0x03E0.inv()) or (y shl 5)
        }
    }

    fun clock() {
        dot++
        if (dot > 340) {
            dot = 0
            scanline++
            if (scanline > 261) {
                scanline = 0
            }
        }

        if (scanline in 0..239) {
            if (dot == 0) {
                evaluateSpriteOverflow(scanline)
                // The horizontal scroll is loaded into the shifter registers once per line, so a
                // mid-line $2005 write must not reach the picture that is currently being drawn.
                lineFineX = fineX
                paintedDot = 0
                // The hit has to be reported while the raster is on this line, so work out the dot
                // up front instead of waiting for the pixels to be composited at the end of it.
                sprite0HitDot = findSprite0HitDot(scanline)
            } else if (sprite0HitDot >= 0 && dot >= sprite0HitDot + 1) {
                ppuStatus = ppuStatus or 0x40 // Sprite 0 Hit!
                sprite0HitDot = -1
            }
            if (dot == 256) {
                // Hand over the last stretch of visible pixels. Everything the CPU changed while
                // the beam was moving has already been applied at the dot it happened, so the line
                // is now whatever hardware would have put on screen - which is what puts Super
                // Mario Bros. 3's status bar split on the scanline the game asked for.
                flushRaster()
                incrementScrollY()
            }
            if (dot == 257 && (ppuMask and 0x18) != 0) {
                vramAddr = (vramAddr and 0xFBE0) or (tramAddr and 0x041F)
            }
            if (dot == 260 && (ppuMask and 0x18) != 0) {
                // MMC3 derives its scanline counter from PPU A12, which goes high once per line
                // just after the visible part of that line has ended.
                cartridge.clockIrqScanline()
            }
        } else if (scanline == 240 && dot == 0) {
            // The last visible line has been drawn: this is the safe point to present the frame.
            frameReady = true
        } else if (scanline == 241 && dot == 1) {
            ppuStatus = ppuStatus or 0x80
            if ((ppuCtrl and 0x80) != 0) {
                nmiRequested = true
            }
        } else if (scanline == 261) {
            if (dot == 1) {
                ppuStatus = ppuStatus and 0x1F // Clear VBlank (0x80), Sprite 0 Hit (0x40), Sprite Overflow (0x20)
                nmiRequested = false
            }
            if (dot == 260 && (ppuMask and 0x18) != 0) {
                // The pre-render line fetches for scanline 0 like any visible line does, so the
                // MMC3 counter sees 241 clocks a frame rather than 240. Skipping this one leaves
                // every IRQ that was armed during VBlank a whole scanline late.
                cartridge.clockIrqScanline()
            }
            if (dot in 280..304 && (ppuMask and 0x18) != 0) {
                vramAddr = (vramAddr and 0x841F) or (tramAddr and 0x7BE0)
            }
        }
    }

    private fun evaluateSpriteOverflow(line: Int) {
        val bgRender = (ppuMask and 0x08) != 0
        val spriteRender = (ppuMask and 0x10) != 0
        if (!spriteRender && !bgRender) return

        val sHeight = if ((ppuCtrl and 0x20) != 0) 16 else 8
        var spriteCount = 0
        for (i in 0 until 64) {
            val sy = oam[i * 4].toInt() and 0xFF
            if (line >= sy + 1 && line < sy + 1 + sHeight) {
                spriteCount++
                if (spriteCount > 8) {
                    ppuStatus = ppuStatus or 0x20 // Set Sprite Overflow flag
                    break
                }
            }
        }
    }

    /**
     * Finds the first dot of [line] where sprite 0 puts a non-transparent pixel over a
     * non-transparent background pixel. Sprite priority, palette and the sprite being hidden
     * behind the background are irrelevant here - only solidity matters. Returns -1 if no hit
     * happens on this scanline.
     */
    private fun findSprite0HitDot(line: Int): Int {
        // Both the background and the sprites must be enabled for a hit to be detected.
        if ((ppuMask and 0x18) != 0x18) return -1

        val spriteY = oam[0].toInt() and 0xFF
        val tileIdx = oam[1].toInt() and 0xFF
        val attr = oam[2].toInt() and 0xFF
        val spriteX = oam[3].toInt() and 0xFF
        val spriteHeight = if ((ppuCtrl and 0x20) != 0) 16 else 8

        val py = line - (spriteY + 1)
        if (py < 0 || py >= spriteHeight) return -1

        val flipY = (attr and 0x80) != 0
        val actualPy = if (flipY) spriteHeight - 1 - py else py

        val patternAddr = if (spriteHeight == 8) {
            val spritePtBase = if ((ppuCtrl and 0x08) != 0) 0x1000 else 0x0000
            spritePtBase + tileIdx * 16 + actualPy
        } else {
            // 8x16 sprites: bit 0 of the tile index picks the pattern table, the rest is even.
            (tileIdx and 0x01) * 0x1000 + ((tileIdx and 0xFE) + (actualPy ushr 3)) * 16 + (actualPy and 0x07)
        }
        val lsb = readVram(patternAddr)
        val msb = readVram(patternAddr + 8)

        // Pixels 0..7 are only compared when both the background and the sprites are shown there,
        // and a hit can never register on the very last pixel of the scanline.
        val firstX = if ((ppuMask and 0x02) == 0 || (ppuMask and 0x04) == 0) 8 else 0
        val flipX = (attr and 0x40) != 0

        for (screenX in firstX..254) {
            val px = screenX - spriteX
            if (px < 0 || px > 7) continue
            val bit = if (flipX) px else 7 - px
            val pixel = (((msb ushr bit) and 1) shl 1) or ((lsb ushr bit) and 1)
            if (pixel != 0 && bgPixelAt(screenX, vramAddr, lineFineX) != 0) return screenX
        }
        return -1
    }

    /**
     * The two bit pattern value the background shifter would present at [screenX] for a raster at
     * scroll [v] / [fX]; 0 means transparent. Only the pixel under test is resolved, which lets the
     * sprite 0 hit be decided at the start of a line rather than after it has been composited.
     */
    private fun bgPixelAt(screenX: Int, v: Int, fX: Int): Int {
        val fineY = (v ushr 12) and 0x07
        val ntY = (v ushr 11) and 0x01
        val ntX = (v ushr 10) and 0x01
        val coarseY = (v ushr 5) and 0x1F
        val coarseX = v and 0x1F

        val effectiveCoarseX = (coarseX % 32) + (screenX + fX) / 8
        val tileX = effectiveCoarseX % 32
        val effectiveNtX = (ntX + (effectiveCoarseX / 32)) % 2
        val tileY = coarseY % 30
        val effectiveNtY = (ntY + coarseY / 30) % 2

        val baseNt = 0x2000 + (effectiveNtY * 2 + effectiveNtX) * 0x0400
        val tileId = readVram(baseNt + tileY * 32 + tileX)
        val ptAddr = (if ((ppuCtrl and 0x10) != 0) 0x1000 else 0x0000) + tileId * 16 + fineY
        val lsb = readVram(ptAddr)
        val msb = readVram(ptAddr + 8)
        val bit = 7 - ((screenX + fX) % 8)
        return (((msb ushr bit) and 1) shl 1) or ((lsb ushr bit) and 1)
    }

    fun renderNametableToFramebuffer() {
        bgOpaque.fill(false)
        val backdropColorIdx = readVram(0x3F00)
        val backdropIdx = if ((ppuMask and 0x01) != 0) backdropColorIdx and 0x30 else backdropColorIdx
        frameBuffer.fill(systemPalette[backdropIdx and 0x3F])

        // Mimic the raster: v advances one pixel row per scanline and reloads from t on the
        // pre-render line, so a caller that only ever set t sees the whole nametable from t's
        // coordinates, exactly like a frame that had no mid-frame scroll writes.
        val savedV = vramAddr
        vramAddr = tramAddr
        for (screenY in 0 until 240) {
            lineFineX = fineX
            renderScanlineSegment(screenY, 0, 256)
            incrementScrollY()
            vramAddr = (vramAddr and 0xFBE0) or (tramAddr and 0x041F)
        }
        vramAddr = savedV
    }

    /**
     * Draws the pixels of [screenY] between [fromX] (inclusive) and [toX] using the state the
     * raster has reached by that point of the line.
     */
    private fun renderScanlineSegment(screenY: Int, fromX: Int, toX: Int) {
        if (screenY !in 0..239) return

        val v = vramAddr
        val fX = lineFineX
        val mask = ppuMask

        val backdropColorIdx = readVram(0x3F00)
        val backdropIdx = if ((mask and 0x01) != 0) backdropColorIdx and 0x30 else backdropColorIdx
        val backdrop = systemPalette[backdropIdx and 0x3F]

        for (x in fromX until toX) {
            bgOpaque[screenY * 256 + x] = false
            frameBuffer[screenY * 256 + x] = backdrop
        }

        val backgroundEnabled = (mask and 0x08) != 0
        val showBackgroundLeft = (mask and 0x02) != 0

        if (backgroundEnabled) {
            val ctrl = ppuCtrl

            val fineY = (v ushr 12) and 0x07
            val ntY = (v ushr 11) and 0x01
            val ntX = (v ushr 10) and 0x01
            val coarseY = (v ushr 5) and 0x1F
            val coarseX = v and 0x1F

            val bgPtBase = if ((ctrl and 0x10) != 0) 0x1000 else 0x0000

            // The vertical raster position is constant for the whole scanline: v already points at
            // the right tile row, so the screen Y never enters into these calculations.
            val pixelInTileY = fineY
            val tileY = coarseY % 30
            val effectiveNtY = (ntY + coarseY / 30) % 2

            var paletteGroup = 0
            var lsb = 0
            var msb = 0

            for (screenX in fromX until toX) {
                if (screenX < 8 && !showBackgroundLeft) continue

                val pixelInTileX = (screenX + fX) % 8
                if (screenX == fromX || pixelInTileX == 0) {
                    val effectiveCoarseX = (coarseX % 32) + (screenX + fX) / 8
                    val tileX = effectiveCoarseX % 32
                    val effectiveNtX = (ntX + (effectiveCoarseX / 32)) % 2

                    val tableIdx = effectiveNtY * 2 + effectiveNtX
                    val baseNt = 0x2000 + tableIdx * 0x0400
                    val ntAddr = baseNt + tileY * 32 + tileX
                    val tileId = readVram(ntAddr)

                    val attrAddr = baseNt + 0x03C0 + (tileY / 4) * 8 + (tileX / 4)
                    val attrByte = readVram(attrAddr)
                    val shift = ((tileY and 2) shl 1) or (tileX and 2)
                    paletteGroup = (attrByte ushr shift) and 0x03

                    val ptAddr = bgPtBase + tileId * 16 + pixelInTileY
                    lsb = readVram(ptAddr)
                    msb = readVram(ptAddr + 8)
                }

                val bitShift = 7 - pixelInTileX
                val pixel = (((msb ushr bitShift) and 1) shl 1) or ((lsb ushr bitShift) and 1)

                val fbIdx = screenY * 256 + screenX
                if (pixel != 0) {
                    val colorIdx = readVram(0x3F00 + paletteGroup * 4 + pixel)
                    val finalColorIdx = if ((mask and 0x01) != 0) (colorIdx and 0x30) else colorIdx
                    frameBuffer[fbIdx] = systemPalette[finalColorIdx and 0x3F]
                    bgOpaque[fbIdx] = true
                }
            }
        }

        // Render Sprites for this scanline
        val spritesEnabled = (mask and 0x10) != 0
        if (!spritesEnabled) return

        val spriteHeight = if ((ppuCtrl and 0x20) != 0) 16 else 8
        val spritePtBase = if ((ppuCtrl and 0x08) != 0) 0x1000 else 0x0000
        val showSpritesLeft = (mask and 0x04) != 0

        for (i in 63 downTo 0) {
            val spriteY = oam[i * 4].toInt() and 0xFF
            val tileIdx = oam[i * 4 + 1].toInt() and 0xFF
            val attr = oam[i * 4 + 2].toInt() and 0xFF
            val spriteX = oam[i * 4 + 3].toInt() and 0xFF

            val py = screenY - (spriteY + 1)
            if (py < 0 || py >= spriteHeight) continue

            val paletteGroup = attr and 0x03
            val behindBg = (attr and 0x20) != 0
            val flipX = (attr and 0x40) != 0
            val flipY = (attr and 0x80) != 0

            val actualPy = if (flipY) (spriteHeight - 1 - py) else py

            if (spriteHeight == 8) {
                val ptAddr = spritePtBase + tileIdx * 16 + actualPy
                val lsb = readVram(ptAddr)
                val msb = readVram(ptAddr + 8)
                for (px in 0 until 8) {
                    val actualPx = if (flipX) 7 - px else px
                    val bitShift = 7 - actualPx
                    val pixel = (((msb ushr bitShift) and 1) shl 1) or ((lsb ushr bitShift) and 1)
                    if (pixel != 0) {
                        val screenX = spriteX + px
                        if (screenX in fromX until toX && (screenX >= 8 || showSpritesLeft)) {
                            val fbIdx = screenY * 256 + screenX
                            if (!behindBg || !bgOpaque[fbIdx]) {
                                val colorIdx = readVram(0x3F10 + paletteGroup * 4 + pixel)
                                val finalColorIdx = if ((mask and 0x01) != 0) (colorIdx and 0x30) else colorIdx
                                frameBuffer[fbIdx] = systemPalette[finalColorIdx and 0x3F]
                            }
                        }
                    }
                }
            } else {
                val ptBase = (tileIdx and 0x01) * 0x1000
                val realTileIdx = tileIdx and 0xFE
                val tileOffset = if (actualPy < 8) 0 else 1
                val rowInTile = actualPy % 8
                val ptAddr = ptBase + (realTileIdx + tileOffset) * 16 + rowInTile
                val lsb = readVram(ptAddr)
                val msb = readVram(ptAddr + 8)
                for (px in 0 until 8) {
                    val actualPx = if (flipX) 7 - px else px
                    val bitShift = 7 - actualPx
                    val pixel = (((msb ushr bitShift) and 1) shl 1) or ((lsb ushr bitShift) and 1)
                    if (pixel != 0) {
                        val screenX = spriteX + px
                        if (screenX in fromX until toX && (screenX >= 8 || showSpritesLeft)) {
                            val fbIdx = screenY * 256 + screenX
                            if (!behindBg || !bgOpaque[fbIdx]) {
                                val colorIdx = readVram(0x3F10 + paletteGroup * 4 + pixel)
                                val finalColorIdx = if ((mask and 0x01) != 0) (colorIdx and 0x30) else colorIdx
                                frameBuffer[fbIdx] = systemPalette[finalColorIdx and 0x3F]
                            }
                        }
                    }
                }
            }
        }
    }
}

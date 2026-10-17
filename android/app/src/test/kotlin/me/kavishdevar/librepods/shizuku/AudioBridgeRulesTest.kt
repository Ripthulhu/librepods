package me.kavishdevar.librepods.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioBridgeRulesTest {
    private fun config(frame480: Boolean, objectType: Int = 39): ByteArray {
        val bits = "11111" + (objectType - 32).toString(2).padStart(6, '0') +
            "0011" + "0010" + (if (frame480) "1" else "0") + "0000"
        return bits.chunked(8).map { it.padEnd(8, '0').toInt(2).toByte() }.toByteArray()
    }

    @Test fun validatesActualEldFrameLength() {
        assertEquals(AudioBridgeRules.EldConfig(48000, 2, 480),
            AudioBridgeRules.parseEldConfig(config(true)))
        AudioBridgeRules.requireAppleEld(AudioBridgeRules.parseEldConfig(config(true)), listOf(480, 480))
        val wrong = AudioBridgeRules.parseEldConfig(config(false))
        assertEquals(512, wrong.frameSamples)
        assertThrows(IllegalArgumentException::class.java) {
            AudioBridgeRules.requireAppleEld(wrong, listOf(512, 512))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioBridgeRules.requireAppleEld(AudioBridgeRules.parseEldConfig(config(true)), listOf(480, 512))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioBridgeRules.requireAppleEld(AudioBridgeRules.parseEldConfig(config(true)), emptyList())
        }
    }

    @Test fun rejectsTruncatedAndDifferentProfiles() {
        assertThrows(IllegalArgumentException::class.java) { AudioBridgeRules.parseEldConfig(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { AudioBridgeRules.parseEldConfig(config(true, 38)) }
    }

    @Test fun refusesOtherCallersAndSystemInjectionTargets() {
        AudioBridgeRules.requireCaller(10123, 10123)
        assertThrows(IllegalArgumentException::class.java) { AudioBridgeRules.requireCaller(10123, 10124) }
        assertThrows(IllegalArgumentException::class.java) { AudioBridgeRules.requireCaller(2000, 2000) }
        assertThrows(IllegalArgumentException::class.java) { AudioBridgeRules.requireTarget(1000) }
        assertThrows(IllegalArgumentException::class.java) { AudioBridgeRules.validateFormat(8000, 1) }
        assertThrows(IllegalArgumentException::class.java) { AudioBridgeRules.validateFormat(48000, 8) }
    }
}

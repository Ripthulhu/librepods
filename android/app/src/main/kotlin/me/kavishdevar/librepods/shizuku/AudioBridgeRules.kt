package me.kavishdevar.librepods.shizuku

/** These checks also run in the shell process; Binder parameters are not trusted. */
internal object AudioBridgeRules {
    fun validateFormat(sampleRate: Int, channels: Int) {
        require(sampleRate in setOf(16000, 24000, 32000, 44100, 48000, 64000)) {
            "Unsupported PCM sample rate: $sampleRate"
        }
        require(channels in 1..2) { "PCM must have one or two channels" }
    }

    fun requireCaller(expectedUid: Int, callingUid: Int) {
        require(expectedUid >= 10000 && callingUid == expectedUid) {
            "Audio bridge caller does not own LibrePods"
        }
    }

    fun requireTarget(uid: Int) {
        require(uid >= 10000) { "Choose an installed user application as the recording target" }
    }

    data class EldConfig(val sampleRate: Int, val channels: Int, val frameSamples: Int)

    fun parseEldConfig(bytes: ByteArray): EldConfig {
        var bit = 0
        fun read(count: Int): Int {
            require(bit + count <= bytes.size * 8) { "Truncated AAC configuration" }
            var value = 0
            repeat(count) {
                value = (value shl 1) or ((bytes[bit / 8].toInt() ushr (7 - bit % 8)) and 1)
                bit++
            }
            return value
        }
        var objectType = read(5)
        if (objectType == 31) objectType = 32 + read(6)
        require(objectType == 39) { "Encoder did not return AAC-ELD (object type $objectType)" }
        val index = read(4)
        val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000,
            22050, 16000, 12000, 11025, 8000, 7350)
        val rate = if (index == 15) read(24) else {
            require(index in rates.indices) { "Reserved AAC sample rate" }
            rates[index]
        }
        val channels = read(4)
        require(channels in 1..2) { "AAC-ELD must be mono or stereo" }
        val frameSamples = if (read(1) == 1) 480 else 512
        return EldConfig(rate, channels, frameSamples)
    }

    fun requireAppleEld(config: EldConfig, decodedFrames: List<Int>) {
        require(config == EldConfig(48000, 2, 480)) {
            "Apple playback requires real 48 kHz stereo 480-sample ELD; encoder returned $config"
        }
        require(decodedFrames.size >= 2 && decodedFrames.all { it == 480 }) {
            "ELD decoder must confirm 480 PCM samples per encoded frame: $decodedFrames"
        }
    }
}

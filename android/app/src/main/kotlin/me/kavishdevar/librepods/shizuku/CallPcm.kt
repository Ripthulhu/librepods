package me.kavishdevar.librepods.shizuku

/** A call downlink is mono; the selected media output remains stereo. */
internal object CallPcm {
    fun stereoFromMono(input: ByteArray, count: Int, output: ByteArray): Int {
        require(count in 0..input.size && count % 2 == 0 && count <= output.size / 2) {
            "Invalid mono PCM16 block"
        }
        for (index in 0 until count step 2) {
            output[index * 2] = input[index]
            output[index * 2 + 1] = input[index + 1]
            output[index * 2 + 2] = input[index]
            output[index * 2 + 3] = input[index + 1]
        }
        return count * 2
    }

    fun isSelectedOutput(type: Int, address: String, selected: String) =
        type == 8 && address.equals(selected, ignoreCase = true) // TYPE_BLUETOOTH_A2DP
}

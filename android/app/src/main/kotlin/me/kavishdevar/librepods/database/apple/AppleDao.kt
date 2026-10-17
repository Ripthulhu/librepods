package me.kavishdevar.librepods.database.apple

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.data.apple.AppleCache
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings

@Dao
interface AppleDao {

    @Query("SELECT * FROM AppleEntity WHERE macAddress = :macAddress")
    suspend fun get(macAddress: MacAddress): AppleEntity?

    @Query("SELECT * FROM AppleEntity")
    suspend fun getAll(): List<AppleEntity>

    @Upsert
    suspend fun upsert(device: AppleEntity)

    @Transaction
    suspend fun updateSettings(macAddress: MacAddress, settings: AppleSettings) {
        val device = get(macAddress)?: AppleEntity(macAddress = macAddress, settings = settings, metadata = AppleMetadata(), cache = AppleCache())
        upsert(device.copy(settings = settings))
    }

    @Transaction
    suspend fun updateMetadata(macAddress: MacAddress, metadata: AppleMetadata) {
        val device = get(macAddress)?: AppleEntity(macAddress = macAddress, settings = AppleSettings(), metadata = metadata, cache = AppleCache())
        upsert(device.copy(metadata = metadata))
    }

    @Transaction
    suspend fun saveCache(macAddress: MacAddress, cache: AppleCache) {
        val device = get(macAddress)?: AppleEntity(macAddress = macAddress, settings = AppleSettings(), metadata = AppleMetadata(), cache = cache)
        upsert(device.copy(cache = cache))
    }
}

package me.kavishdevar.librepods.database.apple

import android.app.Application
import androidx.room3.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.core.data.apple.AppleCache
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.database.LibrePodsDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AppleDaoTest {
    @Test fun allowOffChoiceStaysWithItsPairedIdentityAcrossOtherColumnUpdates() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            LibrePodsDatabase::class.java).build()
        try {
            val dao = database.appleDao()
            val first = MacAddress("12:34:56:78:9A:BC")
            val second = MacAddress("12:34:56:78:9A:BD")
            dao.updateSettings(first, AppleSettings(allowOffChoice = true))
            dao.updateSettings(second, AppleSettings(allowOffChoice = false))
            dao.updateMetadata(first, AppleMetadata(name = "First pair"))
            dao.saveCache(first, AppleCache())
            assertEquals(true, dao.get(first)?.settings?.allowOffChoice)
            assertEquals(false, dao.get(second)?.settings?.allowOffChoice)
            assertEquals("First pair", dao.get(first)?.metadata?.name)
        } finally {
            database.close()
        }
    }

    @Test fun concurrentMetadataSettingsAndKeyWritesPreserveAllThreeColumns() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            LibrePodsDatabase::class.java).build()
        try {
            withTimeout(20_000) {
                val dao = database.appleDao()
                val address = MacAddress("12:34:56:78:9A:BC")
                // The first round also exercises concurrent creation of a previously absent row.
                for (revision in 1..12) {
                    val metadata = AppleMetadata(name = "revision-$revision")
                    val settings = AppleSettings(conversationAwarenessVolume = revision.toFloat())
                    val key = ByteArray(16) { revision.toByte() }
                    val cache = AppleCache(magicKeys = mapOf(MagicKeyType.IRK to key))
                    coroutineScope {
                        val start = CompletableDeferred<Unit>()
                        val writes = listOf(
                            async(Dispatchers.IO) { start.await(); dao.updateMetadata(address, metadata) },
                            async(Dispatchers.IO) { start.await(); dao.updateSettings(address, settings) },
                            async(Dispatchers.IO) { start.await(); dao.saveCache(address, cache) },
                        )
                        start.complete(Unit)
                        writes.awaitAll()
                    }
                    val saved = requireNotNull(dao.get(address))
                    assertEquals(metadata, saved.metadata)
                    assertEquals(settings, saved.settings)
                    assertArrayEquals(key, saved.cache.magicKeys[MagicKeyType.IRK])
                }
            }
        } finally {
            database.close()
        }
    }
}

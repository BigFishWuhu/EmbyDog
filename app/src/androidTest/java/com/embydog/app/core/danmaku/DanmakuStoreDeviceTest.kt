package com.embydog.app.core.danmaku

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.embydog.app.core.security.TokenCipher
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DanmakuStoreDeviceTest {
    @Test fun credentialsAreEncryptedAndPreferencesAndBindingsSurviveRecreation(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "danmaku-test-${System.nanoTime()}.preferences_pb")
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val data = PreferenceDataStoreFactory.create(scope = owner, produceFile = { file })
            val json = Json { ignoreUnknownKeys = true }
            val store = DanmakuStore(data, TokenCipher(), json)
            store.saveCredentials("test-app", "fixture-secret-not-a-live-key")
            store.saveDisplay(DanmakuDisplay(opacity = 0.4f, area = 0.75f, offsetMs = 2500, showTop = false))
            val first = danmakuScope("https://server.example", "first-user", "42")
            val second = danmakuScope("https://server.example", "second-user", "42")
            val episode = DanmakuEpisode(123450001L, "测试动画", "第1集")
            store.bind(first, episode)
            assertEquals(episode, store.binding(first)); assertNull(store.binding(second))
            assertFalse("AppSecret must not be written in plaintext", file.readText().contains("fixture-secret-not-a-live-key"))
            val restored = DanmakuStore(data, TokenCipher(), json)
            assertEquals(DanmakuCredentials("test-app", "fixture-secret-not-a-live-key"), restored.credentials.first())
            assertEquals(0.4f, restored.display.first().opacity)
            assertEquals(2500L, restored.display.first().offsetMs)
            assertFalse(restored.display.first().showTop)
            assertEquals(episode, restored.binding(first))
            restored.bind(first, null); assertNull(restored.binding(first))
            restored.saveCredentials("", ""); assertFalse(restored.credentials.first().configured)
        } finally { owner.cancel(); owner.coroutineContext[Job]?.join(); file.delete() }
    }
}

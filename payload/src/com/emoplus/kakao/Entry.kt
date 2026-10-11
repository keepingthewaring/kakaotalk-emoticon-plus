package com.emoplus.kakao

import android.content.Context
import com.revanced.kakaotalk.modern.EmoticonPlusFeature
import com.revanced.kakaotalk.modern.ModernHookRuntime
import com.revanced.kakaotalk.modern.PlusAllTabFeature
import com.revanced.kakaotalk.modern.SpecHooker
import com.revanced.kakaotalk.modern.dex.DexIndex
import top.canyie.pine.Pine
import top.canyie.pine.PineConfig

object Entry {
    @Volatile private var installed = false

    @JvmStatic
    fun init(app: Context, pinePath: String) {
        if (installed) return
        installed = true
        runCatching {
            PineConfig.libLoader = Pine.LibLoader { System.load(pinePath) }
            Pine.ensureInitialized()

            val info = app.applicationInfo
            val paths = buildList {
                info?.sourceDir?.let(::add)
                info?.publicSourceDir?.let(::add)
                info?.splitSourceDirs?.forEach(::add)
            }.distinct()
            if (paths.isEmpty()) return

            val dex = runCatching { DexIndex.load(paths) }.getOrNull() ?: return
            try {
                val runtime = ModernHookRuntime(app.classLoader)
                val hooker = SpecHooker(runtime, dex)
                EmoticonPlusFeature.install(hooker)
                PlusAllTabFeature.install(hooker)
            } finally {
                dex.release()
            }
        }
    }
}

package com.kingzcheung.xime.model

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object ModelRuntime {

    private const val TAG = "ModelRuntime"

    private data class Registration(
        val loader: suspend () -> Boolean,
        val releaser: () -> Unit,
        val label: String
    )

    private class State {
        @Volatile var refCount = 0
        @Volatile var loaded = false
        @Volatile var hot = false
        val mutex = Mutex()
    }

    private val registry = ConcurrentHashMap<String, Registration>()
    private val states = ConcurrentHashMap<String, State>()
    private var attached = false

    fun attach(context: Context) {
        if (attached) return
        attached = true
        (context.applicationContext as Application).registerComponentCallbacks(trimCallbacks)
        FileLogger.i(TAG, "ModelRuntime attached")
    }

    fun register(
        id: String,
        loader: suspend () -> Boolean,
        releaser: () -> Unit,
        label: String = id
    ) {
        if (registry.containsKey(id)) {
            FileLogger.w(TAG, "Model '$id' already registered, skipping")
            return
        }
        registry[id] = Registration(loader, releaser, label)
        FileLogger.i(TAG, "Registered model: $label ($id)")
    }

    fun markLoaded(id: String) {
        val state = states.getOrPut(id) { State() }
        if (!state.loaded) {
            state.loaded = true
            state.refCount = 1
        }
    }

    fun markUnloaded(id: String) {
        states[id]?.let {
            it.loaded = false
            it.refCount = 0
        }
    }

    suspend fun load(id: String): Boolean {
        val reg = registry[id] ?: run {
            FileLogger.w(TAG, "load: unknown model '$id'")
            return false
        }
        val state = states.getOrPut(id) { State() }
        state.mutex.withLock {
            if (state.loaded) {
                state.refCount++
                FileLogger.d(TAG, "${reg.label}: already loaded (refCount=${state.refCount})")
                return true
            }
            FileLogger.i(TAG, "${reg.label}: loading...")
            state.loaded = try {
                reg.loader()
            } catch (e: Exception) {
                FileLogger.e(TAG, "${reg.label}: load failed", e)
                false
            }
            if (state.loaded) {
                state.refCount = 1
                FileLogger.i(TAG, "${reg.label}: loaded (refCount=1)")
            }
            return state.loaded
        }
    }

    fun tryLoad(id: String): Boolean {
        val state = states[id] ?: return false
        if (!state.loaded) return false
        state.refCount++
        return true
    }

    fun unload(id: String) {
        val state = states[id] ?: return
        val reg = registry[id] ?: return

        state.refCount = (state.refCount - 1).coerceAtLeast(0)
        if (state.refCount == 0 && !state.hot && state.loaded) {
            FileLogger.i(TAG, "${reg.label}: releasing (refCount=0, !hot)")
            try {
                reg.releaser()
            } catch (e: Exception) {
                FileLogger.e(TAG, "${reg.label}: release failed", e)
            }
            state.loaded = false
        }
    }

    fun keepWarm(id: String) {
        states[id]?.hot = true
    }

    fun releaseWarm(id: String) {
        states[id]?.hot = false
    }

    fun isLoaded(id: String): Boolean = states[id]?.loaded ?: false

    fun getLoadedModels(): Map<String, Boolean> =
        states.filter { it.value.loaded }.mapValues { it.value.hot }

    fun onTrimMemory(level: Int) {
        val shouldRelease = level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
        if (!shouldRelease) return

        val toRelease = states.filter { (_, state) ->
            state.loaded && !state.hot
        }

        if (toRelease.isEmpty()) return

        FileLogger.i(TAG, "onTrimMemory($level): releasing ${toRelease.size} model(s)")
        toRelease.forEach { (id, state) ->
            val reg = registry[id]
            FileLogger.i(TAG, "  releasing: ${reg?.label ?: id}")
            try {
                reg?.releaser?.invoke()
            } catch (e: Exception) {
                FileLogger.e(TAG, "  release failed: ${reg?.label ?: id}", e)
            }
            state.loaded = false
            state.refCount = 0
        }
    }

    private val trimCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            this@ModelRuntime.onTrimMemory(level)
        }

        override fun onLowMemory() {
            onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}
    }
}

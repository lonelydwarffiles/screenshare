package com.screenshare

import android.app.Application
import android.util.Log

/**
 * Thin wrapper around the Lovense Android SDK.
 *
 * **Setup (required before use):**
 *  1. Download the Lovense SDK AAR from https://developer.lovense.com/#android-sdk
 *  2. Place `lovense.aar` in `android/app/libs/`
 *  3. Set [LOVENSE_DEV_TOKEN] in [ScreenShareApp] with your token from
 *     https://www.lovense.com/user/developer/info
 *
 * All calls are wrapped in try/catch so the app degrades gracefully if no toys are connected.
 */
class LovenseManager private constructor(private val app: Application) {

    private val lovense get() = com.xtremeprog.sdk.Lovense.getInstance(app)
    private val connectedToyIds = mutableListOf<String>()

    /**
     * Initialises the Lovense SDK with your developer token.
     * Call once at app startup from [ScreenShareApp.onCreate].
     */
    fun init(devToken: String) {
        try {
            lovense.setDeveloperToken(devToken)
            Log.d(TAG, "Lovense SDK initialised")
        } catch (e: Throwable) {
            Log.e(TAG, "Lovense init failed — ensure lovense.aar is in app/libs/", e)
        }
    }

    /**
     * Starts a Bluetooth scan for nearby Lovense toys.
     *
     * @param onFound  Called for each discovered toy with (toyId, toyName).
     * @param onDone   Called when the scan finishes.
     */
    fun startScan(
        onFound: (toyId: String, toyName: String) -> Unit = { _, _ -> },
        onDone: () -> Unit = {},
    ) {
        try {
            lovense.searchToys(object : com.xtremeprog.sdk.callback.OnSearchToyListener() {
                override fun onSearchToy(toy: com.xtremeprog.sdk.bean.LovenseToy) {
                    onFound(toy.id, toy.name ?: toy.id)
                }
                override fun finishSearch() { onDone() }
                override fun onError(err: com.xtremeprog.sdk.error.LovenseError) {
                    Log.e(TAG, "Scan error: ${err.message}")
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "startScan failed", e)
        }
    }

    /** Stops any ongoing toy scan. */
    fun stopScan() {
        runCatching { lovense.stopSearching() }
    }

    /**
     * Connects to a toy discovered during scanning.
     * The toy is tracked internally once [com.xtremeprog.sdk.bean.LovenseToy.SERVICE_DISCOVERED].
     */
    fun connectToy(toyId: String) {
        try {
            lovense.connectToy(toyId, object : com.xtremeprog.sdk.callback.OnConnectListener() {
                override fun onConnect(id: String, status: String) {
                    when (status) {
                        com.xtremeprog.sdk.bean.LovenseToy.SERVICE_DISCOVERED,
                        com.xtremeprog.sdk.bean.LovenseToy.STATE_CONNECTED -> {
                            if (!connectedToyIds.contains(id)) connectedToyIds.add(id)
                            Log.d(TAG, "Toy connected: $id")
                        }
                        com.xtremeprog.sdk.bean.LovenseToy.STATE_FAILED -> {
                            connectedToyIds.remove(id)
                            Log.w(TAG, "Toy connection failed: $id")
                        }
                    }
                }
                override fun onError(err: com.xtremeprog.sdk.error.LovenseError) {
                    Log.e(TAG, "connectToy error: ${err.message}")
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "connectToy failed", e)
        }
    }

    /** Disconnects a toy and removes it from the tracking list. */
    fun disconnectToy(toyId: String) {
        connectedToyIds.remove(toyId)
        runCatching { lovense.disconnect(toyId) }
    }

    /**
     * Sends a vibration command to all connected toys.
     *
     * @param level Vibration intensity 0–20 (0 = stop).
     */
    fun vibrate(level: Int) {
        if (connectedToyIds.isEmpty()) return
        val clamped = level.coerceIn(0, 20)
        connectedToyIds.toList().forEach { id ->
            runCatching {
                lovense.sendCommand(
                    id,
                    com.xtremeprog.sdk.bean.LovenseToy.COMMAND_VIBRATE,
                    clamped
                )
            }
        }
    }

    /** Stops all connected toys. */
    fun stopAll() {
        connectedToyIds.toList().forEach { id ->
            runCatching {
                lovense.sendCommand(id, com.xtremeprog.sdk.bean.LovenseToy.COMMAND_VIBRATE, 0)
            }
        }
    }

    /** Returns `true` if at least one toy is tracked as connected. */
    fun hasConnectedToys(): Boolean = connectedToyIds.isNotEmpty()

    companion object {
        private const val TAG = "LovenseManager"

        @Volatile private var instance: LovenseManager? = null

        fun getInstance(app: Application): LovenseManager =
            instance ?: synchronized(this) {
                instance ?: LovenseManager(app).also { instance = it }
            }
    }
}

package me.routerfor.cpa

import android.app.Application

/**
 * Loads the Go kernel before any binding is touched. HotSoLoader decides which kernel:
 * the packaged one by default, or a verified staged one when the user enabled 内核热更新.
 */
class CpaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HotSoLoader.loadEngine(this)
        // Housekeeping off the main thread: downloaded APKs from an earlier update are dead
        // weight (about 23 MB each) and nothing else ever deletes them.
        UpdateManager(this).reclaimStorage()
    }
}

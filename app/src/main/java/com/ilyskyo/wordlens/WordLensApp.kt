package com.ilyskyo.wordlens

import android.app.Application
import android.util.Log

class WordLensApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "WordLens started, filesDir=$filesDir")
    }

    private companion object {
        const val TAG = "WordLensApp"
    }
}

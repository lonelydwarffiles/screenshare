package com.screenshare

import android.app.Application

class ScreenShareApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // The token is read from BuildConfig, which is injected from local.properties.
        // See android/app/build.gradle for how LOVENSE_DEV_TOKEN is defined.
        LovenseManager.getInstance(this).init(BuildConfig.LOVENSE_DEV_TOKEN)
    }
}

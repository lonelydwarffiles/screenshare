package com.screenshare

import android.app.Application

class ScreenShareApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // TODO: Replace with your Lovense developer token obtained from
        //  https://www.lovense.com/user/developer/info
        // After downloading the Lovense SDK AAR (lovense.aar), place it in android/app/libs/
        // and set your real token here before building.
        LovenseManager.getInstance(this).init(LOVENSE_DEV_TOKEN)
    }

    companion object {
        private const val LOVENSE_DEV_TOKEN = "YOUR_LOVENSE_DEV_TOKEN"
    }
}

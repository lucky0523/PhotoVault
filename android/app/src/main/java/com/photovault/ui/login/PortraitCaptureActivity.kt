package com.photovault.ui.login

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * ZXing's default [CaptureActivity] is locked to landscape. This subclass exists
 * only so we can register it in the manifest with
 * `android:screenOrientation="portrait"`, giving the server-QR scanner a
 * portrait viewfinder that matches the rest of the app.
 */
class PortraitCaptureActivity : CaptureActivity()

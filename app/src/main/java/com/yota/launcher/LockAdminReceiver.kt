package com.yota.launcher

import android.app.admin.DeviceAdminReceiver

/**
 * Minimal device admin used only as a lock fallback on non-Yota devices.
 */
class LockAdminReceiver : DeviceAdminReceiver()

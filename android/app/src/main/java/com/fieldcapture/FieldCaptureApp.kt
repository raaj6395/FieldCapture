package com.fieldcapture

import android.app.Application

class FieldCaptureApp : Application() {
  val session by lazy { JobSession(this) }
}

package ai.iqworks.consentiq.example

import ai.iqworks.consentiq.ConsentIQ
import ai.iqworks.consentiq.ConsentIQConfig
import android.app.Application
import android.content.pm.ApplicationInfo

class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ConsentIQ.init(
            this,
            ConsentIQConfig(
                propertyKey = "YOUR_ANDROID_PROPERTY_KEY",
                debug = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            ),
        )
    }
}

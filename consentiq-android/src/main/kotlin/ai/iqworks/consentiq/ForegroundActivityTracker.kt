package ai.iqworks.consentiq

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

internal class ForegroundActivityTracker : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var resumed: WeakReference<Activity>? = null
    private val destroyListeners = CopyOnWriteArrayList<(Activity) -> Unit>()

    val current: Activity?
        get() = resumed?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    fun addDestroyListener(listener: (Activity) -> Unit) {
        destroyListeners += listener
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
        for (listener in destroyListeners) listener(activity)
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {}

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) {}
}

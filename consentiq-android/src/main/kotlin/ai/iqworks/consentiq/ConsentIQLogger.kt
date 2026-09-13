package ai.iqworks.consentiq

import android.util.Log

public interface ConsentIQLogger {
    public fun debug(message: String)

    public fun warn(message: String)

    public fun error(message: String)

    public companion object {
        public val NONE: ConsentIQLogger =
            object : ConsentIQLogger {
                override fun debug(message: String) {}

                override fun warn(message: String) {}

                override fun error(message: String) {}
            }
    }
}

internal class AndroidLogger(
    private val debug: Boolean,
) : ConsentIQLogger {
    override fun debug(message: String) {
        if (debug) Log.d(TAG, message)
    }

    override fun warn(message: String) {
        Log.w(TAG, message)
    }

    override fun error(message: String) {
        Log.e(TAG, message)
    }

    private companion object {
        const val TAG = "ConsentIQ"
    }
}

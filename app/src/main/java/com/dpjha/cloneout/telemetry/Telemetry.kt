package com.dpjha.cloneout.telemetry

import android.app.Application
import android.os.Bundle
import android.util.Log
import com.dpjha.cloneout.BuildConfig
import com.dpjha.cloneout.data.Prefs
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * One safe doorway to Firebase Analytics and Crashlytics.
 *
 * - Never throws: every call is guarded, so telemetry can never crash or slow the app.
 * - Does nothing when the build has no Firebase config (no google-services.json), in debug builds,
 *   until the ad/privacy consent flow allows it, and when the user switched "Share anonymous usage
 *   data" off in Settings.
 * - Never receives file names, paths or contents: callers pass counts, sizes, kinds and codes only.
 */
object Telemetry {

    private const val TAG = "Telemetry"
    private const val MAX_BUFFER = 40

    private var app: Application? = null
    private var analytics: FirebaseAnalytics? = null
    private var crashlytics: FirebaseCrashlytics? = null

    private var consentKnown = false
    private var consentGiven = false
    private var userOptIn = true
    private var active = false
    private var plan = "free"

    /** Events fired before the consent answer is known; sent once allowed, dropped otherwise. */
    private val pending = ArrayDeque<() -> Unit>()

    /** Call once, early (MainActivity.onCreate). Safe to call again. */
    @Synchronized
    fun init(application: Application) {
        if (app != null) return
        app = application
        try {
            userOptIn = Prefs(application).shareUsage
        } catch (e: Exception) {
            userOptIn = true
        }
        try {
            if (FirebaseApp.getApps(application).isNotEmpty()) {
                analytics = FirebaseAnalytics.getInstance(application)
                crashlytics = FirebaseCrashlytics.getInstance()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firebase not available: ${e.message}")
        }
        apply()
    }

    /** Result of the consent flow: true when ads (and with them measurement) may be used. */
    @Synchronized
    fun setConsent(allowed: Boolean) {
        consentKnown = true
        consentGiven = allowed
        apply()
    }

    @Synchronized
    fun setShareUsage(on: Boolean) {
        userOptIn = on
        apply()
    }

    /** "free" or "pro": attached to every event and crash report. */
    @Synchronized
    fun setPlan(isPro: Boolean) {
        plan = if (isPro) "pro" else "free"
        if (!active) return
        guard {
            analytics?.setUserProperty("plan", plan)
            crashlytics?.setCustomKey("plan", plan)
        }
    }

    @Synchronized
    private fun apply() {
        val wasActive = active
        active = !BuildConfig.DEBUG && analytics != null && consentGiven && userOptIn
        guard {
            analytics?.setAnalyticsCollectionEnabled(active)
            crashlytics?.setCrashlyticsCollectionEnabled(active)
        }
        if (active && !wasActive) {
            guard {
                analytics?.setUserProperty("plan", plan)
                crashlytics?.setCustomKey("plan", plan)
            }
            while (pending.isNotEmpty()) guard { pending.removeFirst().invoke() }
        }
        // Opted out, or consent was refused: nothing buffered may be sent later.
        if (!userOptIn || (consentKnown && !consentGiven)) pending.clear()
        if (!active && wasActive) guard { analytics?.resetAnalyticsData() }
    }

    /** Logs an event. Params: Int/Long/Double/Boolean/String values only; no file names or paths. */
    fun event(name: String, vararg params: Pair<String, Any?>) {
        if (BuildConfig.DEBUG) Log.d(TAG, "$name ${params.joinToString { "${it.first}=${it.second}" }}")
        val task: () -> Unit = { send(name, params) }
        synchronized(this) { dispatch(task) }
    }

    private fun dispatch(task: () -> Unit) {
        if (active) {
            task()
        } else if (!consentKnown && userOptIn && analytics != null && !BuildConfig.DEBUG) {
            if (pending.size >= MAX_BUFFER) pending.removeFirst()
            pending.addLast(task)
        }
    }

    /** A screen was shown. */
    fun screen(name: String) = event("screen_view", "screen_name" to name, "screen_class" to name)

    /**
     * Records a handled problem as a non-fatal in Crashlytics, plus an `app_error` event so it also
     * shows up in Analytics. [where] says what the app was doing, e.g. "scan" or "clean".
     */
    fun error(where: String, t: Throwable) {
        if (BuildConfig.DEBUG) Log.w(TAG, "error in $where", t)
        synchronized(this) {
            if (!active) return
            guard {
                crashlytics?.setCustomKey("where", where)
                // Exception messages can contain file paths, so only the type and stack trace are sent.
                crashlytics?.recordException(RuntimeException(t.javaClass.name).also { it.stackTrace = t.stackTrace })
            }
        }
        event("app_error", "where" to where, "type" to t.javaClass.simpleName)
    }

    /** Short breadcrumb that is attached to the next crash report. */
    fun breadcrumb(text: String) {
        synchronized(this) {
            if (!active) return
            guard { crashlytics?.log(text.take(200)) }
        }
    }

    private fun send(name: String, params: Array<out Pair<String, Any?>>) {
        guard {
            val b = Bundle()
            for ((k, v) in params) {
                val key = k.take(40)
                when (v) {
                    null -> Unit
                    is Boolean -> b.putLong(key, if (v) 1L else 0L)
                    is Int -> b.putLong(key, v.toLong())
                    is Long -> b.putLong(key, v)
                    is Float -> b.putDouble(key, v.toDouble())
                    is Double -> b.putDouble(key, v)
                    else -> b.putString(key, v.toString().take(100))
                }
            }
            analytics?.logEvent(name.take(40), b)
            crashlytics?.log("$name ${params.joinToString(" ") { "${it.first}=${it.second}" }}".take(200))
        }
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Log.w(TAG, "telemetry call failed: ${e.message}")
        }
    }
}

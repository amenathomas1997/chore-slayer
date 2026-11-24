package com.at365.choretracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The FCM token for this device and what it should receive (a kid's
 * reminders, parent alerts). Every Firebase call is guarded: without
 * google-services.json in the build, Firebase never starts and push is off.
 */
object Push {
    private const val PREFS = "chore_push"

    /** Set by the app model so a refreshed token can be registered again. */
    var onTokenChange: ((new: String, old: String?) -> Unit)? = null

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun available(c: Context): Boolean =
        runCatching { FirebaseApp.getApps(c).isNotEmpty() || FirebaseApp.initializeApp(c) != null }.getOrDefault(false)

    fun token(c: Context): String? = prefs(c).getString("token", null)
    fun childId(c: Context): String? = prefs(c).getString("child", null)
    fun setChildId(c: Context, id: String?) = prefs(c).edit().putString("child", id).apply()
    fun parentAlerts(c: Context): Boolean = prefs(c).getBoolean("parent", false)
    fun setParentAlerts(c: Context, on: Boolean) = prefs(c).edit().putBoolean("parent", on).apply()
    fun clearChoices(c: Context) = prefs(c).edit().remove("child").remove("parent").apply()

    /** Can this app post notifications right now (permission + not blocked in Settings)? */
    fun allowed(c: Context): Boolean {
        val perm = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return perm && NotificationManagerCompat.from(c).areNotificationsEnabled()
    }

    /** Fetch the FCM token (null if Firebase is off or it takes too long). */
    suspend fun fetchToken(c: Context): String? {
        if (!available(c)) return null
        val t = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    FirebaseMessaging.getInstance().token
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resume(null) }
                }.onFailure { cont.resume(null) }
            }
        }
        if (t != null) received(c, t)
        return t ?: token(c)
    }

    fun received(c: Context, new: String) {
        val old = token(c)
        prefs(c).edit().putString("token", new).apply()
        if (old != new) onTokenChange?.invoke(new, old)
    }

    // ---- notifications ----

    const val CHANNEL = "chores"

    fun createChannel(c: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        c.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Chore reminders and alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Chore reminders for kids, and alerts for parents." })
    }

    fun show(c: Context, title: String, body: String, tag: String) {
        if (!allowed(c)) return
        createChannel(c)
        val open = PendingIntent.getActivity(
            c, tag.hashCode(),
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_chore)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(c).notify(tag, 1, n) }
    }
}

/** Receives Chore Slayer's FCM data messages (title, body, type, childId) and shows them. */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Push.received(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        val title = d["title"] ?: return
        val body = d["body"] ?: ""
        val tag = (d["type"] ?: "chore") + "-" + (d["childId"] ?: "") + "-" + title.hashCode()
        Push.show(applicationContext, title, body, tag)
    }
}

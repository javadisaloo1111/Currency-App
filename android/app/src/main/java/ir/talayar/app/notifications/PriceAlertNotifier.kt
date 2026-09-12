package ir.talayar.app.notifications

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
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.talayar.app.MainActivity
import ir.talayar.app.R
import ir.talayar.app.core.Formatters
import ir.talayar.app.domain.model.TriggeredAlert
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts price alert notifications.
 *
 * The channel + permission flow live here so future alert types
 * (strong moves, threshold crossings, daily digests) plug in without
 * touching the evaluation pipeline.
 */
@Singleton
class PriceAlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_price_alerts),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_price_alerts_desc)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun notifyAlert(triggered: TriggeredAlert) {
        if (!canPostNotifications()) return
        ensureChannel()

        val contentIntent = PendingIntent.getActivity(
            context,
            triggered.rule.symbol.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_SYMBOL, triggered.rule.symbol)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val priceText = Formatters.price(triggered.asset.price) + " تومان"
        val message = when (triggered.rule.kind.id) {
            "above" -> "قیمت ${triggered.asset.name} به $priceText رسید (حد ${Formatters.price(triggered.rule.threshold)} تومان)."
            "below" -> "قیمت ${triggered.asset.name} به $priceText رسید (حد ${Formatters.price(triggered.rule.threshold)} تومان)."
            else -> "قیمت ${triggered.asset.name} بیش از ${Formatters.toPersianDigits(
                triggered.rule.threshold.toString(),
            )}٪ تغییر داشت."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_alert_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID_BASE + triggered.rule.id.toInt(), notification)
        } catch (_: SecurityException) {
            // permission revoked between check and post — ignore safely
        }
    }

    companion object {
        const val CHANNEL_ID = "price_alerts"
        const val EXTRA_SYMBOL = "extra_symbol"
        private const val NOTIFICATION_ID_BASE = 10_000
    }
}

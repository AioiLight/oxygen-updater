package com.oxygenupdater.workers

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.PRIORITY_HIGH
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.oxygenupdater.OxygenUpdater
import com.oxygenupdater.R
import com.oxygenupdater.activities.MainActivity
import com.oxygenupdater.activities.NewsItemActivity
import com.oxygenupdater.database.LocalAppDb
import com.oxygenupdater.enums.NotificationElement
import com.oxygenupdater.enums.NotificationType
import com.oxygenupdater.exceptions.OxygenUpdaterException
import com.oxygenupdater.internal.settings.SettingsManager
import com.oxygenupdater.models.AppLocale
import com.oxygenupdater.utils.Logger.logError
import com.oxygenupdater.utils.NotificationIds
import org.koin.java.KoinJavaComponent.inject

/**
 * Enqueued from [com.oxygenupdater.services.FirebaseMessagingService]
 * to display a notification to the user after a specified delay
 *
 * @author [Adhiraj Singh Chauhan](https://github.com/adhirajsinghchauhan)
 */
class DisplayDelayedNotificationWorker(
    private val context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    private val messageContents = parameters.inputData.keyValueMap
        .entries
        .associate { it.key to it.value.toString() }

    private val notificationBuilder = NotificationCompat.Builder(
        context,
        OxygenUpdater.PUSH_NOTIFICATION_CHANNEL_ID
    )

    private val localAppDb by inject(LocalAppDb::class.java)
    private val notificationManager by inject(NotificationManager::class.java)

    private val newsItemDao by lazy {
        localAppDb.newsItemDao()
    }

    override suspend fun doWork(): Result {
        if (messageContents.isNullOrEmpty()) {
            return Result.failure()
        }

        val notificationType = NotificationType.valueOf(
            messageContents[NotificationElement.TYPE.name] ?: ""
        )

        val builder = when (notificationType) {
            NotificationType.NEW_DEVICE -> if (!SettingsManager.getPreference(
                    SettingsManager.PROPERTY_RECEIVE_NEW_DEVICE_NOTIFICATIONS,
                    true
                )
            ) {
                return Result.success()
            } else {
                getNewDeviceNotificationBuilder(messageContents[NotificationElement.NEW_DEVICE_NAME.name])
            }
            NotificationType.NEW_VERSION -> if (!SettingsManager.getPreference(
                    SettingsManager.PROPERTY_RECEIVE_SYSTEM_UPDATE_NOTIFICATIONS,
                    true
                )
            ) {
                return Result.success()
            } else {
                getNewVersionNotificationBuilder(
                    messageContents[NotificationElement.DEVICE_NAME.name],
                    messageContents[NotificationElement.NEW_VERSION_NUMBER.name]
                )
            }
            NotificationType.GENERAL_NOTIFICATION -> if (!SettingsManager.getPreference(
                    SettingsManager.PROPERTY_RECEIVE_GENERAL_NOTIFICATIONS,
                    true
                )
            ) {
                // Don't show notification if user has opted out
                return Result.success()
            } else {
                val message = if (AppLocale.get() == AppLocale.NL) {
                    messageContents[NotificationElement.DUTCH_MESSAGE.name]
                } else {
                    messageContents[NotificationElement.ENGLISH_MESSAGE.name]
                }

                getGeneralServerOrNewsNotificationBuilder(message)
            }
            NotificationType.NEWS -> if (!SettingsManager.getPreference(
                    SettingsManager.PROPERTY_RECEIVE_NEWS_NOTIFICATIONS,
                    true
                )
            ) {
                return Result.success()
            } else {
                // If this is a "dump" notification, show it only to people who haven't yet read the article
                // A "bump" is defined as re-sending the notification so that people who haven't yet read the article can read it
                // However, only app versions from v4.1.0 onwards properly support this,
                // even though a broken implementation was added in v4.0.0 (Kotlin rebuild).
                // So use the "bump" feature on admin portal with care - the notification will still be shown on older app versions
                if (messageContents[NotificationElement.NEWS_ITEM_IS_BUMP.name]?.toBoolean() == true
                    && newsItemDao.getById(
                        messageContents[NotificationElement.NEWS_ITEM_ID.name]?.toLong()
                    )?.read == true
                ) {
                    return Result.success()
                }

                val newsMessage = if (AppLocale.get() == AppLocale.NL) {
                    messageContents[NotificationElement.DUTCH_MESSAGE.name]
                } else {
                    messageContents[NotificationElement.ENGLISH_MESSAGE.name]
                }

                getGeneralServerOrNewsNotificationBuilder(newsMessage)
            }
        }

        if (builder == null) {
            logError(
                TAG,
                OxygenUpdaterException("Failed to instantiate notificationBuilder. Can not display push notification!")
            )
            return Result.failure()
        }

        builder.setSmallIcon(R.drawable.logo_notification)
            .setContentIntent(getNotificationIntent(notificationType))
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .setPriority(PRIORITY_HIGH)
            .setColor(ContextCompat.getColor(context, R.color.colorPrimary))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        notificationManager.notify(
            getNotificationId(notificationType),
            builder.build()
        )

        return Result.success()
    }

    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    private fun getNotificationId(type: NotificationType) = when (type) {
        NotificationType.NEW_DEVICE -> NotificationIds.REMOTE_NOTIFICATION_NEW_DEVICE
        NotificationType.NEW_VERSION -> NotificationIds.REMOTE_NOTIFICATION_NEW_UPDATE
        NotificationType.GENERAL_NOTIFICATION -> NotificationIds.REMOTE_NOTIFICATION_GENERIC
        NotificationType.NEWS -> NotificationIds.REMOTE_NOTIFICATION_NEWS
        else -> NotificationIds.REMOTE_NOTIFICATION_UNKNOWN
    }

    private fun getGeneralServerOrNewsNotificationBuilder(message: String?) = notificationBuilder
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))

    private fun getNewDeviceNotificationBuilder(
        newDeviceName: String?
    ) = context.getString(
        R.string.notification_new_device_text,
        newDeviceName
    ).let {
        notificationBuilder
            .setContentTitle(context.getString(R.string.notification_new_device_title))
            .setContentText(it)
            .setStyle(NotificationCompat.BigTextStyle().bigText(it))
    }

    private fun getNewVersionNotificationBuilder(
        deviceName: String?,
        versionNumber: String?
    ) = context.getString(
        R.string.notification_version,
        versionNumber,
        deviceName
    ).let {
        notificationBuilder
            .setWhen(System.currentTimeMillis())
            .setContentTitle(context.getString(R.string.notification_version_title))
            .setContentText(it)
            .setStyle(NotificationCompat.BigTextStyle().bigText(it))
    }

    private fun getNotificationIntent(
        notificationType: NotificationType
    ) = if (notificationType == NotificationType.NEWS) {
        val intent = Intent(context, NewsItemActivity::class.java)
            .putExtra(
                NewsItemActivity.INTENT_NEWS_ITEM_ID,
                messageContents[NotificationElement.NEWS_ITEM_ID.name]?.toLong()
            )
            .putExtra(NewsItemActivity.INTENT_DELAY_AD_START, true)

        PendingIntent.getActivity(
            context,
            0,
            intent,
            FLAG_UPDATE_CURRENT
        )
    } else {
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        private const val TAG = "DisplayDelayedNotificationWorker"
    }
}

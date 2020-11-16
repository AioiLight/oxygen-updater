package com.oxygenupdater.utils

import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.ktx.messaging
import com.oxygenupdater.BuildConfig.NOTIFICATIONS_PREFIX
import com.oxygenupdater.internal.settings.SettingsManager
import com.oxygenupdater.internal.settings.SettingsManager.PROPERTY_NOTIFICATION_TOPIC
import com.oxygenupdater.models.Device
import com.oxygenupdater.models.UpdateMethod
import com.oxygenupdater.utils.Logger.logVerbose

object NotificationTopicSubscriber {

    private const val TAG = "NotificationTopicSubscriber"
    private const val DEVICE_TOPIC_PREFIX = "device_"
    private const val UPDATE_METHOD_TOPIC_PREFIX = "_update-method_"

    private val messaging = Firebase.messaging

    fun subscribe(deviceList: List<Device>, updateMethodList: List<UpdateMethod>) {
        val oldTopic = SettingsManager.getPreference<String?>(PROPERTY_NOTIFICATION_TOPIC, null)

        if (oldTopic == null) {
            // If the topic is not saved (App Version 1.0.0 did not do this),
            // unsubscribe from all possible topics first to prevent duplicate/wrong notifications.
            updateMethodList.forEach { (id) ->
                deviceList.forEach { device ->
                    messaging.unsubscribeFromTopic(
                        NOTIFICATIONS_PREFIX + DEVICE_TOPIC_PREFIX + device.id + UPDATE_METHOD_TOPIC_PREFIX + id
                    )
                }
            }
        } else {
            messaging.unsubscribeFromTopic(oldTopic)
        }

        val newTopic = (NOTIFICATIONS_PREFIX + DEVICE_TOPIC_PREFIX
                + SettingsManager.getPreference(SettingsManager.PROPERTY_DEVICE_ID, -1L)
                + UPDATE_METHOD_TOPIC_PREFIX
                + SettingsManager.getPreference(SettingsManager.PROPERTY_UPDATE_METHOD_ID, -1L))

        // Subscribe to the new topic to start receiving notifications.
        messaging.subscribeToTopic(newTopic)

        logVerbose(TAG, "Subscribed to notifications on topic $newTopic ...")

        SettingsManager.savePreference(PROPERTY_NOTIFICATION_TOPIC, newTopic)
    }
}

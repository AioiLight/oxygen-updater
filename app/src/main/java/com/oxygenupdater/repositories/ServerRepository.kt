package com.oxygenupdater.repositories

import com.android.billingclient.api.Purchase
import com.fasterxml.jackson.core.JsonProcessingException
import com.oxygenupdater.BuildConfig
import com.oxygenupdater.OxygenUpdater
import com.oxygenupdater.apis.ServerApi
import com.oxygenupdater.database.LocalAppDb
import com.oxygenupdater.enums.PurchaseType
import com.oxygenupdater.internal.KotlinCallback
import com.oxygenupdater.internal.settings.SettingsManager
import com.oxygenupdater.models.DeviceRequestFilter
import com.oxygenupdater.models.NewsItem
import com.oxygenupdater.models.RootInstall
import com.oxygenupdater.models.ServerPostResult
import com.oxygenupdater.models.ServerStatus
import com.oxygenupdater.models.SystemVersionProperties
import com.oxygenupdater.models.UpdateData
import com.oxygenupdater.utils.Utils
import com.oxygenupdater.utils.performServerRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException

/**
 * @author [Adhiraj Singh Chauhan](https://github.com/adhirajsinghchauhan)
 */
@Suppress("unused")
class ServerRepository constructor(
    private val serverApi: ServerApi,
    private val systemVersionProperties: SystemVersionProperties,
    localAppDb: LocalAppDb
) {

    private var serverStatus: ServerStatus? = null

    private val newsItemDao by lazy(LazyThreadSafetyMode.NONE) {
        localAppDb.newsItemDao()
    }

    suspend fun fetchFaq() = performServerRequest { serverApi.fetchFaq() }

    suspend fun fetchDevices(
        filter: DeviceRequestFilter
    ) = performServerRequest { serverApi.fetchDevices(filter.filter) }

    suspend fun fetchUpdateData(
        deviceId: Long,
        updateMethodId: Long,
        incrementalSystemVersion: String
    ) = performServerRequest {
        serverApi.fetchUpdateData(
            deviceId,
            updateMethodId,
            incrementalSystemVersion,
            systemVersionProperties.oxygenOSVersion,
            systemVersionProperties.osType,
            systemVersionProperties.fingerprint,
            SettingsManager.getPreference(SettingsManager.PROPERTY_IS_EU_BUILD, false),
            BuildConfig.VERSION_NAME
        )
    }.let { updateData: UpdateData? ->
        if (updateData?.information != null
            && updateData.information == OxygenUpdater.UNABLE_TO_FIND_A_MORE_RECENT_BUILD
            && updateData.isUpdateInformationAvailable
            && updateData.systemIsUpToDate
        ) {
            fetchMostRecentUpdateData(deviceId, updateMethodId)
        } else if (!Utils.checkNetworkConnection()) {
            if (SettingsManager.checkIfOfflineUpdateDataIsAvailable()) {
                UpdateData(
                    id = SettingsManager.getPreference<Long?>(SettingsManager.PROPERTY_OFFLINE_ID, null),
                    versionNumber = SettingsManager.getPreference<String?>(SettingsManager.PROPERTY_OFFLINE_UPDATE_NAME, null),
                    description = SettingsManager.getPreference<String?>(SettingsManager.PROPERTY_OFFLINE_UPDATE_DESCRIPTION, null),
                    downloadUrl = SettingsManager.getPreference<String?>(SettingsManager.PROPERTY_OFFLINE_DOWNLOAD_URL, null),
                    downloadSize = SettingsManager.getPreference(SettingsManager.PROPERTY_OFFLINE_UPDATE_DOWNLOAD_SIZE, 0L),
                    filename = SettingsManager.getPreference<String?>(SettingsManager.PROPERTY_OFFLINE_FILE_NAME, null),
                    updateInformationAvailable = SettingsManager.getPreference(SettingsManager.PROPERTY_OFFLINE_UPDATE_INFORMATION_AVAILABLE, false),
                    systemIsUpToDate = SettingsManager.getPreference(SettingsManager.PROPERTY_OFFLINE_IS_UP_TO_DATE, false)
                )
            } else {
                null
            }
        } else {
            updateData
        }
    }

    private suspend fun fetchMostRecentUpdateData(
        deviceId: Long,
        updateMethodId: Long
    ) = performServerRequest {
        serverApi.fetchMostRecentUpdateData(deviceId, updateMethodId)
    }

    suspend fun fetchServerStatus(
        useCache: Boolean = false
    ) = if (useCache && serverStatus != null) {
        serverStatus!!
    } else {
        performServerRequest {
            serverApi.fetchServerStatus()
        }.let { status ->
            val automaticInstallationEnabled = SettingsManager.getPreference(
                SettingsManager.PROPERTY_IS_AUTOMATIC_INSTALLATION_ENABLED,
                false
            )
            val pushNotificationsDelaySeconds = SettingsManager.getPreference(
                SettingsManager.PROPERTY_NOTIFICATION_DELAY_IN_SECONDS,
                300
            )

            val response = if (status == null && Utils.checkNetworkConnection()) {
                ServerStatus(
                    ServerStatus.Status.UNREACHABLE,
                    BuildConfig.VERSION_NAME,
                    automaticInstallationEnabled,
                    pushNotificationsDelaySeconds
                )
            } else {
                status ?: ServerStatus(
                    ServerStatus.Status.NORMAL,
                    BuildConfig.VERSION_NAME,
                    automaticInstallationEnabled,
                    pushNotificationsDelaySeconds
                )
            }

            SettingsManager.savePreference(
                SettingsManager.PROPERTY_IS_AUTOMATIC_INSTALLATION_ENABLED,
                response.automaticInstallationEnabled
            )
            SettingsManager.savePreference(
                SettingsManager.PROPERTY_NOTIFICATION_DELAY_IN_SECONDS,
                response.pushNotificationDelaySeconds
            )

            response.also { serverStatus = it }
        }
    }

    suspend fun fetchServerMessages(
        serverStatus: ServerStatus,
        errorCallback: KotlinCallback<String?>
    ) = performServerRequest {
        serverApi.fetchServerMessages(
            SettingsManager.getPreference(SettingsManager.PROPERTY_DEVICE_ID, -1L),
            SettingsManager.getPreference(SettingsManager.PROPERTY_UPDATE_METHOD_ID, -1L)
        )
    }.let { serverMessages ->
        val status = serverStatus.status!!
        if (status.isNonRecoverableError) {
            when (status) {
                ServerStatus.Status.MAINTENANCE -> withContext(Dispatchers.Main) {
                    errorCallback.invoke(OxygenUpdater.SERVER_MAINTENANCE_ERROR)
                }
                ServerStatus.Status.OUTDATED -> withContext(Dispatchers.Main) {
                    errorCallback.invoke(OxygenUpdater.APP_OUTDATED_ERROR)
                }
                else -> {
                    // no-op
                }
            }
        }

        serverMessages
    }

    suspend fun fetchNews(
        deviceId: Long,
        updateMethodId: Long
    ) = performServerRequest {
        serverApi.fetchNews(deviceId, updateMethodId)
    }.let {
        if (!it.isNullOrEmpty()) {
            newsItemDao.refreshNewsItems(it)
        }

        newsItemDao.getAll()
    }

    suspend fun fetchNewsItem(
        newsItemId: Long
    ) = performServerRequest {
        serverApi.fetchNewsItem(newsItemId)
    }.let {
        if (it != null) {
            newsItemDao.insertOrUpdate(it)
        }

        newsItemDao.getById(newsItemId)
    }

    fun toggleNewsItemReadStatusLocally(
        newsItem: NewsItem,
        newReadStatus: Boolean = !newsItem.read
    ) = newsItemDao.toggleReadStatus(
        newsItem,
        newReadStatus
    )

    fun markNewsItemReadLocally(
        newsItemId: Long
    ) = newsItemDao.toggleReadStatus(
        newsItemId,
        true
    )

    suspend fun markNewsItemRead(
        newsItemId: Long
    ) = performServerRequest {
        serverApi.markNewsItemRead(mapOf("news_item_id" to newsItemId))
    }

    suspend fun fetchUpdateMethodsForDevice(
        deviceId: Long,
        hasRootAccess: Boolean
    ) = performServerRequest {
        serverApi.fetchUpdateMethodsForDevice(deviceId)
    }.let { updateMethods ->
        if (hasRootAccess) {
            updateMethods?.filter { it.supportsRootedDevice }?.map { it.setRecommended(if (it.recommendedForNonRootedDevice) "1" else "0") }
        } else {
            updateMethods?.map { it.setRecommended(if (it.recommendedForNonRootedDevice) "1" else "0") }
        }
    }

    suspend fun fetchAllMethods() = performServerRequest {
        serverApi.fetchAllUpdateMethods()
    }

    suspend fun fetchInstallGuidePage(
        deviceId: Long,
        updateMethodId: Long,
        pageNumber: Int
    ) = performServerRequest {
        serverApi.fetchInstallGuidePage(deviceId, updateMethodId, pageNumber)
    }

    suspend fun submitUpdateFile(
        filename: String
    ) = performServerRequest {
        serverApi.submitUpdateFile(
            hashMapOf(
                "filename" to filename,
                "isEuBuild" to SettingsManager.getPreference(SettingsManager.PROPERTY_IS_EU_BUILD, false),
                "appVersion" to BuildConfig.VERSION_NAME,
                "deviceName" to SettingsManager.getPreference(SettingsManager.PROPERTY_DEVICE, "<UNKNOWN>"),
                "actualDeviceName" to systemVersionProperties.oxygenDeviceName
            )
        )
    }

    suspend fun logDownloadError(
        url: String?,
        filename: String?,
        version: String?,
        otaVersion: String?,
        httpCode: Int,
        httpMessage: String?
    ) = performServerRequest {
        serverApi.logDownloadError(
            hashMapOf(
                "url" to url,
                "filename" to filename,
                "version" to version,
                "otaVersion" to otaVersion,
                "httpCode" to httpCode,
                "httpMessage" to httpMessage,
                "appVersion" to BuildConfig.VERSION_NAME,
                "deviceName" to SettingsManager.getPreference(SettingsManager.PROPERTY_DEVICE, "<UNKNOWN>"),
                "actualDeviceName" to systemVersionProperties.oxygenDeviceName
            )
        )
    }

    suspend fun logRootInstall(rootInstall: RootInstall) = try {
        performServerRequest { serverApi.logRootInstall(rootInstall) }
    } catch (e: JSONException) {
        ServerPostResult(
            false,
            "IN-APP ERROR (ServerConnector): Json parse error on input data $rootInstall"
        )
    } catch (e: JsonProcessingException) {
        ServerPostResult(
            false,
            "IN-APP ERROR (ServerConnector): Json parse error on input data $rootInstall"
        )
    }

    suspend fun verifyPurchase(
        purchase: Purchase,
        amount: String?,
        purchaseType: PurchaseType
    ) = performServerRequest {
        serverApi.verifyPurchase(
            hashMapOf(
                "orderId" to purchase.orderId,
                "packageName" to purchase.packageName,
                "productId" to purchase.skus.joinToString(","),
                "purchaseTime" to purchase.purchaseTime,
                "purchaseState" to purchase.purchaseState,
                "developerPayload" to purchase.developerPayload,
                "token" to purchase.purchaseToken,
                "purchaseToken" to purchase.purchaseToken,
                "autoRenewing" to purchase.isAutoRenewing,
                "purchaseType" to purchaseType.name,
                "itemType" to purchaseType.type,
                "signature" to purchase.signature,
                "amount" to amount
            )
        )
    }

    companion object {
        private const val TAG = "ServerRepository"
    }
}

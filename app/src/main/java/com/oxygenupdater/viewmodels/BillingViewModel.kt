package com.oxygenupdater.viewmodels

import android.app.Activity
import android.app.Application
import androidx.annotation.UiThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.Purchase
import com.oxygenupdater.enums.PurchaseType
import com.oxygenupdater.internal.KotlinCallback
import com.oxygenupdater.internal.OnPurchaseFinishedListener
import com.oxygenupdater.internal.settings.SettingsManager
import com.oxygenupdater.models.ServerPostResult
import com.oxygenupdater.models.billing.AdFreeUnlock
import com.oxygenupdater.models.billing.AugmentedSkuDetails
import com.oxygenupdater.repositories.BillingRepository
import com.oxygenupdater.repositories.ServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @author [Adhiraj Singh Chauhan](https://github.com/adhirajsinghchauhan)
 */
class BillingViewModel(
    application: Application,
    private val billingRepository: BillingRepository,
    private val serverRepository: ServerRepository
) : AndroidViewModel(application) {

    val purchaseStateChangeLiveData: LiveData<Unit>
    val pendingPurchasesLiveData: LiveData<Purchase>
    val inappSkuDetailsListLiveData: LiveData<List<AugmentedSkuDetails>>
    val adFreeUnlockLiveData: LiveData<AdFreeUnlock?>

    init {
        billingRepository.startDataSourceConnections()

        inappSkuDetailsListLiveData = billingRepository.inappSkuDetailsListLiveData

        // Clients need to observe this LiveData so that internal logic is guaranteed to run
        adFreeUnlockLiveData = Transformations.map(
            billingRepository.adFreeUnlockLiveData
        ) {
            // Save, because we can guarantee that the device is online and that the purchase check has succeeded
            SettingsManager.savePreference(
                SettingsManager.PROPERTY_AD_FREE,
                it?.entitled == true
            ).run { it }
        }

        // Clients need to observe this LiveData so that internal logic is guaranteed to run
        pendingPurchasesLiveData = Transformations.map(
            billingRepository.pendingPurchasesLiveData
        ) { purchases -> logPendingPurchase(purchases) }

        // Clients need to observe this LiveData so that internal logic is guaranteed to run
        purchaseStateChangeLiveData = Transformations.map(
            billingRepository.purchaseStateChangeLiveData
        ) { logPurchaseStateChange(it) }
    }

    /**
     * Not used yet, but could be used to force refresh (e.g. pull-to-refresh)
     */
    @UiThread
    fun queryPurchases() = billingRepository.queryPurchases()

    override fun onCleared() = super.onCleared().also {
        billingRepository.endDataSourceConnections()
    }

    @UiThread
    fun makePurchase(
        activity: Activity,
        augmentedSkuDetails: AugmentedSkuDetails,
        /**
         * Invoked within [BillingRepository.disburseNonConsumableEntitlement] and [BillingRepository.onPurchasesUpdated]
         */
        callback: OnPurchaseFinishedListener
    ) {
        billingRepository.launchBillingFlow(
            activity,
            augmentedSkuDetails
        ) { responseCode, purchase ->
            // Since we update UI after receiving the callback,
            // Make sure it's invoked on the main thread
            // (otherwise app would crash)
            viewModelScope.launch {
                callback.invoke(responseCode, purchase)
            }
        }
    }

    fun verifyPurchase(
        purchase: Purchase,
        amount: String?,
        purchaseType: PurchaseType,
        callback: KotlinCallback<ServerPostResult?>
    ) = viewModelScope.launch(Dispatchers.IO) {
        serverRepository.verifyPurchase(
            purchase,
            amount,
            purchaseType
        ).let {
            withContext(Dispatchers.Main) {
                callback.invoke(it)
            }
        }
    }

    /**
     * Updates the server with information about the pending purchase, and returns
     * the pending purchase object so that other LiveData observers can act on it
     */
    private fun logPendingPurchase(purchases: Set<Purchase>): Purchase? {
        val pendingAdFreeUnlockPurchase = purchases.find {
            it.sku == BillingRepository.Sku.AD_FREE
        }

        viewModelScope.launch(Dispatchers.IO) {
            purchases.forEach {
                serverRepository.verifyPurchase(
                    it,
                    null,
                    PurchaseType.AD_FREE
                )
            }
        }

        return pendingAdFreeUnlockPurchase
    }

    /**
     * Updates the server with information about any purchase state changes
     */
    private fun logPurchaseStateChange(purchases: Set<Purchase>) {
        viewModelScope.launch(Dispatchers.IO) {
            purchases.forEach {
                serverRepository.verifyPurchase(
                    it,
                    null,
                    PurchaseType.AD_FREE
                )
            }
        }
    }
}

package com.example.duplicatecleaner.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-time "Pro" unlock through Google Play Billing.
 * Create an in-app product (not a subscription) with ID [PRO_PRODUCT_ID] in Play Console.
 */
class BillingManager(
    context: Context,
    private val entitlements: Entitlements
) : PurchasesUpdatedListener {

    private val _isPro = MutableStateFlow(entitlements.isProCached)
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    private val _price = MutableStateFlow<String?>(null)
    val price: StateFlow<String?> = _price.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var productDetails: ProductDetails? = null

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    fun connect() {
        if (client.isReady) {
            loadProduct()
            refresh()
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    loadProduct()
                    refresh()
                }
            }

            override fun onBillingServiceDisconnected() {
                // Reconnects on the next connect() / purchase attempt.
            }
        })
    }

    private fun loadProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRO_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetails = details.firstOrNull()
                _price.value = productDetails?.oneTimePurchaseOfferDetails?.formattedPrice
            }
        }
    }

    /** Re-checks what the user owns. Also serves as "Restore purchase". */
    fun refresh() {
        if (!client.isReady) {
            connect()
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val owned = purchases.filter(::isProPurchase)
                owned.forEach(::acknowledgeIfNeeded)
                setPro(owned.isNotEmpty())
            }
        }
    }

    fun launchPurchase(activity: Activity) {
        val details = productDetails
        if (!client.isReady || details == null) {
            _events.tryEmit("Store isn't ready yet. Check your connection and try again.")
            connect()
            return
        }
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()
        client.launchBillingFlow(activity, flow)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val list = purchases.orEmpty()
                if (list.any { it.products.contains(PRO_PRODUCT_ID) && it.purchaseState == Purchase.PurchaseState.PENDING }) {
                    _events.tryEmit("Payment pending. Pro unlocks when it completes.")
                }
                val owned = list.filter(::isProPurchase)
                if (owned.isNotEmpty()) {
                    owned.forEach(::acknowledgeIfNeeded)
                    setPro(true)
                    _events.tryEmit("Thank you! Pro is unlocked.")
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            else -> _events.tryEmit("Purchase failed. You have not been charged.")
        }
    }

    private fun isProPurchase(p: Purchase): Boolean =
        p.products.contains(PRO_PRODUCT_ID) && p.purchaseState == Purchase.PurchaseState.PURCHASED

    private fun acknowledgeIfNeeded(p: Purchase) {
        if (p.isAcknowledged) return
        client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
        ) { /* Play refunds unacknowledged purchases after 3 days; retried on next refresh(). */ }
    }

    private fun setPro(value: Boolean) {
        entitlements.isProCached = value
        _isPro.value = value
    }

    companion object {
        const val PRO_PRODUCT_ID = "pro_unlock"
    }
}

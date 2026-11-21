package com.dpjha.cloneout.billing

import android.app.Activity
import android.content.Context
import com.dpjha.cloneout.telemetry.Telemetry
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

    /** True when Play could not give us the product (not installed from Play, product missing, no store). */
    private val _unavailable = MutableStateFlow(false)
    val storeUnavailable: StateFlow<Boolean> = _unavailable.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var productDetails: ProductDetails? = null
    private var priceMicros = 0L
    private var currency = ""

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
                } else {
                    _unavailable.value = true
                    Telemetry.event("billing_setup_failed", "code" to result.responseCode)
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
        client.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetails = queryResult.productDetailsList.firstOrNull()
                _price.value = productDetails?.oneTimePurchaseOfferDetails?.formattedPrice
                priceMicros = productDetails?.oneTimePurchaseOfferDetails?.priceAmountMicros ?: 0L
                currency = productDetails?.oneTimePurchaseOfferDetails?.priceCurrencyCode ?: ""
                _unavailable.value = productDetails == null
                if (productDetails == null) Telemetry.event("billing_product_missing")
            } else {
                _unavailable.value = true
                Telemetry.event("billing_product_failed", "code" to result.responseCode)
            }
        }
    }

    /** Re-checks what the user owns. Also serves as "Restore purchase". */
    fun refresh(userInitiated: Boolean = false) {
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
                if (userInitiated) Telemetry.event("restore_purchase_result", "found" to owned.isNotEmpty())
            } else if (userInitiated) {
                Telemetry.event("restore_purchase_result", "found" to false, "code" to result.responseCode)
            }
        }
    }

    fun launchPurchase(activity: Activity) {
        val details = productDetails
        if (!client.isReady || details == null) {
            Telemetry.event("purchase_blocked", "store_unavailable" to _unavailable.value)
            _events.tryEmit(
                if (_unavailable.value) "Purchases work in the Play Store version of Cloneout. Install it from Google Play (or the testing link) and try again."
                else "Store isn't ready yet. Check your connection and try again."
            )
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
        Telemetry.event(
            "begin_checkout",
            "item_id" to PRO_PRODUCT_ID, "value" to (priceMicros / 1_000_000.0), "currency" to currency
        )
        val launchResult = client.launchBillingFlow(activity, flow)
        when (launchResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            else -> {
                Telemetry.event("purchase_error", "code" to launchResult.responseCode, "stage" to "launch")
                _events.tryEmit("Couldn't open the purchase screen. Please try again.")
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val list = purchases.orEmpty()
                if (list.any { it.products.contains(PRO_PRODUCT_ID) && it.purchaseState == Purchase.PurchaseState.PENDING }) {
                    Telemetry.event("purchase_pending")
                    _events.tryEmit("Payment pending. Pro unlocks when it completes.")
                }
                val owned = list.filter(::isProPurchase)
                if (owned.isNotEmpty()) {
                    owned.forEach(::acknowledgeIfNeeded)
                    setPro(true)
                    Telemetry.event(
                        "purchase",
                        "item_id" to PRO_PRODUCT_ID,
                        "value" to (priceMicros / 1_000_000.0),
                        "currency" to currency,
                        "transaction_id" to owned.first().orderId
                    )
                    _events.tryEmit("Thank you! Pro is unlocked.")
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> Telemetry.event("purchase_cancel")
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            else -> {
                Telemetry.event("purchase_error", "code" to result.responseCode, "stage" to "result")
                _events.tryEmit("Purchase failed. You have not been charged.")
            }
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

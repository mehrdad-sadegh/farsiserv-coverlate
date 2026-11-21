package ir.farsisaz

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import org.json.JSONObject

/**
 * خرید اشتراک از طریق Google Play (برای آینده). فقط وقتی استفاده می‌شود که روش پرداخت «googleplay»
 * از پنل روشن باشد و برنامه از پلی‌استور نصب شده باشد. محصولات در Play Console تعریف می‌شوند (نه در برنامه)،
 * پس فعال‌سازی‌اش بدون آپدیت است. بعد از خرید، رسید به سرور فرستاده می‌شود تا لایسنس صادر شود.
 */
class Billing(
    private val act: Activity,
    private val hwid: String,
    private val onResult: (Boolean, String) -> Unit,
) {
    private var client: BillingClient? = null

    private val purchaseListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (p in purchases) handlePurchase(p)
        } else if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            onResult(false, "خرید لغو شد.")
        } else {
            onResult(false, "خرید انجام نشد (${result.responseCode}).")
        }
    }

    fun buy(productId: String) {
        if (productId.isBlank()) { onResult(false, "محصول تعریف نشده."); return }
        val c = BillingClient.newBuilder(act)
            .setListener(purchaseListener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        client = c
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) launch(productId)
                else onResult(false, "اتصال به Google Play برقرار نشد.")
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun launch(productId: String) {
        val c = client ?: return
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(productId)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        c.queryProductDetailsAsync(params) { result, list ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK || list.isEmpty()) {
                onResult(false, "محصول در Google Play پیدا نشد."); return@queryProductDetailsAsync
            }
            val pd = list[0]
            val flow = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd).build()
                )).build()
            act.runOnUiThread { c.launchBillingFlow(act, flow) }
        }
    }

    private fun handlePurchase(p: Purchase) {
        if (p.purchaseState != Purchase.PurchaseState.PURCHASED) return
        // رسید را به سرور بفرست تا لایسنس برای این دستگاه صادر شود
        Thread {
            val ok = try {
                val body = JSONObject()
                    .put("hwid", hwid)
                    .put("token", p.purchaseToken)
                    .put("product", p.products.firstOrNull() ?: "")
                val r = Net.postJson("$SERVER_URL/api/gplay/verify", body)
                JSONObject(r.body).optBoolean("ok", false)
            } catch (_: Exception) { false }
            if (ok && !p.isAcknowledged) {
                try {
                    client?.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
                    ) {}
                } catch (_: Exception) {}
            }
            act.runOnUiThread {
                onResult(ok, if (ok) "اشتراک از Google Play فعال شد." else "تأیید خرید ناموفق بود؛ با پشتیبانی تماس بگیرید.")
            }
        }.start()
    }
}

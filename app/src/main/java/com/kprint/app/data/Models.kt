package com.kprint.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Payload intentionally tolerant so an existing delivery backend can adopt it incrementally. */
data class OrderPayload(
    val orderNumber: String,
    val merchantName: String,
    val createdAt: String,
    val customerName: String,
    val customerPhone: String,
    val deliveryType: String,
    val address: String,
    val items: List<OrderItem>,
    val subtotal: Double,
    val deliveryFee: Double,
    val discount: Double,
    val total: Double,
    val paymentMethod: String,
    val changeFor: Double?,
    val notes: String,
) {
    companion object {
        fun fromJson(json: JSONObject): OrderPayload {
            val customer = json.optJSONObject("customer") ?: JSONObject()
            val delivery = json.optJSONObject("delivery") ?: JSONObject()
            val payment = json.optJSONObject("payment") ?: JSONObject()
            val totals = json.optJSONObject("totals") ?: JSONObject()
            val itemsJson = json.optJSONArray("items") ?: JSONArray()

            val items = buildList {
                for (index in 0 until itemsJson.length()) {
                    val item = itemsJson.optJSONObject(index) ?: continue
                    add(
                        OrderItem(
                            quantity = item.optDouble("quantity", 1.0),
                            name = item.cleanString("name", "Item"),
                            notes = item.cleanString("notes"),
                            unitPrice = item.optDouble("unit_price", 0.0),
                            total = item.optDouble(
                                "total",
                                item.optDouble("unit_price", 0.0) * item.optDouble("quantity", 1.0),
                            ),
                        ),
                    )
                }
            }

            val address = delivery.cleanString("address").ifBlank {
                listOfNotNull(
                    delivery.stringOrNull("street"),
                    delivery.stringOrNull("number"),
                    delivery.stringOrNull("neighborhood"),
                    delivery.stringOrNull("complement"),
                ).joinToString(", ")
            }

            val subtotal = totals.optDouble("subtotal", items.sumOf { it.total })
            val fee = totals.optDouble("delivery_fee", 0.0)
            val discount = totals.optDouble("discount", 0.0)

            return OrderPayload(
                orderNumber = json.cleanString("order_number", json.cleanString("number", "--")),
                merchantName = json.cleanString("merchant_name", "PEDIDO"),
                createdAt = json.cleanString("created_at"),
                customerName = customer.cleanString("name", "Consumidor"),
                customerPhone = customer.cleanString("phone"),
                deliveryType = delivery.cleanString("type", "delivery"),
                address = address,
                items = items,
                subtotal = subtotal,
                deliveryFee = fee,
                discount = discount,
                total = totals.optDouble("total", subtotal + fee - discount),
                paymentMethod = payment.cleanString("method", "Não informado"),
                changeFor = if (payment.has("change_for") && !payment.isNull("change_for")) {
                    payment.optDouble("change_for")
                } else {
                    null
                },
                notes = json.cleanString("notes"),
            )
        }
    }
}

data class OrderItem(
    val quantity: Double,
    val name: String,
    val notes: String,
    val unitPrice: Double,
    val total: Double,
)

data class PrintJob(
    val id: String,
    val orderId: String,
    val payload: OrderPayload,
    val attempts: Int,
) {
    companion object {
        fun fromJson(json: JSONObject) = PrintJob(
            id = json.getString("id"),
            orderId = json.cleanString("order_id"),
            payload = OrderPayload.fromJson(json.getJSONObject("payload")),
            attempts = json.optInt("attempts", 1),
        )
    }
}

private fun JSONObject.cleanString(key: String, fallback: String = ""): String =
    stringOrNull(key)?.takeIf { it.isNotBlank() } ?: fallback

private fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).trim()

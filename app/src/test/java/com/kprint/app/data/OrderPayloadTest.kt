package com.kprint.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class OrderPayloadTest {
    @Test
    fun `parses documented Supabase payload`() {
        val payload = OrderPayload.fromJson(
            JSONObject(
                """
                {
                  "order_number":"1842",
                  "merchant_name":"Loja",
                  "customer":{"name":"Maria"},
                  "delivery":{"type":"delivery","street":"Rua A","number":"10"},
                  "items":[{"quantity":2,"name":"X-Burguer","unit_price":18.5}],
                  "totals":{"delivery_fee":5,"total":42},
                  "payment":{"method":"Pix"}
                }
                """.trimIndent(),
            ),
        )

        assertEquals("1842", payload.orderNumber)
        assertEquals("Maria", payload.customerName)
        assertEquals("Rua A, 10", payload.address)
        assertEquals(37.0, payload.subtotal, 0.001)
        assertEquals(42.0, payload.total, 0.001)
        assertEquals("Pix", payload.paymentMethod)
    }
}

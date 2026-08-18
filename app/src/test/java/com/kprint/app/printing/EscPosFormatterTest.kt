package com.kprint.app.printing

import com.kprint.app.data.OrderItem
import com.kprint.app.data.OrderPayload
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscPosFormatterTest {
    private val order = OrderPayload(
        orderNumber = "42",
        merchantName = "Açaí Ceará",
        createdAt = "17/08/2026 19:30",
        customerName = "José",
        customerPhone = "85999990000",
        deliveryType = "delivery",
        address = "Rua com um nome grande, 100, Fortaleza",
        items = listOf(OrderItem(2.0, "Açaí 500 ml", "Sem banana", 15.0, 30.0)),
        subtotal = 30.0,
        deliveryFee = 5.0,
        discount = 2.0,
        total = 33.0,
        paymentMethod = "Dinheiro",
        changeFor = 50.0,
        notes = "Entregar na portaria",
    )

    @Test
    fun `receipt includes operational order data`() {
        val receipt = EscPosFormatter.renderText(order)

        assertTrue(receipt.contains("PEDIDO #42"))
        assertTrue(receipt.contains("2x Açaí 500 ml"))
        assertTrue(receipt.contains("TOTAL"))
        assertTrue(receipt.contains("R$ 33,00"))
        assertTrue(receipt.contains("Troco para: R$ 50,00"))
    }

    @Test
    fun `printable lines fit 58mm paper`() {
        val receipt = EscPosFormatter.renderText(order)
        val contentLines = receipt.lines().filterNot { it.contains("PEDIDO #") || it.contains("TOTAL") }

        assertFalse(contentLines.any { it.length > 32 })
    }
}

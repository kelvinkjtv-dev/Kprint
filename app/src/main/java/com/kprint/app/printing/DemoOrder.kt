package com.kprint.app.printing

import com.kprint.app.data.OrderItem
import com.kprint.app.data.OrderPayload
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun demoOrder(): OrderPayload = OrderPayload(
    orderNumber = "TESTE-001",
    merchantName = "KPrint Delivery",
    createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
    customerName = "Cliente de teste",
    customerPhone = "(85) 99999-0000",
    deliveryType = "delivery",
    address = "Av. Beira Mar, 1000, Meireles - Fortaleza/CE",
    items = listOf(
        OrderItem(2.0, "Hambúrguer artesanal", "Sem cebola", 24.90, 49.80),
        OrderItem(1.0, "Refrigerante lata", "", 7.00, 7.00),
    ),
    subtotal = 56.80,
    deliveryFee = 5.00,
    discount = 2.00,
    total = 59.80,
    paymentMethod = "Dinheiro",
    changeFor = 100.00,
    notes = "Tocar a campainha ao chegar.",
)

package com.kprint.app.printing

import com.kprint.app.data.OrderPayload
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

object EscPosFormatter {
    private val printerCharset: Charset = Charset.forName("CP860")

    fun format(order: OrderPayload, paperWidth: Int = 58): ByteArray {
        val columns = if (paperWidth == 80) 48 else 32
        val output = ByteArrayOutputStream()

        fun command(vararg bytes: Int) = bytes.forEach(output::write)
        fun text(value: String) {
            output.write(sanitize(value).toByteArray(printerCharset))
        }
        fun line(value: String = "") = text("$value\n")
        fun align(value: Int) = command(0x1B, 0x61, value)
        fun bold(enabled: Boolean) = command(0x1B, 0x45, if (enabled) 1 else 0)
        fun doubleSize(enabled: Boolean) = command(0x1D, 0x21, if (enabled) 0x11 else 0x00)
        fun wrapped(value: String, indent: String = "") = wrap(value, columns - indent.length).forEach {
            line(indent + it)
        }

        command(0x1B, 0x40) // Initialize.
        command(0x1B, 0x74, 0x03) // CP860: Portuguese on common ESC/POS printers.
        align(1)
        bold(true)
        order.merchantName.uppercase(Locale("pt", "BR")).let(::wrapped)
        bold(false)
        line()
        doubleSize(true)
        line("PEDIDO #${order.orderNumber}")
        doubleSize(false)
        order.createdAt.takeIf(String::isNotBlank)?.let(::line)

        align(0)
        line(divider(columns))
        bold(true)
        line("CLIENTE")
        bold(false)
        wrapped(order.customerName)
        order.customerPhone.takeIf(String::isNotBlank)?.let(::wrapped)

        val deliveryLabel = if (order.deliveryType.equals("pickup", true) ||
            order.deliveryType.equals("retirada", true)
        ) "RETIRADA" else "ENTREGA"
        bold(true)
        line(deliveryLabel)
        bold(false)
        order.address.takeIf(String::isNotBlank)?.let(::wrapped)

        line(divider(columns))
        bold(true)
        line("ITENS")
        bold(false)
        order.items.forEach { item ->
            val quantity = formatQuantity(item.quantity)
            val itemLines = wrap("${quantity}x ${item.name}", columns)
            itemLines.forEach(::line)
            line(sides("", money(item.total), columns))
            item.notes.takeIf(String::isNotBlank)?.let { wrapped("Obs: $it", "  ") }
        }

        line(divider(columns))
        line(sides("Subtotal", money(order.subtotal), columns))
        if (abs(order.deliveryFee) > 0.0001) {
            line(sides("Taxa de entrega", money(order.deliveryFee), columns))
        }
        if (abs(order.discount) > 0.0001) {
            line(sides("Desconto", "-${money(abs(order.discount))}", columns))
        }
        bold(true)
        doubleSize(true)
        line(sides("TOTAL", money(order.total), columns / 2).take(columns / 2))
        doubleSize(false)
        bold(false)

        line(divider(columns))
        bold(true)
        line("PAGAMENTO")
        bold(false)
        wrapped(order.paymentMethod)
        order.changeFor?.let { line("Troco para: ${money(it)}") }
        order.notes.takeIf(String::isNotBlank)?.let {
            line()
            bold(true)
            line("OBSERVACOES")
            bold(false)
            wrapped(it)
        }

        align(1)
        line()
        line("Impresso pelo KPrint")
        line()
        line()
        line()
        command(0x1B, 0x64, 0x03) // Feed three lines; safe for printers without cutter.
        return output.toByteArray()
    }

    /** Human-readable version used by tests and future print previews. */
    fun renderText(order: OrderPayload, paperWidth: Int = 58): String {
        val source = format(order, paperWidth)
        val printable = ByteArrayOutputStream()
        var index = 0
        while (index < source.size) {
            val value = source[index].toInt() and 0xFF
            when (value) {
                0x1B -> index += when (source.getOrNull(index + 1)?.toInt()?.and(0xFF)) {
                    0x40 -> 2 // ESC @
                    0x61, 0x45, 0x74, 0x64 -> 3 // Alignment, bold, table, feed.
                    else -> 2
                }
                0x1D -> index += 3 // GS ! (character size).
                else -> {
                    printable.write(value)
                    index += 1
                }
            }
        }
        return String(printable.toByteArray(), printerCharset)
    }

    private fun sides(left: String, right: String, columns: Int): String {
        if (right.length >= columns) return right.take(columns)
        val leftMax = (columns - right.length - 1).coerceAtLeast(0)
        val safeLeft = left.take(leftMax)
        return safeLeft + " ".repeat((columns - safeLeft.length - right.length).coerceAtLeast(1)) + right
    }

    private fun wrap(value: String, columns: Int): List<String> {
        val width = columns.coerceAtLeast(8)
        val words = sanitize(value).trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        var current = ""
        words.forEach { word ->
            val chunks = word.chunked(width)
            chunks.forEachIndexed { index, chunk ->
                val candidate = if (current.isBlank()) chunk else "$current $chunk"
                if (candidate.length <= width) {
                    current = candidate
                } else {
                    lines += current
                    current = chunk
                }
                if (index < chunks.lastIndex) {
                    lines += current
                    current = ""
                }
            }
        }
        if (current.isNotBlank()) lines += current
        return lines
    }

    private fun money(value: Double): String = "R$ " +
        String.format(Locale("pt", "BR"), "%.2f", value)

    private fun formatQuantity(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else {
            String.format(Locale("pt", "BR"), "%.2f", value).trimEnd('0').trimEnd(',')
        }

    private fun divider(columns: Int) = "-".repeat(columns)

    private fun sanitize(value: String): String = value
        .replace('–', '-')
        .replace('—', '-')
        .replace('’', '\'')
        .replace(Regex("[^\\p{L}\\p{N}\\p{P}\\p{Zs}\\r\\n]"), "")
        .let { text ->
            // CP860 covers Portuguese accents. Strip only characters the table cannot encode.
            if (printerCharset.newEncoder().canEncode(text)) text else {
                Normalizer.normalize(text, Normalizer.Form.NFD)
                    .replace(Regex("\\p{M}+"), "")
            }
        }
}

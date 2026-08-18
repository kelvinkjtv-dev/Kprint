package com.kprint.app.printing

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID

data class PairedPrinter(val name: String, val address: String)

class BluetoothPrinter(private val context: Context) {
    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<PairedPrinter> {
        if (!hasPermission()) return emptyList()
        return adapter?.bondedDevices.orEmpty()
            .map { PairedPrinter(it.name ?: "Impressora Bluetooth", it.address) }
            .sortedBy { it.name.lowercase() }
    }

    @SuppressLint("MissingPermission")
    @Throws(IOException::class, SecurityException::class)
    fun print(address: String, bytes: ByteArray) {
        if (!hasPermission()) throw SecurityException("Permissão de Bluetooth não concedida")
        val bluetoothAdapter = adapter ?: throw IOException("Este aparelho não possui Bluetooth")
        if (!bluetoothAdapter.isEnabled) throw IOException("O Bluetooth está desligado")

        bluetoothAdapter.cancelDiscovery()
        val device = bluetoothAdapter.getRemoteDevice(address)
        var socket: BluetoothSocket? = null
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect()
            socket.outputStream.use { output ->
                output.write(bytes)
                output.flush()
            }
        } catch (first: IOException) {
            runCatching { socket?.close() }
            socket = null
            // A number of generic ESC/POS models expose SPP only through channel 1.
            try {
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                socket = method.invoke(device, 1) as BluetoothSocket
                socket.connect()
                socket.outputStream.use { output ->
                    output.write(bytes)
                    output.flush()
                }
            } catch (fallback: Exception) {
                throw IOException("Não foi possível conectar à impressora ${device.name ?: address}", first)
            }
        } finally {
            runCatching { socket?.close() }
        }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

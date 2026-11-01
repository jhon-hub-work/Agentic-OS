package com.jhon.agenticos.core

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** In :core. The car he picked in the mode menu connects: Driving for 3 h, marked by = "car". It disconnects: Driving
 *  ends, but only if the car turned it on. Every other Bluetooth device is ignored. Both broadcasts are protected
 *  (only the system sends them) and need BLUETOOTH_CONNECT, asked for from the mode menu. */
class CarReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        try {
            val on = when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> true
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
                else -> return
            }
            val d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            if (Modes.isCar(d.address)) Modes.car(on)
        } catch (t: Throwable) { Log.w("Modes", "car broadcast not handled", t) }  // never crash :core over a mode
    }
}

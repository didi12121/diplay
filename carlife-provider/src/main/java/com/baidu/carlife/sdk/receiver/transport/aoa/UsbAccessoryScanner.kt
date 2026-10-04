package com.baidu.carlife.sdk.receiver.transport.aoa

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Configs
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.transport.communicator.Communicator
import com.baidu.carlife.sdk.receiver.transport.aoa.async.UsbAccessoryCommunicatorAsync
import com.baidu.carlife.sdk.util.Logger
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class UsbAccessoryScanner(
    private val context: CarLifeContext,
    private val callback: (Communicator) -> Unit
) : BroadcastReceiver(), Runnable {
    companion object {
        private const val ACTION_USB_PERMISSION = "com.baidu.carlifevehicle.connect.USB_PERMISSION"

        const val VID_IPHONE = 0x05AC
        const val STORAGE_INTERFACE_CONUT = 1
        const val STORAGE_INTERFACE_ID = 0
        const val STORAGE_INTERFACE_CLASS = 8
        const val STORAGE_INTERFACE_SUBCLASS = 6
        const val STORAGE_INTERFACE_PROTOCOL = 80
    }

    private val usbManager =
        context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val pendingPermissionDevices = LinkedBlockingQueue<UsbDevice>()

    private var isStopped: AtomicBoolean = AtomicBoolean(false)

    private val useAsyncMode: Boolean by lazy {
        context.getConfig(
            Configs.CONFIG_USE_ASYNC_USB_MODE,
            false
        )
    }

    private val filter by lazy {
        IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
    }


    fun scan() {
        isStopped.set(false)
        // 执行一次scan，不能在主线程
        context.applicationContext.registerReceiver(this, filter)
        context.io().execute(this)
    }

    fun ready() {
        isStopped.set(false)
    }

    fun stop() {
        if (!isStopped.getAndSet(true)) {
            try {
                context.applicationContext.unregisterReceiver(this)
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
            }
            context.main().removeCallbacks(this)
            pendingPermissionDevices.clear()
        }
    }

    private fun isUsbStorageDevice(device: UsbDevice?): Boolean {
        if (device == null) {
            Logger.d(Constants.TAG, "this device is null")
            return false
        }

        if (STORAGE_INTERFACE_CONUT == device.interfaceCount) {
            val usbInter = device.getInterface(STORAGE_INTERFACE_ID)
            if (STORAGE_INTERFACE_CLASS == usbInter.interfaceClass
                && STORAGE_INTERFACE_SUBCLASS == usbInter.interfaceSubclass
                && STORAGE_INTERFACE_PROTOCOL == usbInter.interfaceProtocol
            ) {
                Logger.d(Constants.TAG, "this device is mass storage 2")
                return true
            }
        }
        return false
    }


    override fun run() {
        pendingPermissionDevices.clear()
        Logger.d(Constants.TAG, "UsbAccessoryScanner deviceList", usbManager.deviceList.values)
        for (device in usbManager.deviceList.values) {
            if (isUsbStorageDevice(device)) {
                continue
            }

            val hasPermission = usbManager.hasPermission(device)
            Logger.d(
                Constants.TAG,
                "UsbAccessoryScanner deal with ",
                device,
                " has permission ",
                hasPermission
            )
            if (hasPermission) {
                if (openAccessory(device)) {
                    // 成功打开usb accessory，清空权限列表，返回设备
                    pendingPermissionDevices.clear()
                    break
                }
            } else if (device.vendorId != VID_IPHONE) {
                // 过滤掉iPhone
                pendingPermissionDevices.add(device)
            }
        }
        requestPendingPermission()
    }

    private fun requestPendingPermission() {
        // 请求USB权限，一个一个请求，直到找到AOA设备
        pendingPermissionDevices.poll()?.let {
            val permissionIntent = PendingIntent.getBroadcast(
                context.applicationContext,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.applicationContext.packageName), // explicit: mutable PendingIntent requires it (Android 12+)
                PendingIntent.FLAG_MUTABLE // USB permission reply fills extras into this intent (Android 12+)
            )
            usbManager.requestPermission(it, permissionIntent)
        }
    }

    private fun openAccessory(device: UsbDevice): Boolean {
        Logger.d(Constants.TAG, "UsbAccessoryScanner start openAccessory isStopped: ", isStopped)
        if (!isStopped.get()) {
            if (!useAsyncMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                // 高版本优先使用同步版本
                val communicator = UsbAccessoryCommunicator(context, device)
                if (communicator.openAccessory()) {
                    Logger.d(Constants.TAG, "UsbAccessoryScanner1 openAccessory success")
                    callback(communicator)
                    return true
                }
            } else {
                val communicator = UsbAccessoryCommunicatorAsync(context, device)
                if (communicator.openAccessory()) {
                    Logger.d(Constants.TAG, "UsbAccessoryScanner2 openAccessory success")
                    callback(communicator)
                    return true
                }
            }
        }
        Logger.d(Constants.TAG, "UsbAccessoryScanner openAccessory failed")
        return false
    }

    override fun onReceive(context: Context, intent: Intent) {
        Logger.d(Constants.TAG, "UsbAccessoryScanner action with ${intent.action}, $isStopped")
        if (isStopped.get()) {
            // 如果暂停扫描了，不在处理usb事件
            return
        }

        when (intent.action) {
            ACTION_USB_PERMISSION -> {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let {
                        if (openAccessory(it)) {
                            // 成功打开usb accessory，清空权限列表，返回设备
                            pendingPermissionDevices.clear()
                            return
                        }
                    }
                }
                requestPendingPermission()
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let {
                    if (isUsbStorageDevice(it)) {
                        return
                    }

                    Logger.d(
                        Constants.TAG,
                        "UsbAccessoryScanner ACTION_USB_DEVICE_ATTACHED with ",
                        it
                    )
                    if (usbManager.hasPermission(it)) {
                        if (openAccessory(it)) {
                            return
                        }
                    } else {
                        pendingPermissionDevices.add(it)
                        requestPendingPermission()
                    }
                }
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                isStopped.set(false)
            }
        }
    }
}


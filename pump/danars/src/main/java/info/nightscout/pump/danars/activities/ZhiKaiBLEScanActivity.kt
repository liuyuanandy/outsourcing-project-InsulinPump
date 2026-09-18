package info.nightscout.pump.danars.activities

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.core.app.ActivityCompat
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.extensions.safeEnable
import info.nightscout.pump.danars.R
import info.nightscout.pump.danars.databinding.DanarsBlescannerActivityBinding
import info.nightscout.pump.danars.databinding.ZhikaiBlescannerActivityBinding
import info.nightscout.pump.danars.events.EventDanaRSDeviceChange
import info.nightscout.pump.danars.events.EventZhiKaiDeviceChange
import java.util.regex.Pattern
import javax.inject.Inject

class ZhiKaiBLEScanActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var sp: SP
    @Inject lateinit var blePreCheck: BlePreCheck
    @Inject lateinit var context: Context
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper

    private var listAdapter: ListAdapter? = null
    private val devices = ArrayList<BluetoothDeviceItem>()
    private val bluetoothAdapter: BluetoothAdapter? get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    private val bluetoothLeScanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    private lateinit var binding: ZhikaiBlescannerActivityBinding

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ZhikaiBlescannerActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.e("--------------->","ZhiKaiBLEScanActivity onCreate")
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        title = rh.gs(info.nightscout.pump.dana.R.string.danars_pairing)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        blePreCheck.prerequisitesCheck(this)

        listAdapter = ListAdapter()
        binding.bleScannerListview.emptyView = binding.bleScannerNoDevice
        binding.bleScannerListview.adapter = listAdapter
        listAdapter?.notifyDataSetChanged()
    }

    override fun onResume() {
        super.onResume()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.safeEnable()
            Log.e("--------------->","ZhiKaiBLEScanActivity startScan")
            startScan()
        } else {
            Log.e("--------------->","ZhiKaiBLEScanActivity not startScan")

            ToastUtils.errorToast(context, context.getString(app.aaps.core.ui.R.string.need_connect_permission))
        }
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            stopScan()
        }
    }

    private fun startScan() =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            try {
                Log.e("--------->","startScan yes")

                bluetoothLeScanner?.startScan(mBleScanCallback)
            } catch (ignore: IllegalStateException) {
            } // ignore BT not on
        } else {
            Log.e("--------->","startScan no permission")
            ToastUtils.errorToast(context, context.getString(app.aaps.core.ui.R.string.need_connect_permission))
        }

    private fun stopScan() =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            try {
                bluetoothLeScanner?.stopScan(mBleScanCallback)
            } catch (ignore: IllegalStateException) {
            } // ignore BT not on
        } else {
            ToastUtils.errorToast(context, context.getString(app.aaps.core.ui.R.string.need_connect_permission))
        }

    @SuppressLint("MissingPermission")
    private fun addBleDevice(device: BluetoothDevice?) {
        if (device == null || device.name == null || device.name.equals("")) {
            return
        }

        val item = BluetoothDeviceItem(device)
        if (!(device.name.startsWith("APEX")&&device.name.length==12) || devices.contains(item)) {
            return
        }
        Log.e("--------->","addBleDevice device.name = "+device.name)
        Log.e("--------->","addBleDevice device.address = "+device.address)

        stopScan();
        devices.add(item)
        Handler(Looper.getMainLooper()).post { listAdapter?.notifyDataSetChanged() }
    }

    private val mBleScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addBleDevice(result.device)
        }
    }

    internal inner class ListAdapter : BaseAdapter() {

        override fun getCount(): Int = devices.size
        override fun getItem(i: Int): BluetoothDeviceItem = devices[i]
        override fun getItemId(i: Int): Long = 0

        override fun getView(i: Int, convertView: View?, parent: ViewGroup?): View {
            var v = convertView
            val holder: ViewHolder
            if (v == null) {
                v = View.inflate(applicationContext, R.layout.danars_blescanner_item, null)
                holder = ViewHolder(v)
                v.tag = holder
            } else {
                // reuse view if already exists
                holder = v.tag as ViewHolder
            }
            val item = getItem(i)
            holder.setData(item)
            return v!!
        }

        private inner class ViewHolder(v: View) : View.OnClickListener {

            private lateinit var item: BluetoothDeviceItem
            private val name: TextView = v.findViewById(R.id.ble_name)
            private val address: TextView = v.findViewById(R.id.ble_address)

            init {
                v.setOnClickListener(this@ViewHolder)
            }

            override fun onClick(v: View) {
                sp.putString(info.nightscout.pump.dana.R.string.key_zhikai_address, item.device.address)
                sp.putString(info.nightscout.pump.dana.R.string.key_zhikai_name, item.device.name)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    item.device.createBond()
                    rxBus.send(EventZhiKaiDeviceChange())
                } else {
                    ToastUtils.errorToast(context, context.getString(app.aaps.core.ui.R.string.need_connect_permission))
                }
                finish()
            }

            @SuppressLint("MissingPermission")
            fun setData(data: BluetoothDeviceItem) {
                Log.e("--------->","setData device.name = "+data.device.name)
                Log.e("--------->","setData device.address = "+data.device.address)

                name.text = data.device.name
                address.text = data.device.address
                item = data
            }

        }
    }

    //
    inner class BluetoothDeviceItem internal constructor(val device: BluetoothDevice) {

        override fun equals(other: Any?): Boolean {
            if (other !is BluetoothDeviceItem) {
                return false
            }
            return device.address == other.device.address
        }

        override fun hashCode(): Int = device.hashCode()
    }
}
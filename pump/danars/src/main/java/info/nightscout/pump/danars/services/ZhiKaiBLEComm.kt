package info.nightscout.pum

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.notifyAll
import app.aaps.core.utils.waitMillis
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.danars.encryption.BleEncryption
import info.nightscout.pump.dana.ZhiKaiPump
import info.nightscout.pump.dana.events.EventDanaRNewStatus
import info.nightscout.pump.danars.comm.DanaRSMessageHashTable
import info.nightscout.pump.danars.comm.DanaRSPacket
import info.nightscout.pump.danars.comm.ZhiKaiPacketAPSBasalSetTemporaryBasal
import info.nightscout.pump.danars.comm.ZhiKaiPacketBasalSetProfileBasalRate
import info.nightscout.pump.danars.comm.ZhiKaiPacketBasalSetTemporaryBasal
import info.nightscout.pump.danars.comm.ZhiKaiPacketSetBlous
import info.nightscout.pump.danars.comm.ZhiKaiPacketSetBlousStop
import info.nightscout.pump.danars.util.BleBluetoothUtil
import info.nightscout.pump.danars.util.CommandUtil
import info.nightscout.pump.danars.util.StringUtil
import java.lang.reflect.Method
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class ZhiKaiBLEComm @Inject internal constructor(
    private val injector: HasAndroidInjector,
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val context: Context,
    private val rxBus: RxBus,
    private val sp: SP,
    private val danaRSMessageHashTable: DanaRSMessageHashTable,
    private val zhiKaiPump: ZhiKaiPump,
    private val zhiKaiPlugin: info.nightscout.pump.danars.ZhiKaiPlugin,
    private val bleEncryption: BleEncryption,
    private val pumpSync: PumpSync,
    private val dateUtil: DateUtil,
    private val uiInteraction: UiInteraction,

) {

    companion object {
        private const val WRITE_DELAY_MILLIS: Long = 50
        private const val UART_READ_UUID = "0000ffe4-0000-1000-8000-00805f9b34fb"
        private const val UART_WRITE_UUID = "0000ffe9-0000-1000-8000-00805f9b34fb"
        private const val UART_BLE5_UUID = "00002902-0000-1000-8000-00805f9b34fb" //通知通道描述符uuid
    }
    private var processedMessage: DanaRSPacket? = null
    private val mSendQueue = ArrayList<ByteArray>()
    private val bluetoothAdapter: BluetoothAdapter? get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    private var connectDeviceName: String? = null
    private var bluetoothGatt: BluetoothGatt? = null

    var isConnected = false
    var isConnecting = false
    private var uartRead: BluetoothGattCharacteristic? = null
    private var uartWrite: BluetoothGattCharacteristic? = null
    //接收数据所需-----------------------------------------
    private var isReading:Boolean=false//是否正在读取通知内容
    private lateinit var dataReceived:ByteArray //通知一条数据的内容
    private var totalNum:Int=0//通知-数据总条数
    private var dataId:String=""//通知dataId
    private var isErro=false
    private var lastErroTime:Long=0
    private var lastGetNotifyDataTime:Long = 0;

    @Synchronized
    fun connect(from: String, address: String?):Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            ToastUtils.errorToast(context, context.getString(app.aaps.core.ui.R.string.need_connect_permission))
            aapsLogger.error(LTag.PUMPBTCOMM, "missing permission: $from")
            return false
        }
        aapsLogger.debug(LTag.PUMPBTCOMM, "Initializing BLEComm.")
        if (address == null) {
            aapsLogger.error("unspecified address.")
            return false
        }
        if (bluetoothAdapter == null) {
            aapsLogger.error("Unable to obtain a BluetoothAdapter.")
            return false
        }
        if(!bluetoothAdapter!!.isEnabled){
            aapsLogger.error(" BluetoothAdapter not enable.")
        }
        val device = BleBluetoothUtil.getBluetoothDevice(context,address)
        if (device == null) {
            Log.e("------------>","设备==null")
            aapsLogger.error("Device not found.  Unable to connect from: $from")
            return false
        }
        if(device.name!=null){
            connectDeviceName = device.name;
        }
        Log.e("------------>","设备!=null")

        isConnected = false
        isConnecting = true
        bufferLength = 0
        aapsLogger.debug(LTag.PUMPBTCOMM, "Trying to create a new connection from: $from")
        bluetoothGatt = device.connectGatt(context, false, mGattCallback)
        return true
    }

    @Synchronized
    fun stopConnecting() {
        isConnecting = false
    }

    @Synchronized
    fun disconnect(from: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            aapsLogger.error(LTag.PUMPBTCOMM, "missing permission: $from")
            return
        }
        aapsLogger.debug(LTag.PUMPBTCOMM, "disconnect from: $from")
        if (bluetoothGatt == null) {
            aapsLogger.error("disconnect not possible: (mBluetoothGatt == null) " + (bluetoothGatt == null))
            return
        }
        setCharacteristicNotification(uartReadBTGattChar, false)
        bluetoothGatt?.disconnect()
        isConnected = false
        SystemClock.sleep(2000)
    }
    @Synchronized
    fun writeCommand(data: ByteArray) {
        Thread(Runnable {
            SystemClock.sleep(WRITE_DELAY_MILLIS)
            if (bluetoothGatt == null) {
                isConnecting = false
                isConnected = false
                return@Runnable
            }
            BleBluetoothUtil.writeCharacteristic(context,uartWriteBTGattChar,bluetoothGatt,data,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }).start()
        SystemClock.sleep(50)
    }
    @SuppressLint("MissingPermission")
    @Synchronized fun close() {
        aapsLogger.debug(LTag.PUMPBTCOMM, "BluetoothAdapter close")
        bluetoothGatt?.let {
            val b = refreshGattCache(it)
            Log.e("----------->","gatt refresh result = $b")
        }
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
    fun refreshGattCache(gatt: BluetoothGatt):Boolean {
        var result:Boolean = false
        try{
            val localMethod = gatt.javaClass.getMethod(
                "refresh", *arrayOfNulls(0)
            )
            if(localMethod!=null){
                Log.e("----------->","gatt refresh method !=null")
                localMethod.setAccessible(true)
                result = localMethod.invoke(gatt,*arrayOfNulls(0)) as Boolean
            }else{
                Log.e("----------->","gatt refresh method ==null")
            }
        }catch(e:Exception){
            Log.e("----------->","An exception occured while refreshing device")
        }
        return result
    }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private val mGattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.e("--------------->","onConnectionStateChange status=$status;newState=$newState")
            if(status!=0){//出现133，19等异常值时，
                close()
                isConnected = false
                isConnecting = false
                isReading = false
                rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
                aapsLogger.debug(LTag.PUMPBTCOMM, "Device was disconnected " + gatt.device.name) //Device was disconnected
                aapsLogger.debug(LTag.PUMPBTCOMM, "出现异常情况，status=$status")
                return
            }
            onConnectionStateChangeSynchronized(gatt, newState) // call it synchronized
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "onServicesDiscovered")
            Log.e("------------>","onServicesDiscovered")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.e("------------>","onServicesDiscovered GATT_SUCCESS")
                findCharacteristic()
            }else{
                Log.e("------------>","onServicesDiscovered Fail")
            }

        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
//            Log.e("------------>","onCharacteristicRead")
            readDataParsing(characteristic,characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
//            Log.e("------------>","onCharacteristicRead")
            readDataParsing(characteristic,characteristic.value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
//            Log.e("------------>","onCharacteristicWrite")
            if (status == BluetoothGatt.GATT_SUCCESS) {
//                Log.e("------------>","onCharacteristicWrite GATT_SUCCESS")
            }
            Thread {
                synchronized(mSendQueue) {
                    // after message sent, check if there is the rest of the message waiting and send it
                    if (mSendQueue.size > 0) {
                        val bytes = mSendQueue[0]
                        mSendQueue.removeAt(0)
                        Log.e("-------------->","发出指令："+ StringUtil.bytesToHexString(bytes))
                        writeCommand(bytes)
                    }
                }
            }.start()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun setCharacteristicNotification(characteristic: BluetoothGattCharacteristic?, enabled: Boolean) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "setCharacteristicNotification")
        if (bluetoothGatt == null) {
            aapsLogger.error("BluetoothAdapter not initialized_ERROR")
            isConnecting = false
            isConnected = false
            return
        }
        bluetoothGatt?.setCharacteristicNotification(characteristic, enabled)
        characteristic?.getDescriptor(UUID.fromString(UART_BLE5_UUID))?.let {
            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            bluetoothGatt?.writeDescriptor(it)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun writeCharacteristicNoResponse(characteristic: BluetoothGattCharacteristic, data: ByteArray) {
        Thread(Runnable {
            SystemClock.sleep(WRITE_DELAY_MILLIS)
            characteristic.value = data
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            //aapsLogger.debug("writeCharacteristic:" + DanaRS_Packet.toHexString(data))
            bluetoothGatt?.writeCharacteristic(characteristic)
        }).start()
        SystemClock.sleep(50)
    }
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, data: ByteArray) {
        Thread(Runnable {
            SystemClock.sleep(WRITE_DELAY_MILLIS)
            characteristic.value = data
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            //aapsLogger.debug("writeCharacteristic:" + DanaRS_Packet.toHexString(data))
            bluetoothGatt?.writeCharacteristic(characteristic)
        }).start()
        SystemClock.sleep(50)
    }
    private val uartReadBTGattChar: BluetoothGattCharacteristic
        get() = uartRead
            ?: BluetoothGattCharacteristic(UUID.fromString(UART_READ_UUID), BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0).also { uartRead = it }

    private val uartWriteBTGattChar: BluetoothGattCharacteristic
        get() = uartWrite
            ?: BluetoothGattCharacteristic(UUID.fromString(UART_WRITE_UUID), BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, 0).also { uartWrite = it }

    private fun getSupportedGattServices(): List<BluetoothGattService>? {
        aapsLogger.debug(LTag.PUMPBTCOMM, "getSupportedGattServices")
        if (bluetoothGatt == null) {
            aapsLogger.error("BluetoothAdapter not initialized_ERROR")
            isConnecting = false
            isConnected = false
            return null
        }
        return bluetoothGatt?.services
    }

    private fun findCharacteristic() {
        val gattServices = getSupportedGattServices() ?: return
        var uuid: String
        for (gattService in gattServices) {
            val gattCharacteristics = gattService.characteristics
            for (gattCharacteristic in gattCharacteristics) {
                uuid = gattCharacteristic.uuid.toString()
                if (UART_READ_UUID == uuid) {
                    uartRead = gattCharacteristic
                    setCharacteristicNotification(uartRead, true)
                }
                if (UART_WRITE_UUID == uuid) {
                    uartWrite = gattCharacteristic
                }
            }
        }

        var thread = Thread{
            Thread.sleep(400)
            rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.CONNECTED))
            isConnected = true;
            isConnecting = false;
        }
        thread.start()



//        //读设备名称
//        for (service in gattServices) {
//            for (i in service.characteristics.indices) {
//                if (service.characteristics[i].uuid.toString().startsWith("00002a00")) { //读取设备名称
//                    BleBluetoothUtil.readCharacteristic(context, service.characteristics.get(i), bluetoothGatt)
//                }
//            }
//        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun onConnectionStateChangeSynchronized(gatt: BluetoothGatt, newState: Int) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "onConnectionStateChange")
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            gatt.discoverServices()
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            close()
            isConnected = false
            isConnecting = false
            isReading = false
            rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
            aapsLogger.debug(LTag.PUMPBTCOMM, "Device was disconnected " + gatt.device.name) //Device was disconnected
        }
    }

    private val readBuffer = ByteArray(1024)
    @Volatile private var bufferLength = 0

    private fun addToReadBuffer(buffer: ByteArray) {
        //log.debug("addToReadBuffer " + DanaRS_Packet.toHexString(buffer));
        if (buffer.isEmpty()) return

        synchronized(readBuffer) {
            // Append incoming data to input buffer
            System.arraycopy(buffer, 0, readBuffer, bufferLength, buffer.size)
            bufferLength += buffer.size
        }
    }

    private fun readDataParsing(recharacteristic: BluetoothGattCharacteristic,receivedData: ByteArray) {
        var str = String(receivedData, charset("UTF-8"))
//        Log.e("-------------->","收到原始数据:"+StringUtil.bytesToHexString(receivedData))
        // value为设备发送的数据，根据数据协议进行解析
        if (recharacteristic.getUuid().toString().uppercase(Locale.getDefault())
                .startsWith("0000FFE4")
        ) { //通知通道，通知内容
//            Log.e("-------------->","收到数据:"+StringUtil.bytesToHexString(receivedData))
            lastGetNotifyDataTime = System.currentTimeMillis()
            if(processedMessage?.opCode==BleEncryption.ZHIKAI_PACKET_OPCODE_SET_BLOUS){
                if(!isReading){
                    isReading= true;
                }
                processMessage(receivedData)
                if(processedMessage?.isReceived == true){
                    isReading = false
                }
            }else if(processedMessage?.opCode==BleEncryption.ZHIKAI_PACKET__OPCODE_BASAL__SET_PROFILE_BASAL_RATE){
                if(!isReading){
                    isReading= true;
                }
                processMessage(receivedData)
                if(processedMessage?.isReceived == true){
                    isReading = false
                }
            }else if(processedMessage?.opCode==BleEncryption.ZHIKAI_PACKET_OPCODE_BASAL_SET_TEMPORARY_BASAL){
                if(!isReading){
                    isReading= true;
                }
                processMessage(receivedData)
                if(processedMessage?.isReceived == true){
                    isReading = false
                }
            }else if(processedMessage?.opCode==BleEncryption.ZHIKAI_PACKET_OPCODE_BASAL_APS_SET_TEMPORARY_BASAL){
                if(!isReading){
                    isReading= true;
                }
                processMessage(receivedData)
                if(processedMessage?.isReceived == true){
                    isReading = false
                }

            }else if(processedMessage?.opCode==BleEncryption.ZHIKAI_PACKET__OPCODE_BASAL__CANCEL_TEMPORARY_BASAL){
                if(!isReading){
                    isReading= true;
                }
                processMessage(receivedData)
                if(processedMessage?.isReceived == true){
                    isReading = false
                }
            }else{
                if(!isReading){
                    val space = System.currentTimeMillis() - lastErroTime
                    if (isErro && space <= 3000) { //3秒内不响应，等待错误数据接收完成
                        return
                    }
                    isErro = false
                    totalNum = StringUtil.hexStringToInt(StringUtil.bytesToHexString(receivedData).substring(4, 6))
                    dataId = StringUtil.bytesToHexString(receivedData).substring(8, 10)
                    isReading = true;
                    dataReceived = ByteArray(0)
                }
                dataReceived = CommandUtil.arraycopyByteData(dataReceived, receivedData)
                if (isErro) {
                    lastErroTime = System.currentTimeMillis()
                    isReading = false
                    return
                }
                isErro = judgeIsErro()
                var isFinish = judgeIsReceivedFinished()
//                Log.e("--------------->", " isFinish:$isFinish")
                if (!isFinish) {
                    return
                }
                processMessage(dataReceived)
                rxBus.send(EventDanaRNewStatus())
            }
        }
    }
    fun sendMessage(message: DanaRSPacket) {
        if (bluetoothGatt == null) {
            aapsLogger.debug(LTag.PUMPBTCOMM, ">>>>> IGNORING (NOT CONNECTED) " + message.friendlyName)
            return
        }
        isReading = false;
        if(message.opCode==BleEncryption.ZHIKAI_PACKET_OPCODE_SET_BLOUS_STOP){//停止大剂量，特殊处理
            var bolusStopMessage = message as ZhiKaiPacketSetBlousStop
            if(processedMessage is ZhiKaiPacketSetBlous){
                (processedMessage as ZhiKaiPacketSetBlous).setblousStopMessage = bolusStopMessage
            }
        }else{
            processedMessage = message
        }
        var bytes :ByteArray = ByteArray(0)
        Log.e("---------->","sendMessage"+message.javaClass.simpleName)
        if(message.opCode==BleEncryption.ZHIKAI_PACKET_OPCODE_SYS_STUS){
            bytes= CommandUtil.generateReadcommand("35","00AA",zhiKaiPump.serialNumber)
        }else if(message.opCode==BleEncryption.ZHIKAI_PACKET_OPCODE_BOLUS_GET_BOLUS_HISTORY_INFORMATION){
            bytes= CommandUtil.generateReadcommand("55","01AA",zhiKaiPump.serialNumber)
        } else if (message.opCode==BleEncryption.ZHIKAI_PACKET_OPCODE_SET_BLOUS){
            bytes = CommandUtil.generateSetBLousCommand("35","12AA",zhiKaiPump.serialNumber,zhiKaiPump.bolusAmountToBeDelivered,1)
        }else if(message.opCode==BleEncryption.ZHIKAI_PACKET_OPCODE_SET_BLOUS_STOP){
            bytes = CommandUtil.generateSetBlousStopCommand("55","02AA",zhiKaiPump.serialNumber)
        }else if(message.opCode==BleEncryption.ZHIKAI_PACKET__OPCODE_BASAL__SET_PROFILE_BASAL_RATE){
            var values:String =(message as ZhiKaiPacketBasalSetProfileBasalRate).getValuesHexString()
            bytes = CommandUtil.generateSetBasalRate("35","00AA",zhiKaiPump.serialNumber,values)
        }else if(message.opCode ==BleEncryption.ZHIKAI_PACKET_OPCODE_BASAL_SET_TEMPORARY_BASAL){
            var ratio:Int =(message as ZhiKaiPacketBasalSetTemporaryBasal).temporaryBasalRatio
            var ratioStr:String = StringUtil.intToHexString(ratio)
            val ratioStrFinal:String
            if(ratioStr.length==1){ //例如：1-> 0100
                ratioStrFinal = "0"+ratioStr+"00"
            }else if(ratioStr.length==2){//例如：22->2200
                ratioStrFinal = ratioStr+"00"
            }else if(ratioStr.length==3){//例如： 201 ->0102
                ratioStrFinal = ratioStr.substring(1)+"0"+ratioStr.substring(0,1)
            }else{//例如： 2201 ->0122
                ratioStrFinal = ratioStr.substring(2)+ratioStr.substring(0,2)
            }
            var duration:Int = (message as ZhiKaiPacketBasalSetTemporaryBasal).temporaryBasalDuration/15
            var durationStr:String = StringUtil.intToHexString(duration)
            var durationStrFinal:String;
            if(durationStr.length==1){
                durationStrFinal = "0"+durationStr
            }else{
                durationStrFinal = durationStr
            }
            Log.e("------------>","durationStrFinal = "+durationStrFinal+";ratioStrFinal = "+ratioStrFinal)
            bytes = CommandUtil.generateSetTempsBasalRate("35","02AA",zhiKaiPump.serialNumber,ratioStrFinal,durationStrFinal)

        }else if(message.opCode ==BleEncryption.ZHIKAI_PACKET_OPCODE_BASAL_APS_SET_TEMPORARY_BASAL){
            var ratio:Int =(message as ZhiKaiPacketAPSBasalSetTemporaryBasal).temporaryBasalRatio
            var ratioStr:String = StringUtil.intToHexString(ratio)
            var ratioStrFinal:String;
            if(ratioStr.length==1){ //例如：1  0001-> 0100
                ratioStrFinal = "0"+ratioStr+"00"
            }else if(ratioStr.length==2){//例如：22  0022->2200
                ratioStrFinal = ratioStr+"00"
            }else if(ratioStr.length==3){//例如： 201  0201->0102
                ratioStrFinal = ratioStr.substring(1)+"0"+ratioStr.substring(0,1)
            }else{//例如： 2201 ->0122
                ratioStrFinal = ratioStr.substring(2)+ratioStr.substring(0,2)
            }
            var duration:Int = (message as ZhiKaiPacketAPSBasalSetTemporaryBasal).temporaryBasalDuration/15
            var durationStr:String = StringUtil.intToHexString(duration)
            var durationStrFinal:String;
            if(durationStr.length==1){
                durationStrFinal = "0"+durationStr
            }else{
                durationStrFinal = durationStr
            }
            Log.e("------------>","durationStrFinal = "+durationStrFinal+";ratioStrFinal = "+ratioStrFinal)
            bytes = CommandUtil.generateSetTempsBasalRate("35","02AA",zhiKaiPump.serialNumber,ratioStrFinal,durationStrFinal)
        }else if(message.opCode ==BleEncryption.ZHIKAI_PACKET__OPCODE_BASAL__CANCEL_TEMPORARY_BASAL){
            bytes = CommandUtil.generateSetCancelTempsBasalRate("35","05AA",zhiKaiPump.serialNumber)
        }

        if (mSendQueue.size > 0) {
            // Split to parts per 20 bytes max
            while (true) {
                if (bytes.size > 20) {
                    val addBytes = ByteArray(20)
                    System.arraycopy(bytes, 0, addBytes, 0, addBytes.size)
                    val reBytes = ByteArray(bytes.size - addBytes.size)
                    System.arraycopy(bytes, addBytes.size, reBytes, 0, reBytes.size)
                    bytes = reBytes
                    synchronized(mSendQueue) { mSendQueue.add(addBytes) }
                } else {
                    synchronized(mSendQueue) { mSendQueue.add(bytes) }
                    break
                }
            }
        } else {
            if (bytes.size > 20) {
                // Cut first 20 bytes
                val sendBytes = ByteArray(20)
                System.arraycopy(bytes, 0, sendBytes, 0, sendBytes.size)
                var reBytes = ByteArray(bytes.size - sendBytes.size)
                System.arraycopy(bytes, sendBytes.size, reBytes, 0, reBytes.size)
                bytes = reBytes
                Log.e("-------------->","发出指令："+ StringUtil.bytesToHexString(sendBytes))
                writeCommand(sendBytes)
//                // and send
//                writeCharacteristicNoResponse(uartWriteBTGattChar, sendBytes)
                // The rest split to parts per 20 bytes max
                while (true) {
                    if (bytes.size > 20) {
                        val addBytes = ByteArray(20)
                        System.arraycopy(bytes, 0, addBytes, 0, addBytes.size)
                        reBytes = ByteArray(bytes.size - addBytes.size)
                        System.arraycopy(bytes, addBytes.size, reBytes, 0, reBytes.size)
                        bytes = reBytes
                        synchronized(mSendQueue) { mSendQueue.add(addBytes) }
                    } else {
                        synchronized(mSendQueue) { mSendQueue.add(bytes) }
                        break
                    }
                }
            } else {
                Log.e("-------------->","发出指令："+ StringUtil.bytesToHexString(bytes))
                writeCommand(bytes)
//                writeCharacteristicNoResponse(uartWriteBTGattChar, bytes)
            }
        }
        synchronized(message) {
            try {
                var timeStart = System.currentTimeMillis();
                if(message.opCode==BleEncryption.ZHIKAI_PACKET_OPCODE_SET_BLOUS){//大剂量用时会比较长，单独处理
                    (message as ZhiKaiPacketSetBlous).timeLastReceiedData = System.currentTimeMillis();
                    while(true) {
                        message.waitMillis(500)
                        var timeNow = System.currentTimeMillis()
                        if (zhiKaiPump.bolusStopped || timeNow - (message as ZhiKaiPacketSetBlous).timeLastReceiedData > 10000) {
                            break;
                        }
                    }
                }else{
                    while(true) {
                        message.waitMillis(500)
                        var timeNow = System.currentTimeMillis()
                        if (message.isReceived || timeNow - timeStart > 8000) {
                            break;
                        }
                    }
                }
            } catch (e: InterruptedException) {
                aapsLogger.error("sendMessage InterruptedException", e)
            }
        }
        if(message.opCode==BleEncryption.ZHIKAI_PACKET_OPCODE_SET_BLOUS){//特殊处理
            if(!zhiKaiPump.bolusStopped){//未正确接收到数据
                message.handleMessageNotReceived()
//                disconnect("Reply not received")
            }
        }else{
            if(message.opCode==BleEncryption.ZHIKAI_PACKET_OPCODE_SET_BLOUS_STOP){//不处理
                return
            }
            if (!message.isReceived) {
                Log.e("-------------------->","Reply not received " + message.friendlyName)
                aapsLogger.warn(LTag.PUMPBTCOMM, "Reply not received " + message.friendlyName)
                message.handleMessageNotReceived()
//                disconnect("Reply not received")
            }else{
                Log.e("-------------------->","Reply received " + message.friendlyName)
            }
        }

    }

    // process common packet response
    private fun processMessage(byteArray: ByteArray) {
        Log.e("-------------->","${processedMessage?.friendlyName} 收到数据:"+StringUtil.bytesToHexString(byteArray))
        var message: DanaRSPacket?=null
        message = processedMessage
        if (message != null) {
            message.handleMessage(byteArray)
            message.setReceived()
            synchronized(message) {
                message.notifyAll()
            }
        } else
            aapsLogger.error("Unknown message received " + DanaRSPacket.toHexString(byteArray))
    }
    //判断接收数据是否出错
    private fun judgeIsErro(): Boolean {
        var hexString = StringUtil.bytesToHexString(dataReceived)
        var dataNum = 0
        while (hexString.length > 0) { //表示第二个字节可读
            if (hexString.length > 4) {
                if (hexString.substring(0, 2).uppercase(Locale.getDefault()) != "AA") {
                    return true
                }
                val dataLength = StringUtil.hexStringToInt(hexString.substring(2, 4))
                if (hexString.length >= dataLength * 2) {
                    dataNum = dataNum + 1
                    hexString = hexString.substring(dataLength * 2)
                } else {
                    break
                }
            } else {
                break
            }
        }
        return false
    }
    private fun judgeIsReceivedFinished(): Boolean {
        var hexString = StringUtil.bytesToHexString(dataReceived)
        var dataNum = 0
        while (hexString.length > 0) { //表示第二个字节可读
            if (hexString.length > 4) {
                val dataLength = StringUtil.hexStringToInt(hexString.substring(2, 4))
                if (hexString.length >= dataLength * 2) {
                    dataNum = dataNum + 1
                    hexString = hexString.substring(dataLength * 2)
                } else {
                    break
                }
            } else {
                break
            }
        }
//        Log.e("--------------->", "dataNum = $dataNum;totalNum = $totalNum")
        if (dataNum == totalNum) {
            return true
        }
        return false
    }
}
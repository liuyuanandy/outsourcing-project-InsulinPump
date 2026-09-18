package info.nightscout.pump.danars.comm

import android.util.Log
import app.aaps.core.interfaces.logging.LTag
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.danars.encryption.BleEncryption
import info.nightscout.pump.dana.ZhiKaiPump
import info.nightscout.pump.danars.util.StringUtil
import javax.inject.Inject

class ZhiKaiSystus (
    injector: HasAndroidInjector
) : DanaRSPacket(injector) {

    @Inject lateinit var zhiKaiPump: ZhiKaiPump

    init {
        opCode = BleEncryption.ZHIKAI_PACKET_OPCODE_SYS_STUS
        aapsLogger.debug(LTag.PUMPCOMM, "New message")
    }

    override fun handleMessage(data: ByteArray) {
        val hexString: String = bytesToHex(data)
        val dataLength: Int = StringUtil.hexStringToInt(hexString.substring(2, 4))
        val dataStr: String = hexString.substring(12, dataLength * 2 - 4)
        //电量
        Log.e("--------------->","读取到电量")
         var pumpBattery:Int= StringUtil.hexStringToInt(dataStr.substring(0,2))
        if (pumpBattery ==0) {
            zhiKaiPump.batteryRemaining = 1 //小于1%
        } else if (pumpBattery ==1) {
            zhiKaiPump.batteryRemaining = 25 //小于25%
        } else if (pumpBattery ==2) {
            zhiKaiPump.batteryRemaining = 50 //小于50%
        } else if (pumpBattery==3) {
            zhiKaiPump.batteryRemaining = 75 //小于75%
        } else if (pumpBattery==4) {
            zhiKaiPump.batteryRemaining = 100 //小于100%
        }
        //日已输入量
        var dayTotalStr:String = dataStr.substring(32,40)
        //高低位取反
        val value1 = StringUtil.hexStringToInt(dayTotalStr.substring(6, 8))
        val value2 = StringUtil.hexStringToInt(dayTotalStr.substring(4, 6))
        val value3 = StringUtil.hexStringToInt(dayTotalStr.substring(2, 4))
        val value4 = StringUtil.hexStringToInt(dayTotalStr.substring(0, 2))
        zhiKaiPump.dailyTotalUnits = (value1 * 256 * 256 * 256 + value2 * 256 * 256 + value3 * 256 + value4) * 0.025
        //每日最大值
        var maxDailyStr:String = dataStr.substring(40,48)
        var maxDaily:Int = StringUtil.hexStringToInt(maxDailyStr.substring(6,8)+maxDailyStr.substring(4,6)+maxDailyStr.substring(2,4)+maxDailyStr.substring(0,2))
        zhiKaiPump.maxDailyTotalUnits = (maxDaily).toDouble()
        //基础率最大值
        var maxBasalStr:String = dataStr.substring(40,48)
        val lowMaxBasal = StringUtil.hexStringToInt(maxBasalStr.substring(2, 4))
        val highMaxBasal = StringUtil.hexStringToInt(maxBasalStr.substring(0, 2))
        var maxBasal:Double = (lowMaxBasal * 256 + highMaxBasal) * 0.025
        zhiKaiPump.maxBasal = maxBasal
        //大剂量预设值
        var bolusMaxStr:String = dataStr.substring(52,56)
        val lowBolusMax = StringUtil.hexStringToInt(bolusMaxStr.substring(2, 4))
        val highBolusMax = StringUtil.hexStringToInt(bolusMaxStr.substring(0, 2))
        var bolusMax:Double = (lowBolusMax * 256 + highBolusMax) * 0.025
        zhiKaiPump.maxBolus = bolusMax
        //大剂量预设值
        var bolusStr:String = dataStr.substring(56,60)
        val lowBolus = StringUtil.hexStringToInt(bolusStr.substring(2, 4))
        val highBolus = StringUtil.hexStringToInt(bolusStr.substring(0, 2))
        var bolus:Double = (lowBolus * 256 + highBolus) * 0.025
        zhiKaiPump.blous = bolus
//        zhiKaiPump.iob = 99.0


        //当前基础率
        var currentBasalStr:String = dataStr.substring(152,156)
        if(currentBasalStr.equals("FFFF")){
            zhiKaiPump.isCurrenBasalStop = true;
            zhiKaiPump.currentBasal =0.0;
        }else{
            zhiKaiPump.isCurrenBasalStop = false
            val lowBasal = StringUtil.hexStringToInt(currentBasalStr.substring(2, 4))
            val highBasal = StringUtil.hexStringToInt(currentBasalStr.substring(0, 2))
            var currentBasal:Double = (lowBasal * 256 + highBasal) * 0.025
            zhiKaiPump.currentBasal = currentBasal
        }

        //临时基础率
        var tempBasalStr:String = dataStr.substring(160,164)
        val low = StringUtil.hexStringToInt(tempBasalStr.substring(2, 4))
        val high = StringUtil.hexStringToInt(tempBasalStr.substring(0, 2))
        var tempBasal:Double = (low * 256 + high) * 0.025
        zhiKaiPump.tempBasal = tempBasal
        //临时基础率模式
        zhiKaiPump.tempBaselMode =StringUtil.hexStringToInt(dataStr.substring(164,168).substring(2,4)+dataStr.substring(164,168).substring(0,2))
        Log.e("-------------->","zhiKaiPump.tempBaselMode = ${zhiKaiPump.tempBaselMode}")
        if(zhiKaiPump.tempBaselMode==0){//百分比模式
            //获取百分比
            Log.e("-------------->", "zhiKaiPump.tempBasal*100 = ${zhiKaiPump.tempBasal * 100}")
            Log.e("-------------->","zhiKaiPump.currentBasal = ${zhiKaiPump.currentBasal}")
            zhiKaiPump.tempBasalPercent = (zhiKaiPump.tempBasal*100/zhiKaiPump.currentBasal).toInt();
            Log.e("-------------->","zhiKaiPump.tempBasalPercent = ${zhiKaiPump.tempBasalPercent}")
            //获取临时基础率执行总时间
            zhiKaiPump.tempBasalDuration =
                StringUtil.hexStringToInt(dataStr.substring(168,172).substring(2,4)+dataStr.substring(168,172).substring(0,2)).toLong()*60*1000
            Log.e("------------->","执行总时间:"+zhiKaiPump.tempBasalDuration)
            //获取临时基础率已执行时间
            var tempDurationed =  StringUtil.hexStringToInt(dataStr.substring(172,176).substring(2,4)+dataStr.substring(172,176).substring(0,2)).toLong()*60*1000
            Log.e("------------->","已执行时间:"+tempDurationed)
            zhiKaiPump.tempBasalStart = System.currentTimeMillis()-tempDurationed;
        }

        //剩余药量
        var reservoirRemainingStr:String = dataStr.substring(104,112)
        val reservoirRemainingInt:Int = StringUtil.hexStringToInt(reservoirRemainingStr.substring(6, 8)+reservoirRemainingStr.substring(4, 6)+reservoirRemainingStr.substring(2, 4)+reservoirRemainingStr.substring(0, 2))
        val reservoirRemainingDouble:Double=reservoirRemainingInt/1000.0
        zhiKaiPump.reservoirRemainingUnits = reservoirRemainingDouble
        failed = false
//        aapsLogger.debug(LTag.PUMPCOMM, "Dec ratio: ${zhiKaiPump.decRatio}%")
    }
    override val friendlyName: String = "BASAL_GET_SYS_STUS"
}
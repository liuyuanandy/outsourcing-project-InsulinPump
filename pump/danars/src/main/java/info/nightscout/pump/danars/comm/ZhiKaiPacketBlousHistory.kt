package info.nightscout.pump.danars.comm

import android.util.Log
import app.aaps.core.interfaces.logging.LTag
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.danars.encryption.BleEncryption
import info.nightscout.pump.dana.DanaPump
import info.nightscout.pump.dana.ZhiKaiPump
import info.nightscout.pump.danars.util.CommandUtil
import info.nightscout.pump.danars.util.StringUtil
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import javax.inject.Inject

class ZhiKaiPacketBlousHistory (
    injector: HasAndroidInjector
) : DanaRSPacket(injector) {

    @Inject
    lateinit var danaPump: ZhiKaiPump

    init {
        opCode = BleEncryption.ZHIKAI_PACKET_OPCODE_BOLUS_GET_BOLUS_HISTORY_INFORMATION
        aapsLogger.debug(LTag.PUMPCOMM, "New message")
    }

    override fun handleMessage(datas: ByteArray) {
        Log.e("---------->","ZhiKaiPacketBlousHistory handleMessage")

        var data = CommandUtil.arrayCopy(datas,0,22)
        var data2 = CommandUtil.arrayCopy(data,6,14)

        var blousAmountData = CommandUtil.arrayCopy(data2, 6, 2);//预设值
        var blousAmountDataDone = CommandUtil.arrayCopy(data2, 8, 2);//实际输注值
        Log.e("---------->","blousAmountDataDone data  = "+StringUtil.bytesToHexString(blousAmountDataDone))

        var strData = StringUtil.bytesToHexString(blousAmountDataDone)
        danaPump.lastBolusAmount =  StringUtil.hexStringToInt(strData.substring(2,4)+strData.substring(0,2))*0.025;
        Log.e("---------->","danaPump.lastBolusAmount  = "+danaPump.lastBolusAmount )
        var dataString = StringUtil.bytesToHexString(data2)
        var year = ("20"+dataString.substring(0,2)).toInt()
        var month = dataString.substring(2,4).toInt()
        var day = dataString.substring(4,6).toInt()
        val hours = dataString.substring(6,8).toInt()
        val minutes = dataString.substring(8,10).toInt()
        val seconds = dataString.substring(10,12).toInt()
        if (danaPump.usingUTC) danaPump.lastBolusTime = DateTime.now().withZone(DateTimeZone.UTC).withYear(year).withMonthOfYear(month).withDayOfMonth(day).withHourOfDay(hours).withMinuteOfHour(minutes).withSecondOfMinute(seconds).millis
        else danaPump.lastBolusTime = DateTime.now().withYear(year).withMonthOfYear(month).withDayOfMonth(day).withHourOfDay(hours).withMinuteOfHour(minutes).withSecondOfMinute(seconds).millis
        Log.e("---------->","danaPump.lastBolusTime  = "+dateUtil.dateAndTimeAndSecondsString(danaPump.lastBolusTime))


    }

    override val friendlyName: String = "BOLUS__GET_BOLUS_HISTORY_INFORMATION"
}
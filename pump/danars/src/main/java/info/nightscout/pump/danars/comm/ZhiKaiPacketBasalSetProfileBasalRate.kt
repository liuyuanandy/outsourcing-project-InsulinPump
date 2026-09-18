package info.nightscout.pump.danars.comm

import android.util.Log
import app.aaps.core.interfaces.logging.LTag
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.danars.encryption.BleEncryption
import info.nightscout.pump.dana.ZhiKaiPump
import info.nightscout.pump.danars.util.StringUtil
import javax.inject.Inject

class ZhiKaiPacketBasalSetProfileBasalRate (
    injector: HasAndroidInjector,
    private var profileNumber: Int,
    private var profileBasalRate: Array<Double>
) : DanaRSPacket(injector) {
    @Inject
    lateinit var danaPump: ZhiKaiPump

    init {
        opCode = BleEncryption.ZHIKAI_PACKET__OPCODE_BASAL__SET_PROFILE_BASAL_RATE
        aapsLogger.debug(LTag.PUMPCOMM, "Setting new basal rates for profile $profileNumber")
    }
    override fun handleMessage(data: ByteArray) {
        danaPump.bolusStartErrorCode = intFromBuff(data, 4, 6)
        danaPump.bolusStartErrorCodeDes = StringUtil.bytesToHexString(data).substring(8,12)
        Log.e("---------->","ZhiKaiPacketBasalSetProfileBasalRate ="+danaPump.bolusStartErrorCodeDes)
        if(danaPump.bolusStartErrorCodeDes.equals("55AA")){
            failed = false
        }else{
            failed = true
        }
    }
    fun getValuesHexString(): String {
        val sb = StringBuilder()
        for (i in profileBasalRate.indices) {
            val valueInt: Int = (profileBasalRate.get(i) * 40).toInt()
            val hexStr: String = StringUtil.intToHexString(valueInt)
            if (hexStr.length == 1) {
                sb.append("0" + hexStr + "00")
            } else if (hexStr.length == 2) {
                sb.append(hexStr + "00")
            } else if (hexStr.length == 3) {
                sb.append("0" + hexStr.substring(2, 3) + hexStr.substring(0, 2))
            } else if (hexStr.length == 4) {
                sb.append("0" + hexStr.substring(2, 4) + hexStr.substring(0, 2))
            }
        }
        return sb.toString()
    }
    override val friendlyName: String = "BASAL__SET_PROFILE_BASAL_RATE"
}
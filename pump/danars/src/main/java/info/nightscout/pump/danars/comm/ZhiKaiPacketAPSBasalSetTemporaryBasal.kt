package info.nightscout.pump.danars.comm

import app.aaps.core.interfaces.logging.LTag
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.danars.encryption.BleEncryption
import info.nightscout.pump.danars.util.StringUtil

class ZhiKaiPacketAPSBasalSetTemporaryBasal (
    injector: HasAndroidInjector,
    private var percent: Int
) : DanaRSPacket(injector) {

    var temporaryBasalRatio = 0
    var temporaryBasalDuration = 0
    var error = 0

    init {
        opCode = BleEncryption.ZHIKAI_PACKET_OPCODE_BASAL_APS_SET_TEMPORARY_BASAL
        aapsLogger.debug(LTag.PUMPCOMM, "New message: percent: $percent")

        if (percent < 0) percent = 0
        if (percent > 500) percent = 500
        temporaryBasalRatio = percent
        if (percent < 100) {
            temporaryBasalDuration = PARAM30MIN
            aapsLogger.debug(LTag.PUMPCOMM, "APS Temp basal start percent: $percent duration 30 min")
        } else {
            temporaryBasalDuration = PARAM15MIN
            aapsLogger.debug(LTag.PUMPCOMM, "APS Temp basal start percent: $percent duration 15 min")
        }
    }

    override fun handleMessage(data: ByteArray) {
        val result = StringUtil.bytesToHexString(data)

        if (!result.equals("AA0A00A155AA0000EC39")) {
            failed = true
            aapsLogger.debug(LTag.PUMPCOMM, "Set APS temp basal start result: $result FAILED!!!")
        } else {
            failed = false
            aapsLogger.debug(LTag.PUMPCOMM, "Set APS temp basal start result: $result")
        }
    }

    override val friendlyName: String = "BASAL_APS_SET_TEMPORARY_BASAL"

    companion object {
        const val PARAM30MIN = 30
        const val PARAM15MIN = 15
    }
}
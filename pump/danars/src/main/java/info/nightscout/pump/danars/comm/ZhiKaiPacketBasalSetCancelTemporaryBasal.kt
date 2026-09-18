package info.nightscout.pump.danars.comm

import app.aaps.core.interfaces.logging.LTag
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.danars.encryption.BleEncryption
import info.nightscout.pump.danars.util.StringUtil

class ZhiKaiPacketBasalSetCancelTemporaryBasal (
    injector: HasAndroidInjector
) : DanaRSPacket(injector) {

    init {
        opCode = BleEncryption.ZHIKAI_PACKET__OPCODE_BASAL__CANCEL_TEMPORARY_BASAL
        aapsLogger.debug(LTag.PUMPCOMM, "Canceling temp basal")
    }

    override fun handleMessage(data: ByteArray) {
        val result = StringUtil.bytesToHexString(data)
        @Suppress("LiftReturnOrAssignment")
        if (result.equals("AA0A00A155AA0000EC39")) {
            aapsLogger.debug(LTag.PUMPCOMM, "Result OK")
            failed = false
        } else {
            aapsLogger.error("Result Error: $result")
            failed = true
        }
    }

    override val friendlyName: String = "BASAL__CANCEL_TEMPORARY_BASAL"
}
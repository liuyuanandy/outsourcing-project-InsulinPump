package info.nightscout.androidaps.danars.encryption;

import android.content.Context;

import javax.inject.Inject;
import javax.inject.Singleton;

import info.nightscout.pump.danars.encryption.EncryptionType;

@Singleton
public class ZhiKaiBleType {
    private final Context context;

    @Inject ZhiKaiBleType(Context context) {
        this.context = context;
    }

    public static final int DANAR_PACKET__TYPE_ENCRYPTION_REQUEST = 0x01;

}

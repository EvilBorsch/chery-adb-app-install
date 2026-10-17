package android.content;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;

/** Compile-only signature of Android's hidden interface. Never packaged into the helper. */
public interface IIntentSender extends IInterface {
    void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
            IIntentReceiver finishedReceiver, String requiredPermission, Bundle options);

    abstract class Stub extends Binder implements IIntentSender {
        public IBinder asBinder() { return this; }
    }
}

/*
 * Copyright 2008, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.view;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * {@hide}
 *
 * Hand-written equivalent of the AIDL-generated stub for
 * `IRotationWatcher.aidl` (AOSP hidden API shim used by the CarLife SDK's
 * orientation monitor).
 *
 * DiPlay note (Phase 9 modernization): AGP 9 no longer compiles AIDL, so the
 * generated stub is committed as source. Transaction codes and wire shape
 * match `aidl` output exactly (oneway interface, FIRST_CALL_TRANSACTION).
 */
public interface IRotationWatcher extends IInterface {

    void onRotationChanged(int rotation) throws RemoteException;

    String DESCRIPTOR = "android.view.IRotationWatcher";

    int TRANSACTION_onRotationChanged = IBinder.FIRST_CALL_TRANSACTION;

    abstract class Stub extends Binder implements IRotationWatcher {

        public Stub() {
            this.attachInterface(this, DESCRIPTOR);
        }

        public static IRotationWatcher asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface local = obj.queryLocalInterface(DESCRIPTOR);
            if (local instanceof IRotationWatcher) {
                return (IRotationWatcher) local;
            }
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code >= INTERFACE_TRANSACTION && code <= LAST_CALL_TRANSACTION) {
                data.enforceInterface(DESCRIPTOR);
            }
            switch (code) {
                case TRANSACTION_onRotationChanged: {
                    int rotation = data.readInt();
                    this.onRotationChanged(rotation);
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static class Proxy implements IRotationWatcher {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public void onRotationChanged(int rotation) throws RemoteException {
                Parcel data = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(rotation);
                    mRemote.transact(TRANSACTION_onRotationChanged, data, null, IBinder.FLAG_ONEWAY);
                } finally {
                    data.recycle();
                }
            }
        }
    }
}

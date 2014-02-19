package org.ligi.passandroid;

import android.os.Handler;
import android.os.Looper;

import com.squareup.otto.Bus;

/**
 * Bus for sending events to main thread
 * @see <a href="https://github.com/square/otto/issues/38">Implemented after seen here</a>
 */
public class MainThreadBus extends Bus {
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Override public void post(final Object event) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            super.post(event);
        } else {
            mHandler.post(new Runnable() {
                @Override public void run() {
                    post(event);
                }
            });
        }
    }
}


package com.android.server.policy;

/** Just enough of the system's window policy for the hook to find: the method it hooks and the field it reads. */
public class PhoneWindowManager {
    public Object mFocusedWindow;
    public android.os.Handler mHandler = new android.os.Handler();
    public android.content.Context mContext = new android.content.Context();

    public final class SwipeCallbacks {
        public void onSwipeFromBottom() { if (mHandler == null) throw new IllegalStateException("no handler"); }
        public void onSwipeFromLeft() { if (mHandler == null) throw new IllegalStateException("no handler"); }
    }

    public void requestTransientBars(Object swipeTarget) {
    }
    public int updateSystemBarsLw(Object window,int oldFlags,int flags) { return flags; }
}

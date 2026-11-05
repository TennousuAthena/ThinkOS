package com.android.server.policy;

public class SystemGesturesPointerEventListener {
    public Object mCallbacks;
    public float[] mDownX = {1};
    public float[] mDownY = {600};
    public SystemGesturesPointerEventListener(Object callbacks) { mCallbacks = callbacks; }
}

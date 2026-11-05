package moe.kiwi.thinkos.overlayfixture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import org.json.JSONObject;

/** Separate-UID, short-lived overlay probe; no product service or persistent module. */
public final class OverlayReceiver extends BroadcastReceiver {
    private static View overlay;
    private static WindowManager manager;
    private static int down,up,moves;
    private static float brightness=-1f;
    private static final Handler handler=new Handler(Looper.getMainLooper());
    private static final Runnable expired=new Runnable() { public void run() { hide(); } };
    private static void hide() {
        handler.removeCallbacks(expired);
        if(overlay!=null) { manager.removeViewImmediate(overlay);overlay=null; }
    }
    @Override public void onReceive(Context context,Intent intent) {
        try {
            String command=intent.getStringExtra("command");
            if("show".equals(command)) {
                if(!Settings.canDrawOverlays(context))throw new SecurityException("Fixture appop not allowed");
                float value=intent.getFloatExtra("brightness",0.1f);
                if(Float.isNaN(value)||value<0f||value>1f)throw new IllegalArgumentException("Invalid brightness");
                hide();brightness=value;manager=(WindowManager)context.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
                TextView strip=new TextView(context);strip.setText("Overlay probe");strip.setTextSize(18);strip.setTextColor(Color.WHITE);strip.setBackgroundColor(Color.rgb(32,32,32));strip.setGravity(Gravity.CENTER);
                strip.setOnTouchListener(new View.OnTouchListener() { public boolean onTouch(View view,MotionEvent event) {
                    if(event.getActionMasked()==MotionEvent.ACTION_DOWN)down++;if(event.getActionMasked()==MotionEvent.ACTION_MOVE)moves++;if(event.getActionMasked()==MotionEvent.ACTION_UP)up++;return true;
                } });
                WindowManager.LayoutParams params=new WindowManager.LayoutParams(-1,(int)(24*context.getResources().getDisplayMetrics().density),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,android.graphics.PixelFormat.OPAQUE);
                params.gravity=Gravity.TOP|Gravity.LEFT;params.screenBrightness=value;params.setTitle("ThinkOS external overlay probe");
                manager.addView(strip,params);overlay=strip;handler.postDelayed(expired,120000);
            } else if("hide".equals(command))hide();
            else if(!"state".equals(command))throw new IllegalArgumentException("Unknown command");
            setResultCode(0);setResultData(new JSONObject().put("uid",android.os.Process.myUid()).put("overlay",overlay!=null)
                .put("down",down).put("up",up).put("moves",moves).put("brightness",brightness).put("canOverlay",Settings.canDrawOverlays(context)).toString());
        } catch(Exception error) { setResultCode(1);setResultData("{}"); }
    }
}

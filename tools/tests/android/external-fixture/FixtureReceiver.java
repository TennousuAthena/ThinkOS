package moe.kiwi.thinkos.externalfixture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

/** Only adb shell (DUMP) may control this temporary fixture. */
public final class FixtureReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        try {
            FixtureActivity activity=FixtureActivity.current;
            if(activity==null)throw new IllegalStateException("Fixture Activity is absent");
            String action=intent.getStringExtra("command");
            if("focus_gain".equals(action))activity.gainFocus();
            else if("focus_abandon".equals(action))activity.abandonFocus();
            else if("audio_start".equals(action))activity.startAudio();
            else if("audio_stop".equals(action))activity.stopAudio();
            else if("video_start".equals(action))activity.startVideo();
            else if("video_stop".equals(action))activity.stopVideo();
            else if("input_probe".equals(action))activity.probeInput(intent.getStringExtra("value"));
            else if("ignore_back".equals(action))FixtureActivity.ignoreBack=intent.getBooleanExtra("value",false);
            else if("orientation".equals(action))activity.setRequestedOrientation(intent.getIntExtra("value",1));
            else if("cleanup".equals(action))activity.cleanup();
            else if(!"state".equals(action))throw new IllegalArgumentException("Unknown command");
            setResultCode(0);setResultData(activity.state().toString());
        } catch(Exception error) { setResultCode(1);setResultData(new JSONObject().toString()); }
    }
}

package moe.kiwi.thinkos.externalfixture;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** Temporary separate-UID test app; never included in ThinkOS or its plugins. */
public final class FixtureActivity extends Activity {
    static FixtureActivity current;
    static int resumes, pauses, touches, backPresses, focusChanges, focusResult;
    static boolean resumed, ignoreBack;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable expired = new Runnable() { public void run() { cleanup(); finish(); } };
    private final AudioManager.OnAudioFocusChangeListener audioFocus = new AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int change) { focusChanges++; }
    };
    private volatile AudioTrack audio;
    private MediaPlayer video;
    private SurfaceView surface;
    private boolean videoWanted;
    private JSONObject inputProbe;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); current = this;
        FrameLayout frame = new FrameLayout(this); frame.setBackgroundColor(Color.rgb(64,64,64));
        surface = new SurfaceView(this); frame.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder holder) { if (videoWanted) startVideo(); }
            public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) { }
            public void surfaceDestroyed(SurfaceHolder holder) { stopVideo(); }
        });
        TextView title = new TextView(this); title.setText("外部应用测试");title.setTextSize(32);title.setTextColor(Color.WHITE);title.setGravity(Gravity.CENTER);
        frame.addView(title,new FrameLayout.LayoutParams(-1,120,Gravity.CENTER));
        frame.setOnTouchListener(new View.OnTouchListener() { public boolean onTouch(View view,MotionEvent event) { if(event.getActionMasked()==MotionEvent.ACTION_DOWN) touches++;return true; } });
        setContentView(frame); lease();
    }
    @Override protected void onResume() { super.onResume();resumed=true;resumes++;lease(); }
    @Override protected void onPause() { resumed=false;pauses++;super.onPause(); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent);lease(); }
    @Override public void onBackPressed() { backPresses++;if(!ignoreBack) super.onBackPressed(); }
    @Override protected void onDestroy() { cleanup();handler.removeCallbacksAndMessages(null);if(current==this)current=null;super.onDestroy(); }
    void lease() { handler.removeCallbacks(expired);handler.postDelayed(expired,180000); }
    int gainFocus() { lease();focusResult=((AudioManager)getSystemService(AUDIO_SERVICE)).requestAudioFocus(audioFocus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN);return focusResult; }
    void abandonFocus() { ((AudioManager)getSystemService(AUDIO_SERVICE)).abandonAudioFocus(audioFocus); }
    void startAudio() {
        stopAudio();lease();
        final AudioTrack track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(17640).build();
        track.setVolume(0f);audio=track;track.play();
        new Thread(new Runnable() { public void run() {
            byte[] silence=new byte[4096];
            try { while(audio==track) { if(track.write(silence,0,silence.length)<0)break; } }
            catch(RuntimeException ignored) { }
        } },"ExternalFixture-Silence").start();
    }
    void stopAudio() { AudioTrack previous=audio;audio=null;if(previous!=null) { try { previous.pause();previous.flush();previous.release(); } catch(RuntimeException ignored) { } } }
    void startVideo() {
        videoWanted=true;lease();if(!surface.getHolder().getSurface().isValid())return;
        stopVideo();videoWanted=true;
        try {
            final MediaPlayer player=new MediaPlayer();video=player;
            android.content.res.AssetFileDescriptor file=getAssets().openFd("silent.mp4");
            try { player.setDataSource(file.getFileDescriptor(),file.getStartOffset(),file.getLength()); } finally { file.close(); }
            player.setSurface(surface.getHolder().getSurface());player.setLooping(true);player.setVolume(0f,0f);
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() { public void onPrepared(MediaPlayer ready) { if(video==ready)ready.start(); } });player.prepareAsync();
        } catch(Exception error) { throw new IllegalStateException(error); }
    }
    void stopVideo() { videoWanted=false;MediaPlayer previous=video;video=null;if(previous!=null)previous.release(); }
    void cleanup() { ignoreBack=false;stopAudio();stopVideo();abandonFocus(); }
    void probeInput(String path) throws Exception {
        if(path==null||!path.matches("/data/local/tmp/thinkos-fixed-back-[0-9a-f]{32}\\.jar"))throw new IllegalArgumentException("Unknown probe file");
        JSONObject result=new JSONObject().put("uid",android.os.Process.myUid())
            .put("deviceOwner",((android.app.admin.DevicePolicyManager)getSystemService(DEVICE_POLICY_SERVICE)).isDeviceOwnerApp(getPackageName()))
            .put("hasInjectPermission",checkSelfPermission("android.permission.INJECT_EVENTS")==android.content.pm.PackageManager.PERMISSION_GRANTED);
        try {
            dalvik.system.DexClassLoader loader=new dalvik.system.DexClassLoader(path,getCodeCacheDir().getAbsolutePath(),null,getClassLoader());
            java.lang.reflect.Method emitter=loader.loadClass("moe.kiwi.thinkos.immersive.BackSwipeHook").getDeclaredMethod("injectBack");
            emitter.setAccessible(true);emitter.invoke(null);result.put("accepted",true).put("deniedSynchronously",false);
        } catch(Throwable error) {
            Throwable cause=error;
            while(cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause()!=null)cause=cause.getCause();
            result.put("accepted",false).put("deniedSynchronously",cause instanceof SecurityException).put("error",cause.getClass().getSimpleName());
        }
        inputProbe=result;
    }
    JSONObject state() throws Exception {
        boolean playing=false;int position=0,width=0;try { playing=video!=null&&video.isPlaying();if(playing) {position=video.getCurrentPosition();width=video.getVideoWidth();} } catch(IllegalStateException ignored) { }
        android.graphics.Point screen=new android.graphics.Point();getWindowManager().getDefaultDisplay().getRealSize(screen);
        return new JSONObject().put("uid",android.os.Process.myUid()).put("resumed",resumed).put("resumes",resumes).put("pauses",pauses).put("touches",touches)
            .put("backPresses",backPresses).put("ignoreBack",ignoreBack).put("focusResult",focusResult).put("focusChanges",focusChanges)
            .put("musicActive",((AudioManager)getSystemService(AUDIO_SERVICE)).isMusicActive()).put("silentAudio",audio!=null)
            .put("videoPlaying",playing).put("videoPosition",position).put("videoWidth",width).put("width",screen.x).put("height",screen.y).put("inputProbe",inputProbe);
    }
}

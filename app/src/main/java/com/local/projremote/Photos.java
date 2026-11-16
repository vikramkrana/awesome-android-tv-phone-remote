package com.local.projremote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Photos from a phone, shown full screen as a slideshow. The phone sends each picture already sized for the screen
 * (JPEG, base64). They are kept only in this app's cache, and deleted when the slideshow closes or a new set starts.
 */
public class Photos extends Activity {
    private static final long STEP_MS = 8_000L;
    /** The slideshow on screen, for the phone's commands. */
    private static volatile Photos showing;

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "photos");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Picture index of total from a phone. The first of a set clears the last set and opens the slideshow. */
    static boolean add(Context c, int index, int total, String base64) {
        if (index < 0 || total < 1 || total > 200 || index >= total) return false;
        byte[] jpeg;
        try {
            jpeg = Base64.decode(base64.trim(), Base64.DEFAULT);
        } catch (IllegalArgumentException bad) {
            return false;
        }
        // A picture at all (and not something else): its header must decode.
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, probe);
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return false;
        File d = dir(c);
        if (index == 0) clear(c);
        try (FileOutputStream out = new FileOutputStream(new File(d, String.format("%03d.jpg", index)))) {
            out.write(jpeg);
        } catch (IOException failed) {
            return false;
        }
        Photos s = showing;
        if (index == 0 || s == null) {
            c.startActivity(new Intent(c, Photos.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } else {
            s.runOnUiThread(s::refreshCount);
        }
        return true;
    }

    /** next, prev, pause, play, stop. */
    static boolean command(String cmd) {
        Photos s = showing;
        if (s == null) return false;
        s.runOnUiThread(() -> {
            switch (cmd) {
                case "next": s.step(1); break;
                case "prev": s.step(-1); break;
                case "pause": s.setPlaying(false); break;
                case "play": s.setPlaying(true); break;
                case "stop": s.finish(); break;
                default: break;
            }
        });
        return true;
    }

    static boolean isShowing() {
        return showing != null;
    }

    /** At start: a slideshow cut short by power-off left its pictures behind. */
    static void clearLeftovers(Context c) {
        if (showing == null) clear(c);
    }

    private static void clear(Context c) {
        File[] old = dir(c).listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    // ---- the slideshow -------------------------------------------------------------------------------------------

    private final Handler main = new Handler(Looper.getMainLooper());
    private ImageView front, back;
    private TextView caption;
    private int at;
    private boolean playing = true;
    private final Runnable advance = () -> step(1);
    private final Runnable hideCaption = () -> caption.animate().alpha(0f).setDuration(400).start();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        back = image();
        front = image();
        root.addView(back);
        root.addView(front);
        caption = new TextView(this);
        caption.setTextColor(0xCCFFFFFF);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        caption.setShadowLayer(8, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Math.round(28 * getResources().getDisplayMetrics().density);
        root.addView(caption, lp);
        setContentView(root);
        showing = this;
        at = 0;
        show(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // A new set from a phone: from its first picture.
        at = 0;
        show(true);
    }

    private ImageView image() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return v;
    }

    private File[] files() {
        File[] all = dir(this).listFiles();
        if (all == null) return new File[0];
        java.util.Arrays.sort(all);
        return all;
    }

    private void step(int by) {
        int n = files().length;
        if (n == 0) return;
        at = ((at + by) % n + n) % n;
        show(true);
    }

    private void show(boolean fade) {
        main.removeCallbacks(advance);
        File[] all = files();
        if (all.length == 0) return;
        if (at >= all.length) at = 0;
        Bitmap bmp = BitmapFactory.decodeFile(all[at].getPath());
        if (fade) {
            back.setImageDrawable(front.getDrawable());
            back.setAlpha(1f);
            front.setAlpha(0f);
            front.setImageBitmap(bmp);
            // The old picture goes once the new one covers it: a narrower photo would show it at the sides.
            front.animate().alpha(1f).setDuration(700).withEndAction(() -> back.setImageDrawable(null)).start();
        } else {
            front.setImageBitmap(bmp);
        }
        refreshCount();
        if (playing && all.length > 1) main.postDelayed(advance, STEP_MS);
    }

    private void refreshCount() {
        int n = files().length;
        caption.setText((at + 1) + " / " + n + (playing || n < 2 ? "" : "   ·   paused"));
        caption.setAlpha(1f);
        main.removeCallbacks(hideCaption);
        main.postDelayed(hideCaption, 2500);
        // The second picture just arrived: start moving.
        if (playing && n > 1) {
            main.removeCallbacks(advance);
            main.postDelayed(advance, STEP_MS);
        }
    }

    private void setPlaying(boolean on) {
        playing = on;
        main.removeCallbacks(advance);
        if (on) main.postDelayed(advance, STEP_MS);
        refreshCount();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: step(1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: step(-1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: setPlaying(!playing); return true;
            default: return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Left (Back, Home, another app): the slideshow is over and its pictures go.
        main.removeCallbacksAndMessages(null);
        if (showing == this) showing = null;
        clear(this);
        finish();
    }
}

package com.local.projremote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Remote control for apps made for touch screens (Netflix's phone app, which is the only Netflix most uncertified
 * projectors can run): their screens ignore the D-pad. While one of them is in front, the arrows (from the
 * projector's own remote or from the phone) move a highlight ring over the app's buttons and posters, scrolling a
 * row or the page when the next item is off screen, and OK taps the highlighted item. With nothing to highlight
 * (a video playing), OK taps the middle of the screen, which brings up the player's controls. Back, Home, volume
 * and media keys are left alone. Nothing is recorded or sent anywhere.
 */
final class TouchNav {
    private final AccessibilityService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> apps;
    private AccessibilityNodeInfo selected;
    private final Rect selectedRect = new Rect();
    private View ring;
    /** Keys passed on to the app at ACTION_DOWN, so their ACTION_UP goes the same way. */
    private final Set<Integer> passed = new HashSet<>();

    TouchNav(AccessibilityService service) {
        this.service = service;
        apps = new HashSet<>(Arrays.asList(service.getResources().getStringArray(R.array.touch_apps)));
    }

    /** The app in front is one of the touch-only apps. */
    boolean active() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        return root != null && root.getPackageName() != null && apps.contains(root.getPackageName().toString());
    }

    /** From the projector's remote (AccessibilityService.onKeyEvent): true when the key was used here. */
    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        int direction = direction(code);
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (direction == 0 && !ok) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) return !passed.remove(code);
        if (e.getRepeatCount() == 0) passed.remove(code);
        if (passed.contains(code) || !active()) {
            passed.add(code);
            return false;
        }
        boolean used = ok ? ok() : move(direction);
        if (!used) passed.add(code);
        return used;
    }

    private static int direction(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return View.FOCUS_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return View.FOCUS_RIGHT;
            default: return 0;
        }
    }

    /** One step that way. False when the screen has nothing to step to (the key then goes to the app). */
    boolean move(int direction) {
        List<AccessibilityNodeInfo> all = targets();
        if (all.isEmpty()) {
            hide();
            return false;
        }
        AccessibilityNodeInfo current = current(all);
        if (current == null) {
            select(first(all));
            return true;
        }
        Rect from = new Rect();
        current.getBoundsInScreen(from);
        AccessibilityNodeInfo next = nearest(all, from, direction);
        boolean vertical = direction == View.FOCUS_UP || direction == View.FOCUS_DOWN;
        // Up/down inside a scrolling page: the page's next item, not the fixed bar over it. Scroll first; the bars
        // are reached once the page can go no further.
        AccessibilityNodeInfo page = vertical ? scroller(current, false) : null;
        if (next != null && (page == null || inside(next, page))) {
            select(next);
            return true;
        }
        if (!scroll(current, direction)) {
            if (next != null) select(next);
            return true;
        }
        main.postDelayed(() -> {
            List<AccessibilityNodeInfo> now = targets();
            List<AccessibilityNodeInfo> within = new ArrayList<>();
            for (AccessibilityNodeInfo n : now) if (page == null || inside(n, page)) within.add(n);
            AccessibilityNodeInfo after = nearest(within, from, direction);
            if (after == null) after = closest(within, from);
            if (after != null) select(after);
            else hide();
        }, 400);
        return true;
    }

    /** The nearest scrolling ancestor: a wide row (horizontal) or a tall page. */
    private static AccessibilityNodeInfo scroller(AccessibilityNodeInfo from, boolean horizontal) {
        Rect r = new Rect();
        AccessibilityNodeInfo node = from == null ? null : from.getParent();
        for (int i = 0; node != null && i < 20; i++) {
            if (node.isScrollable()) {
                node.getBoundsInScreen(r);
                if (horizontal == (r.width() > r.height() * 2)) return node;
            }
            node = node.getParent();
        }
        return null;
    }

    private static boolean inside(AccessibilityNodeInfo node, AccessibilityNodeInfo ancestor) {
        AccessibilityNodeInfo p = node.getParent();
        for (int i = 0; p != null && i < 25; i++) {
            if (p.equals(ancestor)) return true;
            p = p.getParent();
        }
        return false;
    }

    /** Taps the highlighted item; with none, the middle of the screen. */
    boolean ok() {
        AccessibilityNodeInfo node = current(targets());
        if (node != null) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            hide();
            selected = null;
            if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(r.centerX(), r.centerY());
            return true;
        }
        DisplayMetrics m = service.getResources().getDisplayMetrics();
        tap(m.widthPixels / 2f, m.heightPixels / 2f);
        return true;
    }

    /** The app scrolled or changed screen: keep the ring on its item, or take it away. */
    void refresh() {
        if (selected == null || ring == null) return;
        if (!selected.refresh() || !selected.isVisibleToUser()) {
            hide();
            return;
        }
        selected.getBoundsInScreen(selectedRect);
        place(selectedRect);
    }

    /** Another app came to the front. */
    void reset() {
        selected = null;
        hide();
    }

    // ---- finding the items -----------------------------------------------------------------------------------------

    /** What can be tapped on screen: the innermost clickable elements, not ones covering (nearly) the whole screen. */
    private List<AccessibilityNodeInfo> targets() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return out;
        DisplayMetrics m = service.getResources().getDisplayMetrics();
        long screen = (long) m.widthPixels * m.heightPixels;
        collect(root, out, screen, 0);
        // Items of a scrolling page that lie under a fixed bar (tabs at the bottom, the header) are hidden there.
        List<Rect> bars = new ArrayList<>();
        List<AccessibilityNodeInfo> paged = new ArrayList<>();
        for (AccessibilityNodeInfo n : out) {
            if (scroller(n, false) == null && scroller(n, true) == null) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                bars.add(r);
            } else {
                paged.add(n);
            }
        }
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : paged) {
            n.getBoundsInScreen(r);
            for (Rect bar : bars) {
                Rect overlap = new Rect();
                if (overlap.setIntersect(r, bar) && overlap.height() * 2 > r.height()) {
                    out.remove(n);
                    break;
                }
            }
        }
        return out;
    }

    private static boolean collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, long screen, int depth) {
        if (node == null || depth > 40) return false;
        boolean inner = false;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null || !child.isVisibleToUser()) continue;
            if (collect(child, out, screen, depth + 1)) inner = true;
        }
        if (inner || !node.isClickable() || !node.isEnabled()) return inner;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.width() < 8 || r.height() < 8 || (long) r.width() * r.height() > screen * 6 / 10) return false;
        out.add(node);
        return true;
    }

    /** The highlighted item if it is still on screen (as one of these). */
    private AccessibilityNodeInfo current(List<AccessibilityNodeInfo> all) {
        if (selected == null) return null;
        for (AccessibilityNodeInfo n : all) if (n.equals(selected)) return n;
        // Same place, new node (the app redrew): the one now there.
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : all) {
            n.getBoundsInScreen(r);
            if (r.equals(selectedRect)) return n;
        }
        return null;
    }

    /** Where to start: the top-left item below the app's top bar, or else the first one. */
    private AccessibilityNodeInfo first(List<AccessibilityNodeInfo> all) {
        int h = service.getResources().getDisplayMetrics().heightPixels;
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MAX_VALUE;
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : all) {
            n.getBoundsInScreen(r);
            if (r.top < h / 8) continue;
            long score = (long) r.top * 4 + r.left;
            if (score < bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best != null ? best : all.get(0);
    }

    private static AccessibilityNodeInfo nearest(List<AccessibilityNodeInfo> all, Rect a, int direction) {
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MAX_VALUE;
        Rect b = new Rect();
        for (AccessibilityNodeInfo n : all) {
            n.getBoundsInScreen(b);
            if (b.equals(a)) continue;
            int major, minor;
            switch (direction) {
                case View.FOCUS_UP: major = a.top - b.bottom; minor = Math.abs(a.centerX() - b.centerX()); break;
                case View.FOCUS_DOWN: major = b.top - a.bottom; minor = Math.abs(a.centerX() - b.centerX()); break;
                case View.FOCUS_LEFT: major = a.left - b.right; minor = Math.abs(a.centerY() - b.centerY()); break;
                default: major = b.left - a.right; minor = Math.abs(a.centerY() - b.centerY()); break;
            }
            if (major < -8) continue;
            long score = 13L * Math.max(0, major) * Math.max(0, major) + (long) minor * minor;
            if (score < bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    private static AccessibilityNodeInfo closest(List<AccessibilityNodeInfo> all, Rect a) {
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MAX_VALUE;
        Rect b = new Rect();
        for (AccessibilityNodeInfo n : all) {
            n.getBoundsInScreen(b);
            long dx = a.centerX() - b.centerX(), dy = a.centerY() - b.centerY();
            if (dx * dx + dy * dy < bestScore) {
                bestScore = dx * dx + dy * dy;
                best = n;
            }
        }
        return best;
    }

    private static boolean scroll(AccessibilityNodeInfo from, int direction) {
        AccessibilityNodeInfo.AccessibilityAction specific;
        int general;
        boolean horizontal = direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT;
        switch (direction) {
            case View.FOCUS_UP: specific = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP;
                general = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD; break;
            case View.FOCUS_DOWN: specific = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN;
                general = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD; break;
            case View.FOCUS_LEFT: specific = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT;
                general = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD; break;
            default: specific = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT;
                general = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD; break;
        }
        // The nearest list that scrolls that way. A list that only says forward/backward is used when its shape
        // fits: a wide row for left/right, a tall page for up/down.
        Rect r = new Rect();
        AccessibilityNodeInfo node = from;
        for (int i = 0; node != null && i < 15; i++) {
            if (node.isScrollable()) {
                if (node.getActionList().contains(specific)) return node.performAction(specific.getId());
                node.getBoundsInScreen(r);
                boolean wide = r.width() > r.height() * 2;
                if (horizontal == wide && node.performAction(general)) return true;
            }
            node = node.getParent();
        }
        return false;
    }

    // ---- the ring and the tap --------------------------------------------------------------------------------------

    private void select(AccessibilityNodeInfo node) {
        selected = node;
        node.getBoundsInScreen(selectedRect);
        // Partly off screen: bring it in first.
        DisplayMetrics m = service.getResources().getDisplayMetrics();
        if (selectedRect.left < 0 || selectedRect.top < 0 || selectedRect.right > m.widthPixels
                || selectedRect.bottom > m.heightPixels) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
            main.postDelayed(this::refresh, 250);
        }
        place(selectedRect);
    }

    private void place(Rect r) {
        float d = service.getResources().getDisplayMetrics().density;
        int pad = Math.round(4 * d);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(r.width() + pad * 2, r.height() + pad * 2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = r.left - pad;
        lp.y = r.top - pad;
        WindowManager wm = (WindowManager) service.getSystemService(AccessibilityService.WINDOW_SERVICE);
        try {
            if (ring == null) {
                ring = new View(service);
                GradientDrawable g = new GradientDrawable();
                g.setColor(0x14FFFFFF);
                g.setCornerRadius(10 * d);
                g.setStroke(Math.round(3 * d), 0xFFFFFFFF);
                ring.setBackground(g);
                wm.addView(ring, lp);
            } else {
                wm.updateViewLayout(ring, lp);
            }
        } catch (RuntimeException failed) {
            android.util.Log.w("TouchNav", "ring: " + failed);
            ring = null;
        }
    }

    private void hide() {
        if (ring == null) return;
        try {
            ((WindowManager) service.getSystemService(AccessibilityService.WINDOW_SERVICE)).removeView(ring);
        } catch (RuntimeException gone) { }
        ring = null;
    }

    private void tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build();
        service.dispatchGesture(g, null, null);
    }
}

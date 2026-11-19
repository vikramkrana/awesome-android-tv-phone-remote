package com.local.projremote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
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
 * projector's own remote or from the phone) move a highlight ring over the app's buttons and posters:
 * <ul>
 * <li>up/down go row by row, keeping to the same column, and bring a row that is partly hidden into view;</li>
 * <li>left/right go along the row, scrolling it at its edge;</li>
 * <li>the app's fixed bars (header, tabs) are reached at the ends of the page;</li>
 * <li>OK taps the highlighted item. With nothing on screen to highlight (a video playing with its controls
 * hidden), OK brings up the controls, left/right skip 10 seconds.</li>
 * </ul>
 * Back, Home, volume and media keys are left alone. Nothing is recorded or sent anywhere.
 */
final class TouchNav {
    private final RemoteService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> apps;
    private AccessibilityNodeInfo selected;
    private final Rect selectedRect = new Rect();
    /** The column up/down keep to: where left/right last left the ring (-1: the item's own middle). */
    private int column = -1;
    private View ring;
    /** Keys passed on to the app at ACTION_DOWN, so their ACTION_UP goes the same way. */
    private final Set<Integer> passed = new HashSet<>();
    /** The part of the screen between the app's fixed bars (from the last look at the screen). */
    private int clearTop, clearBottom = Integer.MAX_VALUE;
    /** A scroll is settling: further presses wait for it rather than act on a moving screen. */
    private boolean settling;
    private long lastScrollAt;
    /** The app's fixed bars on screen (header, tabs): items outside any scrolling list, in the top or bottom strip. */
    private final Set<AccessibilityNodeInfo> bars = new HashSet<>();

    TouchNav(RemoteService service) {
        this.service = service;
        apps = new HashSet<>(Arrays.asList(service.getResources().getStringArray(R.array.touch_apps)));
    }

    /** The app in front is one of the touch-only apps. */
    boolean active() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        return root != null && root.getPackageName() != null && apps.contains(root.getPackageName().toString())
                && !keyboardShowing();
    }

    /** The on-screen keyboard is up (typing a search): the keys are its own then. */
    private boolean keyboardShowing() {
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : service.getWindows()) {
                if (w.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    hide();
                    return true;
                }
            }
        } catch (RuntimeException ignored) { }
        return false;
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

    private static boolean vertical(int direction) {
        return direction == View.FOCUS_UP || direction == View.FOCUS_DOWN;
    }

    // ---- moving -----------------------------------------------------------------------------------------------------

    /** One step that way. False when the screen has nothing to step to (the key then goes to the app). */
    boolean move(int direction) {
        if (settling) return true;
        List<AccessibilityNodeInfo> all = targets();
        if (all.isEmpty()) {
            hide();
            // The video's controls faded while the user was moving among them: back up, the ring where it was.
            if (controlsBack()) return true;
            // A video with its controls hidden: left/right skip, up/down bring the controls up.
            if (direction == View.FOCUS_LEFT) return NowPlaying.command(service, "back");
            if (direction == View.FOCUS_RIGHT) return NowPlaying.command(service, "fwd");
            return showControls();
        }
        AccessibilityNodeInfo current = current(all);
        if (current == null) {
            select(back(all));
            return true;
        }
        Rect from = rect(current);
        boolean vertical = vertical(direction);
        boolean onBar = bars.contains(current);
        List<AccessibilityNodeInfo> page = new ArrayList<>(), barItems = new ArrayList<>();
        for (AccessibilityNodeInfo n : all) (bars.contains(n) ? barItems : page).add(n);
        int anchor = vertical && column >= 0 ? column : from.centerX();

        AccessibilityNodeInfo next;
        if (vertical) {
            next = null;
            if (onBar) {
                List<AccessibilityNodeInfo> clear = new ArrayList<>();
                for (AccessibilityNodeInfo n : page) if (isClear(rect(n))) clear.add(n);
                next = nearest(clear, from, direction, anchor);
            }
            if (next == null) next = nearest(page, from, direction, anchor);
            if (next == null && onBar) next = nearest(barItems, from, direction, anchor);
        } else {
            next = nearest(sameRow(onBar ? barItems : page, from), from, direction, anchor);
        }
        if (next != null) {
            go(next, direction, from);
            return true;
        }
        if (onBar) return true;
        // Nothing more that way on screen: scroll the page (up/down) or the row (left/right), then land on what
        // came into view. At the very end of the page, up/down go on to the bar there.
        Set<String> before = rects(all);
        if (scrollList(from, direction)) {
            settle(() -> land(current, from, direction, anchor, before));
        } else if (vertical) {
            AccessibilityNodeInfo bar = nearest(barItems, from, direction, anchor);
            if (bar != null) go(bar, direction, from);
        }
        return true;
    }

    /** Puts the ring on this item; one partly hidden (under a bar, cut off at the edge) is brought into view first. */
    private void go(AccessibilityNodeInfo node, int direction, Rect like) {
        if (!vertical(direction)) column = rect(node).centerX();
        select(node);
        if (bars.contains(node) || !hidden(rect(node), like)) return;
        Set<String> before = rects(targets());
        if (!vertical(direction)) {
            // Cut off at the side: slide the row (bringing it on screen would also move the page under the header).
            if (!clipped(rect(node), like)) return;
            slide(node, String.valueOf(node.getContentDescription()), like, direction, before, -1, 0);
            return;
        }
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        settle(() -> {
            targets();
            if (node.refresh() && !hidden(rect(node), like)) {
                select(node);
                return;
            }
            // Still hidden (the app does not move for it): scroll the list instead.
            Rect r = node.refresh() ? rect(node) : like;
            if (scrollList(r, direction)) settle(() -> {
                targets();
                if (node.refresh() && !hidden(rect(node), like)) {
                    select(node);
                } else if (node.isVisibleToUser() && isClear(new Rect(0, rect(node).centerY(), 1, rect(node).centerY() + 1))) {
                    // The scroll went past it (whole pages at a time), and it is still partly on screen: back to it.
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                    settle(() -> {
                        targets();
                        if (node.refresh() && node.isVisibleToUser()) select(node);
                        else land(node, r, direction, column >= 0 && vertical(direction) ? column : r.centerX(), before);
                    });
                } else {
                    land(node, r, direction, column >= 0 && vertical(direction) ? column : r.centerX(), before);
                }
            });
        });
    }

    /** Not wholly in view: under a bar, off the sides, or cut short at a side (narrower than its neighbour). */
    private boolean hidden(Rect r, Rect like) {
        if (!isClear(r)) return true;
        // Cut short at the top or bottom of the list (shorter than its neighbour, against a bar or the edge).
        boolean atEnd = r.top <= clearTop + 24 || r.bottom >= clearBottom - 4;
        if (atEnd && like != null && like.width() == r.width() && r.height() * 10 < like.height() * 9) return true;
        int w = service.getResources().getDisplayMetrics().widthPixels;
        boolean atSide = r.left <= 2 || r.right >= w - 2;
        return atSide && like != null && r.width() * 10 < like.width() * 9;
    }

    /**
     * Slides the row until this item is wholly in view. Some apps move a row only a little per scroll (Netflix: about
     * 80 px), so after the first step the rest are sent together, by how far the first one went.
     */
    private void slide(AccessibilityNodeInfo node, String desc, Rect like, int direction, Set<String> before, int lastX, int tries) {
        int startX = rect(node).left;
        if (!scrollList(like, direction)) {
            land(null, like, direction, like.centerX(), before);
            return;
        }
        settle(() -> {
            boolean same = node.refresh() && node.isVisibleToUser() && desc.equals(String.valueOf(node.getContentDescription()));
            if (!same) {
                land(null, like, direction, like.centerX(), before);
                return;
            }
            Rect r = rect(node);
            if (!clipped(r, like)) {
                select(node);
                return;
            }
            int step = Math.abs(startX - r.left);
            int w = service.getResources().getDisplayMetrics().widthPixels;
            int left = direction == View.FOCUS_RIGHT ? r.left + like.width() - (w - 24) : 24 - r.left;
            if (step < 8 || tries >= 3) {
                land(null, like, direction, like.centerX(), before);
                return;
            }
            int more = Math.min(12, (left + step - 1) / step) - 1;
            for (int i = 0; i < more; i++) scrollList(like, direction);
            slide(node, desc, like, direction, before, r.left, tries + 1);
        });
    }

    /** Cut short at a side of the screen (narrower than its neighbour), or off it. */
    private boolean clipped(Rect r, Rect like) {
        int w = service.getResources().getDisplayMetrics().widthPixels;
        if (r.left < -4 || r.right > w + 4) return true;
        boolean atSide = r.left <= 2 || r.right >= w - 2;
        return atSide && like != null && r.width() * 10 < like.width() * 9;
    }

    /** Where each item is and what it shows: in an even list a scroll brings other items to the same places. */
    private static Set<String> rects(List<AccessibilityNodeInfo> all) {
        Set<String> out = new HashSet<>();
        for (AccessibilityNodeInfo n : all) out.add(key(n));
        return out;
    }

    private static String key(AccessibilityNodeInfo n) {
        return rect(n).toShortString() + "|" + n.getContentDescription() + "|" + n.getText();
    }

    /** After a scroll: the item that came into view next to where the ring was, in its column or row. */
    private void land(AccessibilityNodeInfo old, Rect from, int direction, int anchor, Set<String> before) {
        List<AccessibilityNodeInfo> inView = new ArrayList<>(), moved = new ArrayList<>();
        List<AccessibilityNodeInfo> all = targets();
        // The old item still on screen (it moved with the list): the next one on from where it is now.
        CharSequence was = old == null ? null : old.getContentDescription();
        String label = old == null ? null : old.getContentDescription() + "|" + old.getText();
        boolean labelled = old != null && (old.getContentDescription() != null || old.getText() != null);
        Rect found = null;
        if (old != null && old.refresh() && old.isVisibleToUser() && !bars.contains(old)
                && java.util.Objects.equals(label, old.getContentDescription() + "|" + old.getText())) {
            found = rect(old);
        } else if (labelled) {
            // The app rebuilt the row: the item with the same words.
            for (AccessibilityNodeInfo n : all) {
                if (!bars.contains(n) && label.equals(n.getContentDescription() + "|" + n.getText())) {
                    found = rect(n);
                    break;
                }
            }
        }
        if (found != null) {
            Rect now = found;
            List<AccessibilityNodeInfo> clear = new ArrayList<>();
            for (AccessibilityNodeInfo n : all) if (!bars.contains(n) && !hidden(rect(n), from)) clear.add(n);
            List<AccessibilityNodeInfo> pool = vertical(direction) ? clear : sameRow(clear, now);
            AccessibilityNodeInfo next = nearest(pool, now, direction, vertical(direction) ? anchor : now.centerX());
            if (next != null) {
                go(next, direction, from);
                return;
            }
        }
        for (AccessibilityNodeInfo n : all) {
            if (bars.contains(n)) continue;
            if (vertical(direction) ? hidden(rect(n), from) : clipped(rect(n), from)) continue;
            inView.add(n);
            // Items that did not move with the scroll float over the list (the app's header chips): not landed on.
            if (!before.contains(key(n))) moved.add(n);
        }
        if (!moved.isEmpty()) inView = moved;
        if (inView.isEmpty()) return;
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MAX_VALUE;
        for (AccessibilityNodeInfo n : inView) {
            Rect b = rect(n);
            long score;
            switch (direction) {
                // The first row now in view that way, nearest the column.
                case View.FOCUS_DOWN: score = (long) b.top * 4 + Math.abs(b.centerX() - anchor)
                        + (b.top < from.top ? 100_000L : 0); break;
                case View.FOCUS_UP: score = (long) -b.bottom * 4 + Math.abs(b.centerX() - anchor)
                        + (b.bottom > from.bottom ? 100_000L : 0); break;
                // The row's first new item at that end.
                case View.FOCUS_RIGHT: score = Math.abs(b.centerY() - from.centerY()) * 8L + b.left; break;
                default: score = Math.abs(b.centerY() - from.centerY()) * 8L - b.right; break;
            }
            if (score < bestScore) {
                bestScore = score;
                best = n;
            }
        }
        if (best != null) go(best, direction, from);
    }

    /** Runs this once the screen stops moving (the app's scroll animation ends), at most about a second later. */
    private void settle(Runnable then) {
        settling = true;
        long start = android.os.SystemClock.uptimeMillis();
        lastScrollAt = start;
        Runnable check = new Runnable() {
            Set<String> last;
            boolean moved;
            @Override public void run() {
                long now = android.os.SystemClock.uptimeMillis();
                // Still moving: the items are not where they were a moment ago (some apps report no scroll events
                // while their list glides on). Not moved yet: the app may start late.
                Set<String> at = rects(targets());
                if (last != null && !at.equals(last)) moved = true;
                boolean still = at.equals(last) && now - lastScrollAt >= 120 && (moved || now - start >= 450);
                last = at;
                if (!still && now - start < 1500) {
                    main.postDelayed(this, 90);
                    return;
                }
                settling = false;
                then.run();
            }
        };
        main.postDelayed(check, 120);
    }

    /** The app's screen scrolled (an accessibility event). */
    void onScrolled() {
        lastScrollAt = android.os.SystemClock.uptimeMillis();
    }

    /**
     * Scrolls the list on screen that holds this spot: for left/right the smallest wide list around it (its row),
     * for up/down the largest (the page). Found by position, as some apps do not report the item's parents.
     */
    private boolean scrollList(Rect at, int direction) {
        AccessibilityNodeInfo root = root();
        if (root == null) return false;
        boolean vertical = vertical(direction);
        List<AccessibilityNodeInfo> lists = new ArrayList<>();
        scrollables(root, lists, 0);
        AccessibilityNodeInfo best = null;
        long bestArea = vertical ? 0 : Long.MAX_VALUE;
        for (AccessibilityNodeInfo n : lists) {
            Rect r = rect(n);
            if (!r.contains(at.centerX(), Math.max(r.top, Math.min(r.bottom - 1, at.centerY())))) continue;
            if (!r.contains(at.centerX(), at.centerY()) && !vertical) continue;
            if (vertical == sideways(n)) continue;
            long area = (long) r.width() * r.height();
            if (vertical ? area > bestArea : area < bestArea) {
                bestArea = area;
                best = n;
            }
        }
        if (best == null) return false;
        AccessibilityNodeInfo.AccessibilityAction specific;
        int general;
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
        hide();
        if (best.getActionList().contains(specific) && best.performAction(specific.getId())) return true;
        return best.performAction(general);
    }

    private static void scrollables(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 40) return;
        if (node.isScrollable() && node.isVisibleToUser()) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) scrollables(node.getChild(i), out, depth + 1);
    }

    // ---- OK -----------------------------------------------------------------------------------------------------------

    /** Taps the highlighted item. Nothing highlighted: the ring appears; nothing to highlight: the controls. */
    boolean ok() {
        if (settling) return true;
        List<AccessibilityNodeInfo> all = targets();
        AccessibilityNodeInfo node = current(all);
        if (node != null) {
            Rect r = rect(node);
            opened.push(new Object[] { new Rect(r), node.getViewIdResourceName(), String.valueOf(node.getText()) });
            while (opened.size() > 4) opened.removeLast();
            String screen = String.valueOf(root() == null ? null : root().getPackageName());
            hide();
            if (!click(node)) tap(r.centerX(), r.centerY());
            // Some cards answer a click only by showing their own button (Netflix: an info button on the poster), where
            // a finger tap would open them. That button appearing on the card: press it too.
            main.postDelayed(() -> {
                AccessibilityNodeInfo now = root();
                if (now == null || !screen.equals(String.valueOf(now.getPackageName()))) return;
                // The card itself still where it was (a new page did not open over it).
                if (!node.refresh() || !node.isVisibleToUser() || !rect(node).equals(r)) return;
                AccessibilityNodeInfo button = clickableInside(node, 0);
                if (button != null) {
                    button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return;
                }
                // Same screen, nothing opened: the ring comes back where it was.
                if (selected != null && selected.refresh() && selected.isVisibleToUser()) select(selected);
            }, 450);
            return true;
        }
        if (!all.isEmpty()) {
            select(back(all));
            return true;
        }
        hide();
        if (controlsBack()) return true;
        boolean shown = showControls() || service.key("playpause");
        // A video's controls just came up: the ring on its play/pause button, so the next OK pauses.
        main.postDelayed(() -> {
            List<AccessibilityNodeInfo> now = targets();
            if (!now.isEmpty() && selected == null) select(back(now));
        }, 450);
        return shown;
    }

    private static AccessibilityNodeInfo clickableInside(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 10) return null;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null || !c.isVisibleToUser()) continue;
            if (c.isClickable() && c.isEnabled()) return c;
            AccessibilityNodeInfo deeper = clickableInside(c, depth + 1);
            if (deeper != null) return deeper;
        }
        return null;
    }

    /** A video playing full screen: a tap on it brings up its controls. */
    private boolean showControls() {
        AccessibilityNodeInfo root = root();
        AccessibilityNodeInfo surface = root == null ? null : largestClickable(root, null, 0);
        if (surface != null && surface.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        DisplayMetrics m = service.getResources().getDisplayMetrics();
        tap(m.widthPixels / 2f, m.heightPixels / 2f);
        return true;
    }

    private static AccessibilityNodeInfo largestClickable(AccessibilityNodeInfo node, AccessibilityNodeInfo best, int depth) {
        if (node == null || depth > 40) return best;
        if (node.isClickable() && node.isVisibleToUser()) {
            if (best == null || area(rect(node)) > area(rect(best))) best = node;
        }
        for (int i = 0; i < node.getChildCount(); i++) best = largestClickable(node.getChild(i), best, depth + 1);
        return best;
    }

    private static boolean click(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo n = node; n != null; n = n.getParent()) {
            if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        return false;
    }

    /** The app scrolled or changed screen: keep the ring on its item, or take it away. */
    void refresh() {
        if (selected == null || ring == null || settling) return;
        if (!selected.refresh() || !selected.isVisibleToUser()) {
            hide();
            return;
        }
        selected.getBoundsInScreen(selectedRect);
        place(selectedRect);
    }

    /** Another app or screen came to the front. */
    void reset() {
        selected = null;
        column = -1;
        hide();
    }

    // ---- finding the items ----------------------------------------------------------------------------------------

    /** The screen's elements, fetched in one go where Android allows it (Android 13+): far fewer round trips. */
    private AccessibilityNodeInfo root() {
        // Android keeps a copy of the screen and updates it from the app's events; Netflix reuses list rows for other
        // titles without telling, so the copy goes stale (wrong titles in wrong places). A fresh read each time.
        if (Build.VERSION.SDK_INT >= 33) service.clearCache();
        // A sheet or dialog over the page (Netflix: profiles, seasons, audio and subtitles) is a window of its own on
        // top: that one, not the page under it.
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : service.getWindows()) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = Build.VERSION.SDK_INT >= 33
                        ? w.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID) : w.getRoot();
                if (r == null) continue;
                if (r.getPackageName() != null && apps.contains(r.getPackageName().toString())) return r;
                break;
            }
        } catch (RuntimeException ignored) { }
        if (Build.VERSION.SDK_INT >= 33) {
            return service.getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID);
        }
        return service.getRootInActiveWindow();
    }

    /**
     * What can be tapped on screen: the innermost clickable elements, not ones covering most of the screen, and
     * not ones mostly hidden under the app's fixed bars (the header, the tabs).
     */
    private List<AccessibilityNodeInfo> targets() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        AccessibilityNodeInfo root = root();
        if (root == null) return out;
        DisplayMetrics m = service.getResources().getDisplayMetrics();
        coverFrom = -1;
        coverTo = -1;
        seq = 0;
        order.clear();
        lists.clear();
        listOrder.clear();
        collect(root, out, (long) m.widthPixels * m.heightPixels, 0);
        // Under a list drawn over it (a header picture's button once the page scrolled up over the picture): out of
        // reach, though the app still reports it on screen.
        for (java.util.Iterator<AccessibilityNodeInfo> it = out.iterator(); it.hasNext(); ) {
            AccessibilityNodeInfo n = it.next();
            Integer at = order.get(n);
            Rect r = rect(n);
            for (int i = 0; at != null && i < lists.size(); i++) {
                if (listOrder.get(i) > at && lists.get(i).contains(r.centerX(), r.centerY())) {
                    it.remove();
                    break;
                }
            }
        }
        // A sheet over the page: only what is on it can be reached (the page, its bars are under it).
        boolean sheet = coverFrom >= 0 && coverTo > coverFrom;
        onSheet = sheet;
        if (sheet) {
            out.subList(coverTo, out.size()).clear();
            out.subList(0, coverFrom).clear();
        }
        int h = m.heightPixels;
        clearTop = 0;
        clearBottom = h;
        bars.clear();
        AccessibilityNodeInfo pageList = pageList(root);
        for (AccessibilityNodeInfo n : sheet ? new ArrayList<AccessibilityNodeInfo>() : out) {
            Rect r = rect(n);
            boolean fixed = pageList != null ? !within(n, pageList) && (r.bottom <= h / 3 || r.top >= h * 2 / 3)
                    : inStrip(r) && !inList(n);
            if (!fixed) continue;
            bars.add(n);
            if (r.centerY() < h / 2) clearTop = Math.max(clearTop, r.bottom);
            else clearBottom = Math.min(clearBottom, r.top);
        }
        return out;
    }

    /** Where in the list the last full-screen backdrop (a sheet's dimmed surround, tapped to close it) began. */
    private int coverFrom, coverTo;
    /** A sheet or list is open over the page. */
    private boolean onSheet;
    /** Drawing order: each target's place, and each list's place and bounds. */
    private int seq;
    private final java.util.Map<AccessibilityNodeInfo, Integer> order = new java.util.HashMap<>();
    private final List<Rect> lists = new ArrayList<>();
    private final List<Integer> listOrder = new ArrayList<>();

    private boolean collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, long screen, int depth) {
        if (node == null || depth > 40) return false;
        int at = seq++;
        if (node.isScrollable()) {
            lists.add(rect(node));
            listOrder.add(at);
        }
        boolean cover = node.isClickable() && !node.isScrollable() && area(rect(node)) * 100 >= screen * 95
                && !inList(node);
        if (cover) coverFrom = out.size();
        boolean inner = false;
        int start = out.size();
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null || !child.isVisibleToUser()) continue;
            if (collect(child, out, screen, depth + 1)) inner = true;
        }
        if (cover) coverTo = out.size();
        Rect r = rect(node);
        if (inner && node.isClickable() && node.isEnabled() && area(r) <= screen * 6 / 10) {
            // Only small buttons on it (a poster's info button): the card itself is what the ring goes to.
            boolean small = true;
            for (int i = start; i < out.size(); i++) if (area(rect(out.get(i))) * 4 > area(r)) small = false;
            if (small) {
                out.subList(start, out.size()).clear();
                inner = false;
            }
        }
        if (inner || !node.isClickable() || !node.isEnabled()) return inner;
        if (r.width() < 8 || r.height() < 8 || area(r) > screen * 6 / 10) return false;
        out.add(node);
        order.put(node, at);
        return true;
    }

    /** Wholly in the top or bottom strip of the screen, where apps keep their fixed bars. */
    private boolean inStrip(Rect r) {
        int h = service.getResources().getDisplayMetrics().heightPixels;
        return r.bottom <= h * 12 / 100 || r.top >= h * 86 / 100;
    }

    /**
     * A list that scrolls sideways (a row of posters), not up and down. Told by the scrolls it offers, then by how
     * its items are laid out; by its shape only when neither says (a full-width list on a wide screen is not a row).
     */
    private static boolean sideways(AccessibilityNodeInfo n) {
        List<AccessibilityNodeInfo.AccessibilityAction> acts = n.getActionList();
        boolean lr = acts.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT)
                || acts.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT);
        boolean ud = acts.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP)
                || acts.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN);
        if (lr != ud) return lr;
        AccessibilityNodeInfo.CollectionInfo ci = n.getCollectionInfo();
        if (ci != null) {
            if (ci.getRowCount() == 1 && ci.getColumnCount() > 1) return true;
            if (ci.getColumnCount() == 1 && ci.getRowCount() > 1) return false;
        }
        // Where its visible items sit: in one row (a row of posters), or in rows one above another (a list, a grid).
        Set<Integer> rows = new HashSet<>(), cols = new HashSet<>();
        for (int i = 0; i < n.getChildCount() && i < 40; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null || !c.isVisibleToUser()) continue;
            Rect b = rect(c);
            rows.add(b.top / 24);
            cols.add(b.left / 24);
        }
        if (rows.size() >= 2) return false;
        if (cols.size() >= 2) return true;
        Rect r = rect(n);
        return r.width() > r.height() * 2;
    }

    /** The page's own up-and-down list: the largest scrolling element that is not a wide row. */
    private static AccessibilityNodeInfo pageList(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> lists = new ArrayList<>();
        scrollables(root, lists, 0);
        AccessibilityNodeInfo best = null;
        for (AccessibilityNodeInfo n : lists) {
            Rect r = rect(n);
            if (sideways(n)) continue;
            if (best == null || area(r) > area(rect(best))) best = n;
        }
        return best;
    }

    private static boolean within(AccessibilityNodeInfo n, AccessibilityNodeInfo list) {
        AccessibilityNodeInfo p = n.getParent();
        for (int i = 0; p != null && i < 40; i++) {
            if (p.equals(list)) return true;
            p = p.getParent();
        }
        return false;
    }

    /** Inside a scrolling list (so it moves, and is not part of a fixed bar). */
    private static boolean inList(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n.getParent();
        for (int i = 0; p != null && i < 30; i++) {
            if (p.isScrollable()) return true;
            p = p.getParent();
        }
        return false;
    }

    /** Wholly in view: between the bars and within the screen's sides. */
    private boolean isClear(Rect r) {
        int w = service.getResources().getDisplayMetrics().widthPixels;
        return r.top >= clearTop - 4 && r.bottom <= clearBottom + 4 && r.left >= -4 && r.right <= w + 4;
    }

    /** The highlighted item if it is still on screen (as one of these). */
    private AccessibilityNodeInfo current(List<AccessibilityNodeInfo> all) {
        if (selected == null) return null;
        for (AccessibilityNodeInfo n : all) if (n.equals(selected)) return n;
        // Same place, new node (the app redrew): the one now there.
        for (AccessibilityNodeInfo n : all) if (rect(n).equals(selectedRect)) return n;
        return null;
    }

    /** The items last pressed with OK, newest first (one may have opened a list, like a season picker, now closed). */
    private final java.util.ArrayDeque<Object[]> opened = new java.util.ArrayDeque<>();

    /**
     * Where to start again: the newest item OK was pressed on that is still there (the season button once its list
     * closed); else the item the app marks as chosen (the season playing, in that list); else the item nearest the
     * newest one pressed (a tab that stopped being a button once chosen); else the first one.
     */
    private AccessibilityNodeInfo back(List<AccessibilityNodeInfo> all) {
        Object[] newest = opened.peek();
        for (Object[] o : opened) {
            Rect was = (Rect) o[0];
            String id = (String) o[1];
            for (AccessibilityNodeInfo n : all) {
                Rect b = rect(n);
                boolean same = b.equals(was) && (id == null || id.equals(n.getViewIdResourceName()));
                // Same control, a little wider or narrower now (its label changed: "Season 2").
                boolean relabelled = id != null && id.equals(n.getViewIdResourceName()) && b.top == was.top && b.left == was.left;
                if (same || relabelled) {
                    while (opened.peek() != o) opened.pop();
                    opened.pop();
                    return n;
                }
            }
        }
        // A video's controls: its play/pause button first.
        for (AccessibilityNodeInfo n : all) {
            String id = n.getViewIdResourceName();
            if (id != null && (id.contains("pauseButton") || id.contains("playButton"))) {
                opened.clear();
                return n;
            }
        }
        // The option now chosen (the season playing, the subtitle language on), when a button just opened its list.
        if (newest != null) {
            for (AccessibilityNodeInfo n : all) if (!bars.contains(n) && (n.isSelected() || n.isChecked())) return n;
        }
        if (onSheet) {
            // A list a button opened: the entry the button shows (the season button reads "Season 2").
            if (newest != null && !"null".equals(newest[2])) {
                for (AccessibilityNodeInfo n : all) if (newest[2].equals(String.valueOf(n.getText()))) return n;
            }
        }
        opened.clear();
        if (newest != null) {
            Rect was = (Rect) newest[0];
            AccessibilityNodeInfo near = null;
            long best = 400L * 400L;
            for (AccessibilityNodeInfo n : all) {
                Rect b = rect(n);
                long dx = b.centerX() - was.centerX(), dy = b.centerY() - was.centerY();
                if (dx * dx + dy * dy < best && isClear(b)) {
                    best = dx * dx + dy * dy;
                    near = n;
                }
            }
            if (near != null) return near;
        }
        return first(all);
    }

    /** Where to start: the top-left item below the app's top bar, or else the first one. */
    private AccessibilityNodeInfo first(List<AccessibilityNodeInfo> all) {
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MAX_VALUE;
        for (AccessibilityNodeInfo n : all) {
            Rect r = rect(n);
            if (bars.contains(n) || !isClear(r)) continue;
            long score = (long) r.top * 4 + r.left;
            if (score < bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best != null ? best : all.get(0);
    }

    /** Items level with this one (their middles within its height): its row. */
    private static List<AccessibilityNodeInfo> sameRow(List<AccessibilityNodeInfo> all, Rect a) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : all) {
            int cy = rect(n).centerY();
            if (cy >= a.top && cy <= a.bottom) out.add(n);
        }
        return out;
    }

    /** The next item that way: the nearest row (or column) first, then the one closest to the anchor line. */
    private static AccessibilityNodeInfo nearest(List<AccessibilityNodeInfo> all, Rect a, int direction, int anchorX) {
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MAX_VALUE;
        for (AccessibilityNodeInfo n : all) {
            Rect b = rect(n);
            if (b.equals(a)) continue;
            int major, minor;
            switch (direction) {
                case View.FOCUS_UP: major = a.top - b.bottom; minor = Math.abs(anchorX - b.centerX()); break;
                case View.FOCUS_DOWN: major = b.top - a.bottom; minor = Math.abs(anchorX - b.centerX()); break;
                case View.FOCUS_LEFT: major = a.left - b.right; minor = Math.abs(a.centerY() - b.centerY()); break;
                default: major = b.left - a.right; minor = Math.abs(a.centerY() - b.centerY()); break;
            }
            // Overlapping a little (tight layouts) still counts; behind or level does not.
            boolean ahead;
            switch (direction) {
                case View.FOCUS_UP: ahead = b.centerY() < a.top && b.bottom < a.bottom; break;
                case View.FOCUS_DOWN: ahead = b.centerY() > a.bottom && b.top > a.top; break;
                case View.FOCUS_LEFT: ahead = b.centerX() < a.left; break;
                default: ahead = b.centerX() > a.right; break;
            }
            if (!ahead) continue;
            long score = 20L * Math.max(0, major) * Math.max(0, major) + (long) minor * minor;
            if (score < bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    private static Rect rect(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r;
    }

    private static long area(Rect r) {
        return (long) r.width() * r.height();
    }

    // ---- the ring and the tap -------------------------------------------------------------------------------------

    /** The video's controls faded while the user was moving among them: back up, the ring where it was. */
    private boolean controlsBack() {
        if (android.os.SystemClock.uptimeMillis() - lastRingAt >= 8000 || selectedRect.isEmpty()) return false;
        Rect was = new Rect(selectedRect);
        showControls();
        main.postDelayed(() -> {
            List<AccessibilityNodeInfo> now = targets();
            for (AccessibilityNodeInfo n : now) if (rect(n).equals(was)) { select(n); return; }
            if (!now.isEmpty()) select(back(now));
        }, 450);
        lastRingAt = 0;
        return true;
    }

    /** When the ring last moved (on an item that may fade, like a video's controls). */
    private long lastRingAt;

    private void select(AccessibilityNodeInfo node) {
        lastRingAt = android.os.SystemClock.uptimeMillis();
        selected = node;
        node.getBoundsInScreen(selectedRect);
        place(selectedRect);
        // Tells the app where the user is (a video player keeps its controls up while one of them has it).
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        main.removeCallbacks(watch);
        main.postDelayed(watch, 1000);
    }

    /** The ring's item went away by itself (a video's controls fade out): so does the ring. */
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (selected == null || ring == null) return;
            if (!settling) {
                boolean there = false;
                for (AccessibilityNodeInfo n : targets()) if (rect(n).equals(selectedRect)) there = true;
                if (!there) {
                    hide();
                    selected = null;
                    return;
                }
            }
            main.postDelayed(this, 1000);
        }
    };

    private void place(Rect r) {
        float d = service.getResources().getDisplayMetrics().density;
        int pad = Math.round(5 * d);
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
                g.setColor(0x1AFFFFFF);
                g.setCornerRadius(12 * d);
                g.setStroke(Math.round(4 * d), 0xFFFFFFFF);
                ring.setBackground(g);
                wm.addView(ring, lp);
            } else {
                wm.updateViewLayout(ring, lp);
            }
        } catch (RuntimeException failed) {
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

    /** A finger tap, for an item that does not answer a click (gestures do not work on every projector). */
    private void tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build();
        service.dispatchGesture(g, null, null);
    }
}

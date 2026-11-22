package com.xmarthost.blocker;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.regex.Pattern;

/** Walks the accessibility tree of a window looking for Shorts markers or a given text. */
public final class NodeScanner {
    private static final int MAX_NODES = 2500;

    private NodeScanner() {}

    /** @return a short description of the first Shorts marker found, or null. */
    public static String findShorts(AccessibilityNodeInfo root, Rules rules) {
        if (root == null) return null;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen++ < MAX_NODES) {
            AccessibilityNodeInfo n = q.removeFirst();
            String id = n.getViewIdResourceName();
            if (id != null && n.isVisibleToUser()) {
                for (String frag : rules.shortsViewIds) {
                    if (!frag.isEmpty() && id.contains(frag)) return "id:" + id;
                }
            }
            if (!rules.shortsTextPatterns.isEmpty() && n.isVisibleToUser()) {
                String t = text(n);
                if (t != null) {
                    for (Pattern p : rules.shortsTextPatterns) {
                        if (p.matcher(t).find()) return "text:" + t;
                    }
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.addLast(c);
            }
        }
        return null;
    }

    public static boolean containsText(AccessibilityNodeInfo root, String needle) {
        if (root == null || needle == null || needle.isEmpty()) return false;
        String lower = needle.toLowerCase();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen++ < MAX_NODES) {
            AccessibilityNodeInfo n = q.removeFirst();
            String t = text(n);
            if (t != null && t.toLowerCase().contains(lower)) return true;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.addLast(c);
            }
        }
        return false;
    }

    /** Compact dump of the tree, used by Diagnostics to learn what a screen exposes. */
    public static String dump(AccessibilityNodeInfo root, int maxLines) {
        if (root == null) return "(no window content)";
        StringBuilder sb = new StringBuilder();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        ArrayDeque<Integer> depth = new ArrayDeque<>();
        q.add(root);
        depth.add(0);
        int lines = 0, seen = 0;
        while (!q.isEmpty() && lines < maxLines && seen++ < MAX_NODES) {
            AccessibilityNodeInfo n = q.removeFirst();
            int d = depth.removeFirst();
            String id = n.getViewIdResourceName();
            String t = text(n);
            CharSequence cls = n.getClassName();
            if (id != null || t != null || d < 3) {
                sb.append("  ".repeat(Math.min(d, 8)))
                        .append(cls == null ? "?" : simple(cls.toString()));
                if (id != null) sb.append(" #").append(id.substring(id.indexOf('/') + 1));
                if (t != null) sb.append(" \"").append(t.length() > 60 ? t.substring(0, 60) : t).append('"');
                sb.append('\n');
                lines++;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) {
                    q.addLast(c);
                    depth.addLast(d + 1);
                }
            }
        }
        return sb.toString();
    }

    private static String simple(String cls) {
        int i = cls.lastIndexOf('.');
        return i >= 0 ? cls.substring(i + 1) : cls;
    }

    private static String text(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) return t.toString();
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 0) return d.toString();
        return null;
    }
}

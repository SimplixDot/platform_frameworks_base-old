/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view;

import android.app.ActivityManager;
import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Slog;
import android.view.WindowManager.LayoutParams;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Iterator;

/**
 * Runtime adjustments applied to the global window policy.
 *
 * This includes forcing immersive mode behavior for one or both system bars (based on a package
 * list) and permanently disabling immersive mode confirmations for specific packages.
 *
 * Control by setting {@link Settings.Global#POLICY_CONTROL} to one or more name-value pairs.
 * e.g.
 *   to force immersive mode everywhere:
 *     "immersive.full=*"
 *   to force transient status for all apps except a specific package:
 *     "immersive.status=apps,-com.package"
 *   to disable the immersive mode confirmations for specific packages:
 *     "immersive.preconfirms=com.package.one,com.package.two"
 *
 * Separate multiple name-value pairs with ':'
 *   e.g. "immersive.status=apps:immersive.preconfirms=*"
 */
public class WindowManagerPolicyControl {
    private static String TAG = "PolicyControl";
    private static boolean DEBUG = false;

    private static final String NAME_IMMERSIVE_FULL = "immersive.full";
    private static final String NAME_IMMERSIVE_STATUS = "immersive.status";
    private static final String NAME_IMMERSIVE_NAVIGATION = "immersive.navigation";
    private static final String NAME_IMMERSIVE_PRECONFIRMATIONS = "immersive.preconfirms";

    private static int sDefaultImmersiveStyle;
    private static String sSettingValue;
    private static Filter sImmersivePreconfirmationsFilter;
    private static Filter sImmersiveStatusFilter;
    private static Filter sImmersiveNavigationFilter;

    /**
     * Accessible constants for Settings
     */
    public final static class ImmersiveDefaultStyles {
        public final static int IMMERSIVE_FULL = 0;
        public final static int IMMERSIVE_STATUS = 1;
        public final static int IMMERSIVE_NAVIGATION = 2;
    }
     public static int getSystemUiVisibility(int vis, LayoutParams attrs) {
        if (sImmersiveStatusFilter != null && sImmersiveStatusFilter.matches(attrs)
                && (sDefaultImmersiveStyle == ImmersiveDefaultStyles.IMMERSIVE_FULL ||
                sDefaultImmersiveStyle == ImmersiveDefaultStyles.IMMERSIVE_STATUS))  {
            vis |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            vis &= ~(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.STATUS_BAR_TRANSLUCENT);
        }
        if (sImmersiveNavigationFilter != null && sImmersiveNavigationFilter.matches(attrs)
                && (sDefaultImmersiveStyle == ImmersiveDefaultStyles.IMMERSIVE_FULL ||
                sDefaultImmersiveStyle == ImmersiveDefaultStyles.IMMERSIVE_NAVIGATION)) {
            vis |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            vis &= ~(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.NAVIGATION_BAR_TRANSLUCENT);
        }
        return vis;
    }

    public static int getWindowFlags(int flags, LayoutParams attrs) {
        if (sImmersiveStatusFilter != null && sImmersiveStatusFilter.matches(attrs)
                && (sDefaultImmersiveStyle == ImmersiveDefaultStyles.IMMERSIVE_FULL ||
                sDefaultImmersiveStyle == ImmersiveDefaultStyles.IMMERSIVE_STATUS)) {
            flags |= WindowManager.LayoutParams.FLAG_FULLSCREEN;
            flags &= ~(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                    | WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        }
        if (sImmersiveNavigationFilter != null && sImmersiveNavigationFilter.matches(attrs)
                && (sDefaultImmersiveStyle == ImmersiveDefaultStyles.IMMERSIVE_FULL ||
                sDefaultImmersiveStyle == ImmersiveDefaultStyles.IMMERSIVE_NAVIGATION)) {
            flags &= ~WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
        }
        return flags;
    }

    public static int getPrivateWindowFlags(int privateFlags, LayoutParams attrs) {
        if (sImmersiveStatusFilter != null && sImmersiveNavigationFilter != null &&
                sImmersiveStatusFilter.isEnabledForAll()
                && sImmersiveNavigationFilter.isEnabledForAll()) {
             if ((attrs.flags & LayoutParams.FLAG_FULLSCREEN) == 0) {
                privateFlags |= LayoutParams.PRIVATE_FLAG_WAS_NOT_FULLSCREEN;
            }
             switch (sDefaultImmersiveStyle) {
                case ImmersiveDefaultStyles.IMMERSIVE_FULL:
                    privateFlags |= LayoutParams.PRIVATE_FLAG_NAV_HIDE_FORCED;
                    privateFlags |= LayoutParams.PRIVATE_FLAG_STATUS_HIDE_FORCED;
                    return privateFlags;
                case ImmersiveDefaultStyles.IMMERSIVE_STATUS:
                    privateFlags |= LayoutParams.PRIVATE_FLAG_STATUS_HIDE_FORCED;
                    return privateFlags;
                case ImmersiveDefaultStyles.IMMERSIVE_NAVIGATION:
                    privateFlags |= LayoutParams.PRIVATE_FLAG_NAV_HIDE_FORCED;
                    return privateFlags;
                }
        }
         if (sImmersiveStatusFilter != null && sImmersiveStatusFilter.matches(attrs)) {
            if ((attrs.flags & LayoutParams.FLAG_FULLSCREEN) == 0) {
                privateFlags |= LayoutParams.PRIVATE_FLAG_WAS_NOT_FULLSCREEN;
            }
            privateFlags |= LayoutParams.PRIVATE_FLAG_STATUS_HIDE_FORCED;
        }
         if (sImmersiveNavigationFilter != null && sImmersiveNavigationFilter.matches(attrs)) {
            privateFlags |= LayoutParams.PRIVATE_FLAG_NAV_HIDE_FORCED;
        }
         return privateFlags;
    }

     public static int adjustClearableFlags(LayoutParams attrs, int clearableFlags) {
        if (sImmersiveStatusFilter != null && sImmersiveStatusFilter.matches(attrs)) {
            clearableFlags &= ~View.SYSTEM_UI_FLAG_FULLSCREEN;
        }
        return clearableFlags;
    }

    public static boolean immersiveStatusFilterMatches(String packageName) {
        return sImmersiveStatusFilter != null && sImmersiveStatusFilter.matches(packageName);
    }
     public static boolean immersiveNavigationFilterMatches(String packageName) {
        return sImmersiveNavigationFilter != null
                && sImmersiveNavigationFilter.matches(packageName);
    }

    public static boolean disableImmersiveConfirmation(String pkg) {
        return (sImmersivePreconfirmationsFilter != null
                && sImmersivePreconfirmationsFilter.matches(pkg))
                || ActivityManager.isRunningInTestHarness();
    }

    public static void reloadFromSetting(Context context) {
        sDefaultImmersiveStyle = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.POLICY_CONTROL_STYLE,
                WindowManagerPolicyControl.ImmersiveDefaultStyles.IMMERSIVE_FULL);
        if (DEBUG) Slog.d(TAG, "reloadStyleFromSetting " + sDefaultImmersiveStyle);

        String value = null;
        try {
            value = Settings.Global.getStringForUser(context.getContentResolver(),
                    Settings.Global.POLICY_CONTROL,
                    UserHandle.USER_CURRENT);
            if (sSettingValue != null && sSettingValue.equals(value)) return;
            setFilters(value);
            sSettingValue = value;
        } catch (Throwable t) {
            Slog.w(TAG, "Error loading policy control, value=" + value, t);
        }
    }

    public static void saveToSettings(Context context) {
        StringBuilder value = new StringBuilder();
        boolean needSemicolon = false;
        if (sImmersiveStatusFilter != null) {
            writeFilter(NAME_IMMERSIVE_STATUS, sImmersiveStatusFilter, value);
            needSemicolon = true;
        }
        if (sImmersiveNavigationFilter != null) {
            if (needSemicolon) {
                value.append(":");
            }
            writeFilter(NAME_IMMERSIVE_NAVIGATION, sImmersiveNavigationFilter, value);
        }
         Settings.Global.putString(context.getContentResolver(),
                Settings.Global.POLICY_CONTROL, value.toString());
    }
     public static void saveStyleToSettings(Context context, int value) {
        Settings.Global.putInt(context.getContentResolver(),
                Settings.Global.POLICY_CONTROL_STYLE, value);
        sDefaultImmersiveStyle = value;
    }
     public static void addToStatusWhiteList(String packageName) {
        if (sImmersiveStatusFilter == null) {
            sImmersiveStatusFilter = new Filter(new ArraySet<String>(), new ArraySet<String>());
        }
         if (!sImmersiveStatusFilter.mWhitelist.contains(packageName)) {
            sImmersiveStatusFilter.mWhitelist.add(packageName);
        }
    }
     public static void addToNavigationWhiteList(String packageName) {
        if (sImmersiveNavigationFilter == null) {
            sImmersiveNavigationFilter = new Filter(new ArraySet<String>(), new ArraySet<String>());
        }
         if (!sImmersiveNavigationFilter.mWhitelist.contains(packageName)) {
            sImmersiveNavigationFilter.mWhitelist.add(packageName);
        }
    }
     public static void removeFromWhiteLists(String packageName) {
        if (sImmersiveStatusFilter != null) {
            sImmersiveStatusFilter.mWhitelist.remove(packageName);
        }
        if (sImmersiveNavigationFilter != null) {
            sImmersiveNavigationFilter.mWhitelist.remove(packageName);
        }
    }
     private static void writeFilter(String name, Filter filter, StringBuilder stringBuilder) {
        if (filter.mWhitelist.isEmpty() && filter.mBlacklist.isEmpty()) {
            return;
        }
        stringBuilder.append(name);
        stringBuilder.append("=");
         boolean needComma = false;
        if (!filter.mWhitelist.isEmpty()) {
            writePackages(filter.mWhitelist, stringBuilder, false);
            needComma = true;
        }
        if (!filter.mBlacklist.isEmpty()) {
            if (needComma) {
                stringBuilder.append(",");
            }
            writePackages(filter.mBlacklist, stringBuilder, true);
        }
    }
     private static void writePackages(ArraySet<String> set, StringBuilder stringBuilder,
                                      boolean isBlackList) {
        Iterator<String> iterator = set.iterator();
        while (iterator.hasNext()) {
            if (isBlackList) {
                stringBuilder.append("-");
            }
            String name = iterator.next();
            stringBuilder.append(name);
            if (iterator.hasNext()) {
                stringBuilder.append(",");
            }
        }
    }

    public static void dump(String prefix, PrintWriter pw) {
        dump("sImmersiveStatusFilter", sImmersiveStatusFilter, prefix, pw);
        dump("sImmersiveNavigationFilter", sImmersiveNavigationFilter, prefix, pw);
        dump("sImmersivePreconfirmationsFilter", sImmersivePreconfirmationsFilter, prefix, pw);
    }

    private static void dump(String name, Filter filter, String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("PolicyControl."); pw.print(name); pw.print('=');
        if (filter == null) {
            pw.println("null");
        } else {
            filter.dump(pw); pw.println();
        }
    }

    private static void setFilters(String value) {
        if (DEBUG) Slog.d(TAG, "setFilters: " + value);
        sImmersiveStatusFilter = null;
        sImmersiveNavigationFilter = null;
        sImmersivePreconfirmationsFilter = null;
        if (value != null) {
            String[] nvps = value.split(":");
            for (String nvp : nvps) {
                int i = nvp.indexOf('=');
                if (i == -1) continue;
                String n = nvp.substring(0, i);
                String v = nvp.substring(i + 1);
                if (n.equals(NAME_IMMERSIVE_FULL)) {
                    Filter f = Filter.parse(v);
                    sImmersiveStatusFilter = sImmersiveNavigationFilter = f;
                    if (sImmersivePreconfirmationsFilter == null) {
                        sImmersivePreconfirmationsFilter = f;
                    }
                } else if (n.equals(NAME_IMMERSIVE_STATUS)) {
                    Filter f = Filter.parse(v);
                    sImmersiveStatusFilter = f;
                } else if (n.equals(NAME_IMMERSIVE_NAVIGATION)) {
                    Filter f = Filter.parse(v);
                    sImmersiveNavigationFilter = f;
                    if (sImmersivePreconfirmationsFilter == null) {
                        sImmersivePreconfirmationsFilter = f;
                    }
                } else if (n.equals(NAME_IMMERSIVE_PRECONFIRMATIONS)) {
                    Filter f = Filter.parse(v);
                    sImmersivePreconfirmationsFilter = f;
                }
            }
        }
        if (DEBUG) {
            Slog.d(TAG, "immersiveStatusFilter: " + sImmersiveStatusFilter);
            Slog.d(TAG, "immersiveNavigationFilter: " + sImmersiveNavigationFilter);
            Slog.d(TAG, "immersivePreconfirmationsFilter: " + sImmersivePreconfirmationsFilter);
        }
    }

    private static class Filter {
        private static final String ALL = "*";
        private static final String APPS = "apps";

        private final ArraySet<String> mWhitelist;
        private final ArraySet<String> mBlacklist;

        private Filter(ArraySet<String> whitelist, ArraySet<String> blacklist) {
            mWhitelist = whitelist;
            mBlacklist = blacklist;
        }

        boolean matches(LayoutParams attrs) {
            if (attrs == null) return false;
            boolean isApp = attrs.type >= WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW
                    && attrs.type <= WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
            if (isApp && mBlacklist.contains(APPS)) return false;
            if (onBlacklist(attrs.packageName)) return false;
            if (isApp && mWhitelist.contains(APPS)) return true;
            return onWhitelist(attrs.packageName);
        }

        boolean matches(String packageName) {
            return !onBlacklist(packageName) && onWhitelist(packageName);
        }

        public boolean isEnabledForAll() {
            return mWhitelist.contains(ALL);
        }

        private boolean onBlacklist(String packageName) {
            return mBlacklist.contains(packageName) || mBlacklist.contains(ALL);
        }

        private boolean onWhitelist(String packageName) {
            return mWhitelist.contains(ALL) || mWhitelist.contains(packageName);
        }

        void dump(PrintWriter pw) {
            pw.print("Filter[");
            dump("whitelist", mWhitelist, pw); pw.print(',');
            dump("blacklist", mBlacklist, pw); pw.print(']');
        }

        private void dump(String name, ArraySet<String> set, PrintWriter pw) {
            pw.print(name); pw.print("=(");
            final int n = set.size();
            for (int i = 0; i < n; i++) {
                if (i > 0) pw.print(',');
                pw.print(set.valueAt(i));
            }
            pw.print(')');
        }

        @Override
        public String toString() {
            StringWriter sw = new StringWriter();
            dump(new PrintWriter(sw, true));
            return sw.toString();
        }

        // value = comma-delimited list of tokens, where token = (package name|apps|*)
        // e.g. "com.package1", or "apps, com.android.keyguard" or "*"
        static Filter parse(String value) {
            if (value == null) return null;
            ArraySet<String> whitelist = new ArraySet<String>();
            ArraySet<String> blacklist = new ArraySet<String>();
            for (String token : value.split(",")) {
                token = token.trim();
                if (token.startsWith("-") && token.length() > 1) {
                    token = token.substring(1);
                    blacklist.add(token);
                } else {
                    whitelist.add(token);
                }
            }
            return new Filter(whitelist, blacklist);
        }
    }
}

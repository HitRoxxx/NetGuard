package eu.faircode.netguard;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.preference.PreferenceManager;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Rule implements Comparable<Rule> {
    public PackageInfo info;
    public String name;
    public boolean system;
    public boolean disabled;
    public boolean wifi_blocked;
    public boolean other_blocked;
    public boolean unused;
    public boolean roaming;
    public boolean changed;
    public Intent intent;
    public boolean attributes = false;

    private Rule(PackageInfo info, Context context) {
        PackageManager pm = context.getPackageManager();

        this.info = info;
        this.name = info.applicationInfo.loadLabel(pm).toString();

        int setting = pm.getApplicationEnabledSetting(info.packageName);
        if (setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            this.disabled = !info.applicationInfo.enabled;
        else
            this.disabled = (setting != PackageManager.COMPONENT_ENABLED_STATE_ENABLED);

        this.intent = pm.getLaunchIntentForPackage(info.packageName);
    }

    public static List<Rule> getRules(boolean all, String tag, Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences wifi = context.getSharedPreferences("wifi", Context.MODE_PRIVATE);
        SharedPreferences other = context.getSharedPreferences("other", Context.MODE_PRIVATE);
        SharedPreferences unused = context.getSharedPreferences("unused", Context.MODE_PRIVATE);
        SharedPreferences roaming = context.getSharedPreferences("roaming", Context.MODE_PRIVATE);

        // Get settings
        boolean whitelist_wifi = prefs.getBoolean("whitelist_wifi", true);
        boolean whitelist_other = prefs.getBoolean("whitelist_other", true);
        boolean whitelist_roaming = prefs.getBoolean("whitelist_roaming", true);
        boolean manage_system = prefs.getBoolean("manage_system", false);

        // Get predefined rules
        Map<String, Boolean> pre_blocked = new HashMap<>();
        Map<String, Boolean> pre_unused = new HashMap<>();
        try {
            XmlResourceParser xml = context.getResources().getXml(R.xml.predefined);
            int eventType = xml.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && "rule".equals(xml.getName())) {
                    String pkg = xml.getAttributeValue(null, "package");
                    boolean pblocked = xml.getAttributeBooleanValue(null, "blocked", false);
                    boolean punused = xml.getAttributeBooleanValue(null, "unused", false);
                    pre_blocked.put(pkg, pblocked);
                    pre_unused.put(pkg, punused);
                    Log.i(tag, "Predefined " + pkg + " blocked=" + pblocked + " unused" + punused);
                }
                eventType = xml.next();
            }
        } catch (Throwable ex) {
            Log.e(tag, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }

        // Build rule list
        List<Rule> listRules = new ArrayList<>();
        for (PackageInfo info : context.getPackageManager().getInstalledPackages(0)) {
            boolean system = ((info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            if (!system || manage_system || all) {
                boolean isPreBlocked = pre_blocked.containsKey(info.packageName);
                boolean isPreUnused = pre_unused.containsKey(info.packageName);
                Rule rule = new Rule(info, context);
                rule.system = system;
                rule.wifi_blocked = (system && !manage_system ? false :
                        wifi.getBoolean(info.packageName, isPreBlocked ? pre_blocked.get(info.packageName) : whitelist_wifi));
                rule.other_blocked = (system && !manage_system ? false :
                        other.getBoolean(info.packageName, isPreBlocked ? pre_blocked.get(info.packageName) : whitelist_other));
                rule.unused = unused.getBoolean(info.packageName, isPreUnused ? pre_unused.get(info.packageName) : false);
                rule.roaming = roaming.getBoolean(info.packageName, isPreBlocked ? pre_blocked.get(info.packageName) : whitelist_roaming);
                rule.changed = (rule.wifi_blocked != whitelist_wifi ||
                        rule.other_blocked != whitelist_other ||
                        (!rule.other_blocked || rule.unused) && rule.roaming != whitelist_roaming);
                listRules.add(rule);
            }
        }

        // Sort rule list
        Collections.sort(listRules);

        return listRules;
    }

    @Override
    public int compareTo(Rule other) {
        if ((changed || unused) == (other.changed || other.unused)) {
            int i = name.compareToIgnoreCase(other.name);
            return (i == 0 ? info.packageName.compareTo(other.info.packageName) : i);
        }
        return (changed || unused ? -1 : 1);
    }
}

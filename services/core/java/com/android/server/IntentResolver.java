/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.FastImmutableArraySet;
import android.util.Log;
import android.util.LogPrinter;
import android.util.MutableInt;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.FastPrintWriter;
import com.android.server.pm.Computer;
import com.android.server.pm.snapshot.PackageDataSnapshot;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * {@hide}
 */
public abstract class IntentResolver<F, R extends Object> {
    final private static String TAG = "IntentResolver";
    final private static boolean DEBUG = false;
    final private static boolean localLOGV = DEBUG || false;
    final private static boolean localVerificationLOGV = DEBUG || false;

    public void addFilter(@Nullable PackageDataSnapshot snapshot, F f) {
        IntentFilter intentFilter = getIntentFilter(f);
        if (localLOGV) {
            Slog.v(TAG, "Adding filter: " + f);
            intentFilter.dump(new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM), "      ");
            Slog.v(TAG, "    Building Lookup Maps:");
        }

        mFilters.add(f);
        int numS = register_intent_filter(f, intentFilter.schemesIterator(),
                mSchemeToFilter, "      Scheme: ");
        int numT = register_mime_types(f, "      Type: ");
        if (numS == 0 && numT == 0) {
            register_intent_filter(f, intentFilter.actionsIterator(),
                    mActionToFilter, "      Action: ");
        }
        if (numT != 0) {
            register_intent_filter(f, intentFilter.actionsIterator(),
                    mTypedActionToFilter, "      TypedAction: ");
        }
    }

    /**
     * Returns whether an intent matches the IntentFilter with a pre-resolved type.
     */
    public static boolean intentMatchesFilter(
            IntentFilter filter, Intent intent, String resolvedType, boolean defaultOnly) {
        final boolean debug = localLOGV
                || ((intent.getFlags() & Intent.FLAG_DEBUG_LOG_RESOLUTION) != 0);

        final Printer logPrinter = debug
                ? new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM) : null;

        if (debug) {
            Slog.v(TAG, "Intent: " + intent);
            Slog.v(TAG, "Matching against filter: " + filter);
            filter.dump(logPrinter, "  ");
        }

        int match = filter.match(intent.getAction(), resolvedType, intent.getScheme(),
                intent.getData(), intent.getCategories(), TAG);

        if (match >= 0 && defaultOnly && !filter.hasCategory(Intent.CATEGORY_DEFAULT)) {
            match = IntentFilter.NO_MATCH_CATEGORY;
        }

        if (match >= 0) {
            if (debug) {
                Slog.v(TAG, "Filter matched!  match=0x" + Integer.toHexString(match));
            }
            return true;
        } else {
            if (debug) {
                final String reason;
                switch (match) {
                    case IntentFilter.NO_MATCH_ACTION: reason = "action"; break;
                    case IntentFilter.NO_MATCH_CATEGORY: reason = "category"; break;
                    case IntentFilter.NO_MATCH_DATA: reason = "data"; break;
                    case IntentFilter.NO_MATCH_TYPE: reason = "type"; break;
                    default: reason = "unknown reason"; break;
                }
                Slog.v(TAG, "Filter did not match: " + reason);
            }
            return false;
        }
    }

    private ArrayList<F> collectFilters(F[] array, IntentFilter matching) {
        ArrayList<F> res = null;
        if (array != null) {
            for (int i=0; i<array.length; i++) {
                F cur = array[i];
                if (cur == null) {
                    break;
                }
                if (IntentFilter.filterEquals(getIntentFilter(cur), matching)) {
                    if (res == null) {
                        res = new ArrayList<>();
                    }
                    res.add(cur);
                }
            }
        }
        return res;
    }

    public ArrayList<F> findFilters(IntentFilter matching) {
        if (matching.countDataSchemes() == 1) {
            // Fast case.
            return collectFilters(mSchemeToFilter.get(matching.getDataScheme(0)), matching);
        } else if (matching.countDataTypes() != 0 && matching.countActions() == 1) {
            // Another fast case.
            return collectFilters(mTypedActionToFilter.get(matching.getAction(0)), matching);
        } else if (matching.countDataTypes() == 0 && matching.countDataSchemes() == 0
                && matching.countActions() == 1) {
            // Last fast case.
            return collectFilters(mActionToFilter.get(matching.getAction(0)), matching);
        } else {
            ArrayList<F> res = null;
            for (F cur : mFilters) {
                if (IntentFilter.filterEquals(getIntentFilter(cur), matching)) {
                    if (res == null) {
                        res = new ArrayList<>();
                    }
                    res.add(cur);
                }
            }
            return res;
        }
    }

    public void removeFilter(F f) {
        removeFilterInternal(f);
        mFilters.remove(f);
    }

    protected void removeFilterInternal(F f) {
        IntentFilter intentFilter = getIntentFilter(f);
        if (localLOGV) {
            Slog.v(TAG, "Removing filter: " + f);
            intentFilter.dump(new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM), "      ");
            Slog.v(TAG, "    Cleaning Lookup Maps:");
        }

        int numS = unregister_intent_filter(f, intentFilter.schemesIterator(),
                mSchemeToFilter, "      Scheme: ");
        int numT = unregister_mime_types(f, "      Type: ");
        if (numS == 0 && numT == 0) {
            unregister_intent_filter(f, intentFilter.actionsIterator(),
                    mActionToFilter, "      Action: ");
        }
        if (numT != 0) {
            unregister_intent_filter(f, intentFilter.actionsIterator(),
                    mTypedActionToFilter, "      TypedAction: ");
        }
    }

    boolean dumpMap(PrintWriter out, String titlePrefix, String title,
            String prefix, ArrayMap<String, F[]> map, String packageName,
            boolean printFilter, boolean collapseDuplicates) {
        final String eprefix = prefix + "  ";
        final String fprefix = prefix + "    ";
        final ArrayMap<Object, MutableInt> found = new ArrayMap<>();
        boolean printedSomething = false;
        Printer printer = null;
        for (int mapi=0; mapi<map.size(); mapi++) {
            F[] a = map.valueAt(mapi);
            final int N = a.length;
            boolean printedHeader = false;
            F filter;
            if (collapseDuplicates && !printFilter) {
                found.clear();
                for (int i=0; i<N && (filter=a[i]) != null; i++) {
                    if (packageName != null && !isPackageForFilter(packageName, filter)) {
                        continue;
                    }
                    Object label = filterToLabel(filter);
                    int index = found.indexOfKey(label);
                    if (index < 0) {
                        found.put(label, new MutableInt(1));
                    } else {
                        found.valueAt(index).value++;
                    }
                }
                for (int i=0; i<found.size(); i++) {
                    if (title != null) {
                        out.print(titlePrefix); out.println(title);
                        title = null;
                    }
                    if (!printedHeader) {
                        out.print(eprefix); out.print(map.keyAt(mapi)); out.println(":");
                        printedHeader = true;
                    }
                    printedSomething = true;
                    dumpFilterLabel(out, fprefix, found.keyAt(i), found.valueAt(i).value);
                }
            } else {
                for (int i=0; i<N && (filter=a[i]) != null; i++) {
                    if (packageName != null && !isPackageForFilter(packageName, filter)) {
                        continue;
                    }
                    if (title != null) {
                        out.print(titlePrefix); out.println(title);
                        title = null;
                    }
                    if (!printedHeader) {
                        out.print(eprefix); out.print(map.keyAt(mapi)); out.println(":");
                        printedHeader = true;
                    }
                    printedSomething = true;
                    dumpFilter(out, fprefix, filter);
                    if (printFilter) {
                        if (printer == null) {
                            printer = new PrintWriterPrinter(out);
                        }
                        getIntentFilter(filter).dump(printer, fprefix + "  ");
                    }
                }
            }
        }
        return printedSomething;
    }

    void writeProtoMap(ProtoOutputStream proto, long fieldId, ArrayMap<String, F[]> map) {
        int N = map.size();
        for (int mapi = 0; mapi < N; mapi++) {
            long token = proto.start(fieldId);
            proto.write(IntentResolverProto.ArrayMapEntry.KEY, map.keyAt(mapi));
            for (F f : map.valueAt(mapi)) {
                if (f != null) {
                    proto.write(IntentResolverProto.ArrayMapEntry.VALUES, f.toString());
                }
            }
            proto.end(token);
        }
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        writeProtoMap(proto, IntentResolverProto.FULL_MIME_TYPES, mTypeToFilter);
        writeProtoMap(proto, IntentResolverProto.BASE_MIME_TYPES, mBaseTypeToFilter);
        writeProtoMap(proto, IntentResolverProto.WILD_MIME_TYPES, mWildTypeToFilter);
        writeProtoMap(proto, IntentResolverProto.SCHEMES, mSchemeToFilter);
        writeProtoMap(proto, IntentResolverProto.NON_DATA_ACTIONS, mActionToFilter);
        writeProtoMap(proto, IntentResolverProto.MIME_TYPED_ACTIONS, mTypedActionToFilter);
        proto.end(token);
    }

    public boolean dump(PrintWriter out, String title, String prefix, String packageName,
            boolean printFilter, boolean collapseDuplicates) {
        String innerPrefix = prefix + "  ";
        String sepPrefix = "\n" + prefix;
        String curPrefix = title + "\n" + prefix;
        if (dumpMap(out, curPrefix, "Full MIME Types:", innerPrefix,
                mTypeToFilter, packageName, printFilter, collapseDuplicates)) {
            curPrefix = sepPrefix;
        }
        if (dumpMap(out, curPrefix, "Base MIME Types:", innerPrefix,
                mBaseTypeToFilter, packageName, printFilter, collapseDuplicates)) {
            curPrefix = sepPrefix;
        }
        if (dumpMap(out, curPrefix, "Wild MIME Types:", innerPrefix,
                mWildTypeToFilter, packageName, printFilter, collapseDuplicates)) {
            curPrefix = sepPrefix;
        }
        if (dumpMap(out, curPrefix, "Schemes:", innerPrefix,
                mSchemeToFilter, packageName, printFilter, collapseDuplicates)) {
            curPrefix = sepPrefix;
        }
        if (dumpMap(out, curPrefix, "Non-Data Actions:", innerPrefix,
                mActionToFilter, packageName, printFilter, collapseDuplicates)) {
            curPrefix = sepPrefix;
        }
        if (dumpMap(out, curPrefix, "MIME Typed Actions:", innerPrefix,
                mTypedActionToFilter, packageName, printFilter, collapseDuplicates)) {
            curPrefix = sepPrefix;
        }
        return curPrefix == sepPrefix;
    }

    private class IteratorWrapper implements Iterator<F> {
        private final Iterator<F> mI;
        private F mCur;

        IteratorWrapper(Iterator<F> it) {
            mI = it;
        }

        public boolean hasNext() {
            return mI.hasNext();
        }

        public F next() {
            return (mCur = mI.next());
        }

        public void remove() {
            if (mCur != null) {
                removeFilterInternal(mCur);
            }
            mI.remove();
        }

    }

    /**
     * Returns an iterator allowing filters to be removed.
     */
    public Iterator<F> filterIterator() {
        return new IteratorWrapper(mFilters.iterator());
    }

    /**
     * Returns a read-only set of the filters.
     */
    public Set<F> filterSet() {
        return Collections.unmodifiableSet(mFilters);
    }

    public List<R> queryIntentFromList(@NonNull Computer computer, Intent intent,
            String resolvedType, boolean defaultOnly, ArrayList<F[]> listCut, int userId,
            long customFlags) {
        ArrayList<R> resultList = new ArrayList<R>();

        final boolean debug = localLOGV ||
                ((intent.getFlags() & Intent.FLAG_DEBUG_LOG_RESOLUTION) != 0);

        FastImmutableArraySet<String> categories = getFastIntentCategories(intent);
        final String scheme = intent.getScheme();
        int N = listCut.size();
        for (int i = 0; i < N; ++i) {
            buildResolveList(computer, intent, categories, debug, defaultOnly, resolvedType, scheme,
                    listCut.get(i), resultList, userId, customFlags);
        }
        filterResults(resultList);
        sortResults(resultList);
        return resultList;
    }

    public List<R> queryIntent(@NonNull PackageDataSnapshot snapshot, Intent intent,
            String resolvedType, boolean defaultOnly, @UserIdInt int userId) {
        return queryIntent(snapshot, intent, resolvedType, defaultOnly, userId, 0);
    }

    protected final List<R> queryIntent(@NonNull PackageDataSnapshot snapshot, Intent intent,
            String resolvedType, boolean defaultOnly, @UserIdInt int userId, long customFlags) {
        String scheme = intent.getScheme();

        ArrayList<R> finalList = new ArrayList<R>();

        final boolean debug = localLOGV ||
                ((intent.getFlags() & Intent.FLAG_DEBUG_LOG_RESOLUTION) != 0);

        if (debug) Slog.v(
            TAG, "Resolving type=" + resolvedType + " scheme=" + scheme
            + " defaultOnly=" + defaultOnly + " userId=" + userId + " of " + intent);

        F[] firstTypeCut = null;
        F[] secondTypeCut = null;
        F[] thirdTypeCut = null;
        F[] schemeCut = null;

        // If the intent includes a MIME type, then we want to collect all of
        // the filters that match that MIME type.
        if (resolvedType != null) {
            int slashpos = resolvedType.indexOf('/');
            if (slashpos > 0) {
                final String baseType = resolvedType.substring(0, slashpos);
                if (!baseType.equals("*")) {
                    if (resolvedType.length() != slashpos+2
                            || resolvedType.charAt(slashpos+1) != '*') {
                        // Not a wild card, so we can just look for all filters that
                        // completely match or wildcards whose base type matches.
                        firstTypeCut = mTypeToFilter.get(resolvedType);
                        if (debug) Slog.v(TAG, "First type cut: " + Arrays.toString(firstTypeCut));
                        secondTypeCut = mWildTypeToFilter.get(baseType);
                        if (debug) Slog.v(TAG, "Second type cut: "
                                + Arrays.toString(secondTypeCut));
                    } else {
                        // We can match anything with our base type.
                        firstTypeCut = mBaseTypeToFilter.get(baseType);
                        if (debug) Slog.v(TAG, "First type cut: " + Arrays.toString(firstTypeCut));
                        secondTypeCut = mWildTypeToFilter.get(baseType);
                        if (debug) Slog.v(TAG, "Second type cut: "
                                + Arrays.toString(secondTypeCut));
                    }
                    // Any */* types always apply, but we only need to do this
                    // if the intent type was not already */*.
                    thirdTypeCut = mWildTypeToFilter.get("*");
                    if (debug) Slog.v(TAG, "Third type cut: " + Arrays.toString(thirdTypeCut));
                } else if (intent.getAction() != null) {
                    // The intent specified any type ({@literal *}/*).  This
                    // can be a whole heck of a lot of things, so as a first
                    // cut let's use the action instead.
                    firstTypeCut = mTypedActionToFilter.get(intent.getAction());
                    if (debug) Slog.v(TAG, "Typed Action list: " + Arrays.toString(firstTypeCut));
                }
            }
        }

        // If the intent includes a data URI, then we want to collect all of
        // the filters that match its scheme (we will further refine matches
        // on the authority and path by directly matching each resulting filter).
        if (scheme != null) {
            schemeCut = mSchemeToFilter.get(scheme);
            if (debug) Slog.v(TAG, "Scheme list: " + Arrays.toString(schemeCut));
        }

        // If the intent does not specify any data -- either a MIME type or
        // a URI -- then we will only be looking for matches against empty
        // data.
        if (resolvedType == null && scheme == null && intent.getAction() != null) {
            firstTypeCut = mActionToFilter.get(intent.getAction());
            if (debug) Slog.v(TAG, "Action list: " + Arrays.toString(firstTypeCut));
        }

        FastImmutableArraySet<String> categories = getFastIntentCategories(intent);
        Computer computer = (Computer) snapshot;
        if (firstTypeCut != null) {
            buildResolveList(computer, intent, categories, debug, defaultOnly, resolvedType,
                    scheme, firstTypeCut, finalList, userId, customFlags);
        }
        if (secondTypeCut != null) {
            buildResolveList(computer, intent, categories, debug, defaultOnly, resolvedType,
                    scheme, secondTypeCut, finalList, userId, customFlags);
        }
        if (thirdTypeCut != null) {
            buildResolveList(computer, intent, categories, debug, defaultOnly, resolvedType,
                    scheme, thirdTypeCut, finalList, userId, customFlags);
        }
        if (schemeCut != null) {
            buildResolveList(computer, intent, categories, debug, defaultOnly, resolvedType,
                    scheme, schemeCut, finalList, userId, customFlags);
        }
        filterResults(finalList);
        sortResults(finalList);

        if (debug) {
            Slog.v(TAG, "Final result list:");
            for (int i=0; i<finalList.size(); i++) {
                Slog.v(TAG, "  " + finalList.get(i));
            }
        }
        return finalList;
    }

    /**
     * Control whether the given filter is allowed to go into the result
     * list.  Mainly intended to prevent adding multiple filters for the
     * same target object.
     */
    protected boolean allowFilterResult(F filter, List<R> dest) {
        return true;
    }

    /**
     * Returns whether the object associated with the given filter is
     * "stopped", that is whether it should not be included in the result
     * if the intent requests to excluded stopped objects.
     */
    protected boolean isFilterStopped(@NonNull Computer computer, F filter, @UserIdInt int userId) {
        return false;
    }

    /**
     * Returns whether the given filter is "verified" that is whether it has been verified against
     * its data URIs.
     *
     * The verification would happen only and only if the Intent action is
     * {@link android.content.Intent#ACTION_VIEW} and the Intent category is
     * {@link android.content.Intent#CATEGORY_BROWSABLE} and the Intent data scheme
     * is "http" or "https".
     *
     * @see android.content.IntentFilter#setAutoVerify(boolean)
     * @see android.content.IntentFilter#getAutoVerify()
     */
    protected boolean isFilterVerified(F filter) {
        return getIntentFilter(filter).isVerified();
    }

    /**
     * Returns whether this filter is owned by this package. This must be
     * implemented to provide correct filtering of Intents that have
     * specified a package name they are to be delivered to.
     */
    protected abstract boolean isPackageForFilter(String packageName, F filter);

    protected abstract F[] newArray(int size);

    @SuppressWarnings("unchecked")
    protected R newResult(@NonNull Computer computer, F filter, int match, int userId,
            long customFlags) {
        return (R)filter;
    }

    @SuppressWarnings("unchecked")
    protected void sortResults(List<R> results) {
        Collections.sort(results, mResolvePrioritySorter);
    }

    /**
     * Apply filtering to the results. This happens before the results are sorted.
     */
    protected void filterResults(List<R> results) {
    }

    protected void dumpFilter(PrintWriter out, String prefix, F filter) {
        out.print(prefix); out.println(filter);
    }

    protected Object filterToLabel(F filter) {
        return "IntentFilter";
    }

    protected void dumpFilterLabel(PrintWriter out, String prefix, Object label, int count) {
        out.print(prefix); out.print(label); out.print(": "); out.println(count);
    }

    private final void addFilter(ArrayMap<String, F[]> map, String name, F filter) {
        F[] array = map.get(name);
        if (array == null) {
            array = newArray(2);
            map.put(name,  array);
            array[0] = filter;
        } else {
            final int N = array.length;
            int i = N;
            while (i > 0 && array[i-1] == null) {
                i--;
            }
            if (i < N) {
                array[i] = filter;
            } else {
                F[] newa = newArray((N*3)/2);
                System.arraycopy(array, 0, newa, 0, N);
                newa[N] = filter;
                map.put(name, newa);
            }
        }
    }

    private final int register_mime_types(F filter, String prefix) {
        final Iterator<String> i = getIntentFilter(filter).typesIterator();
        if (i == null) {
            return 0;
        }

        int num = 0;
        while (i.hasNext()) {
            String name = i.next();
            num++;
            if (localLOGV) Slog.v(TAG, prefix + name);
            String baseName = name;
            final int slashpos = name.indexOf('/');
            if (slashpos > 0) {
                baseName = name.substring(0, slashpos).intern();
            } else {
                name = name + "/*";
            }

            addFilter(mTypeToFilter, name, filter);

            if (slashpos > 0) {
                addFilter(mBaseTypeToFilter, baseName, filter);
            } else {
                addFilter(mWildTypeToFilter, baseName, filter);
            }
        }

        return num;
    }

    private final int unregister_mime_types(F filter, String prefix) {
        final Iterator<String> i = getIntentFilter(filter).typesIterator();
        if (i == null) {
            return 0;
        }

        int num = 0;
        while (i.hasNext()) {
            String name = i.next();
            num++;
            if (localLOGV) Slog.v(TAG, prefix + name);
            String baseName = name;
            final int slashpos = name.indexOf('/');
            if (slashpos > 0) {
                baseName = name.substring(0, slashpos).intern();
            } else {
                name = name + "/*";
            }

            remove_all_objects(mTypeToFilter, name, filter);

            if (slashpos > 0) {
                remove_all_objects(mBaseTypeToFilter, baseName, filter);
            } else {
                remove_all_objects(mWildTypeToFilter, baseName, filter);
            }
        }
        return num;
    }

    protected final int register_intent_filter(F filter, Iterator<String> i,
            ArrayMap<String, F[]> dest, String prefix) {
        if (i == null) {
            return 0;
        }

        int num = 0;
        while (i.hasNext()) {
            String name = i.next();
            num++;
            if (localLOGV) Slog.v(TAG, prefix + name);
            addFilter(dest, name, filter);
        }
        return num;
    }

    protected final int unregister_intent_filter(F filter, Iterator<String> i,
            ArrayMap<String, F[]> dest, String prefix) {
        if (i == null) {
            return 0;
        }

        int num = 0;
        while (i.hasNext()) {
            String name = i.next();
            num++;
            if (localLOGV) Slog.v(TAG, prefix + name);
            remove_all_objects(dest, name, filter);
        }
        return num;
    }

    private final void remove_all_objects(ArrayMap<String, F[]> map, String name,
            F object) {
        F[] array = map.get(name);
        if (array != null) {
            int LAST = array.length-1;
            while (LAST >= 0 && array[LAST] == null) {
                LAST--;
            }
            for (int idx=LAST; idx>=0; idx--) {
                F arrayValue = array[idx];
                if (arrayValue != null && getIntentFilter(arrayValue) == getIntentFilter(object)) {
                    final int remain = LAST - idx;
                    if (remain > 0) {
                        System.arraycopy(array, idx+1, array, idx, remain);
                    }
                    array[LAST] = null;
                    LAST--;
                }
            }
            if (LAST < 0) {
                map.remove(name);
            } else if (LAST < (array.length/2)) {
                F[] newa = newArray(LAST+2);
                System.arraycopy(array, 0, newa, 0, LAST+1);
                map.put(name, newa);
            }
        }
    }

    private static FastImmutableArraySet<String> getFastIntentCategories(Intent intent) {
        final Set<String> categories = intent.getCategories();
        if (categories == null) {
            return null;
        }
        return new FastImmutableArraySet<String>(categories.toArray(new String[categories.size()]));
    }

    private void buildResolveList(@NonNull Computer computer, Intent intent,
            FastImmutableArraySet<String> categories, boolean debug, boolean defaultOnly,
            String resolvedType, String scheme, F[] src, List<R> dest, int userId,
            long customFlags) {
        final String action = intent.getAction();
        final Uri data = intent.getData();
        final String packageName = intent.getPackage();

        final boolean excludingStopped = intent.isExcludingStopped();

        final Printer logPrinter;
        final PrintWriter logPrintWriter;
        if (debug) {
            logPrinter = new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM);
            logPrintWriter = new FastPrintWriter(logPrinter);
        } else {
            logPrinter = null;
            logPrintWriter = null;
        }

        final int N = src != null ? src.length : 0;
        boolean hasNonDefaults = false;
        int i;
        F filter;
        for (i=0; i<N && (filter=src[i]) != null; i++) {
            int match;
            if (debug) Slog.v(TAG, "Matching against filter " + filter);

            if (excludingStopped && isFilterStopped(computer, filter, userId)) {
                if (debug) {
                    Slog.v(TAG, "  Filter's target is stopped; skipping");
                }
                continue;
            }

            // Is delivery being limited to filters owned by a particular package?
            if (packageName != null && !isPackageForFilter(packageName, filter)) {
                if (debug) {
                    Slog.v(TAG, "  Filter is not from package " + packageName + "; skipping");
                }
                continue;
            }

            // Are we verified ?
            IntentFilter intentFilter = getIntentFilter(filter);
            if (intentFilter.getAutoVerify()) {
                if (localVerificationLOGV || debug) {
                    Slog.v(TAG, "  Filter verified: " + isFilterVerified(filter));
                    int authorities = intentFilter.countDataAuthorities();
                    for (int z = 0; z < authorities; z++) {
                        Slog.v(TAG, "   " + intentFilter.getDataAuthority(z)
                                .getHost());
                    }
                }
            }

            // Do we already have this one?
            if (!allowFilterResult(filter, dest)) {
                if (debug) {
                    Slog.v(TAG, "  Filter's target already added");
                }
                continue;
            }

            match = intentFilter.match(action, resolvedType, scheme, data, categories, TAG);
            if (match >= 0) {
                if (debug) Slog.v(TAG, "  Filter matched!  match=0x" +
                        Integer.toHexString(match) + " hasDefault="
                        + intentFilter.hasCategory(Intent.CATEGORY_DEFAULT));
                if (!defaultOnly || intentFilter.hasCategory(Intent.CATEGORY_DEFAULT)) {
                    final R oneResult = newResult(computer, filter, match, userId, customFlags);
                    if (debug) Slog.v(TAG, "    Created result: " + oneResult);
                    if (oneResult != null) {
                        dest.add(oneResult);
                        if (debug) {
                            dumpFilter(logPrintWriter, "    ", filter);
                            logPrintWriter.flush();
                            intentFilter.dump(logPrinter, "    ");
                        }
                    }
                } else {
                    hasNonDefaults = true;
                }
            } else {
                if (debug) {
                    String reason;
                    switch (match) {
                        case IntentFilter.NO_MATCH_ACTION: reason = "action"; break;
                        case IntentFilter.NO_MATCH_CATEGORY: reason = "category"; break;
                        case IntentFilter.NO_MATCH_DATA: reason = "data"; break;
                        case IntentFilter.NO_MATCH_TYPE: reason = "type"; break;
                        default: reason = "unknown reason"; break;
                    }
                    Slog.v(TAG, "  Filter did not match: " + reason);
                }
            }
        }

        if (debug && hasNonDefaults) {
            if (dest.size() == 0) {
                Slog.v(TAG, "resolveIntent failed: found match, but none with CATEGORY_DEFAULT");
            } else if (dest.size() > 1) {
                Slog.v(TAG, "resolveIntent: multiple matches, only some with CATEGORY_DEFAULT");
            }
        }
    }

    // Sorts a List of IntentFilter objects into descending priority order.
    @SuppressWarnings("rawtypes")
    private static final Comparator mResolvePrioritySorter = new Comparator() {
        public int compare(Object o1, Object o2) {
            final int q1 = ((IntentFilter) o1).getPriority();
            final int q2 = ((IntentFilter) o2).getPriority();
            return (q1 > q2) ? -1 : ((q1 < q2) ? 1 : 0);
        }
    };

    // Method to take the snapshot of an F.
    protected F snapshot(F f) {
        return f;
    }

    // Helper method to copy some of the maps.
    protected void copyInto(ArrayMap<String, F[]> l, ArrayMap<String, F[]> r) {
        final int end = r.size();
        l.clear();
        l.ensureCapacity(end);
        for (int i = 0; i < end; i++) {
            final F[] val = r.valueAt(i);
            final String key = r.keyAt(i);
            final F[] newval = Arrays.copyOf(val, val.length);
            for (int j = 0; j < newval.length; j++) {
                newval[j] = snapshot(newval[j]);
            }
            l.put(key, newval);
        }
    }

    protected void copyInto(ArraySet<F> l, ArraySet<F> r) {
        l.clear();
        final int end = r.size();
        l.ensureCapacity(end);
        for (int i = 0; i < end; i++) {
            l.append(snapshot(r.valueAt(i)));
        }
    }

    // Make <this> a copy of <orig>.  The presumption is that <this> is empty but all
    // arrays are cleared out explicitly, just to be sure.
    protected void copyFrom(IntentResolver orig) {
        copyInto(mFilters, orig.mFilters);
        copyInto(mTypeToFilter, orig.mTypeToFilter);
        copyInto(mBaseTypeToFilter, orig.mBaseTypeToFilter);
        copyInto(mWildTypeToFilter, orig.mWildTypeToFilter);
        copyInto(mSchemeToFilter, orig.mSchemeToFilter);
        copyInto(mActionToFilter, orig.mActionToFilter);
        copyInto(mTypedActionToFilter, orig.mTypedActionToFilter);
    }

    /**
     * All filters that have been registered.
     */
    protected final ArraySet<F> mFilters = new ArraySet<F>();

    /**
     * All of the MIME types that have been registered, such as "image/jpeg",
     * "image/*", or "{@literal *}/*".
     */
    private final ArrayMap<String, F[]> mTypeToFilter = new ArrayMap<String, F[]>();

    /**
     * The base names of all of all fully qualified MIME types that have been
     * registered, such as "image" or "*".  Wild card MIME types such as
     * "image/*" will not be here.
     */
    private final ArrayMap<String, F[]> mBaseTypeToFilter = new ArrayMap<String, F[]>();

    /**
     * The base names of all of the MIME types with a sub-type wildcard that
     * have been registered.  For example, a filter with "image/*" will be
     * included here as "image" but one with "image/jpeg" will not be
     * included here.  This also includes the "*" for the "{@literal *}/*"
     * MIME type.
     */
    private final ArrayMap<String, F[]> mWildTypeToFilter = new ArrayMap<String, F[]>();

    /**
     * All of the URI schemes (such as http) that have been registered.
     */
    private final ArrayMap<String, F[]> mSchemeToFilter = new ArrayMap<String, F[]>();

    /**
     * All of the actions that have been registered, but only those that did
     * not specify data.
     */
    private final ArrayMap<String, F[]> mActionToFilter = new ArrayMap<String, F[]>();

    /**
     * All of the actions that have been registered and specified a MIME type.
     */
    private final ArrayMap<String, F[]> mTypedActionToFilter = new ArrayMap<String, F[]>();

    /**
     * Rather than refactoring the entire class, this allows the input {@link F} to be a type
     * other than {@link IntentFilter}, transforming it whenever necessary. It is valid to use
     * {@link IntentFilter} directly as {@link F} and just return {@param input}.
     */
    protected abstract IntentFilter getIntentFilter(@NonNull F input);
}

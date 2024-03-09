/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.media;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.WorkerThread;
import android.app.Activity;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.StaleDataException;
import android.net.Uri;
import android.os.Environment;
import android.os.FileUtils;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.vibrator.Flags;
import android.os.vibrator.persistence.VibrationXmlParser;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio.AudioColumns;
import android.provider.MediaStore.MediaColumns;
import android.provider.Settings;
import android.provider.Settings.System;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.database.SortCursor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * RingtoneManager provides access to ringtones, notification, and other types
 * of sounds. It manages querying the different media providers and combines the
 * results into a single cursor. It also provides a {@link Ringtone} for each
 * ringtone. We generically call these sounds ringtones, however the
 * {@link #TYPE_RINGTONE} refers to the type of sounds that are suitable for the
 * phone ringer.
 * <p>
 * To show a ringtone picker to the user, use the
 * {@link #ACTION_RINGTONE_PICKER} intent to launch the picker as a subactivity.
 * 
 * @see Ringtone
 */
public class RingtoneManager {

    private static final String TAG = "RingtoneManager";

    // Make sure these are in sync with attrs.xml:
    // <attr name="ringtoneType">
    
    /**
     * Type that refers to sounds that are used for the phone ringer.
     */
    public static final int TYPE_RINGTONE = 1;
    
    /**
     * Type that refers to sounds that are used for notifications.
     */
    public static final int TYPE_NOTIFICATION = 2;
    
    /**
     * Type that refers to sounds that are used for the alarm.
     */
    public static final int TYPE_ALARM = 4;
    
    /**
     * All types of sounds.
     */
    public static final int TYPE_ALL = TYPE_RINGTONE | TYPE_NOTIFICATION | TYPE_ALARM;

    // </attr>
    
    /**
     * Activity Action: Shows a ringtone picker.
     * <p>
     * Input: {@link #EXTRA_RINGTONE_EXISTING_URI},
     * {@link #EXTRA_RINGTONE_SHOW_DEFAULT},
     * {@link #EXTRA_RINGTONE_SHOW_SILENT}, {@link #EXTRA_RINGTONE_TYPE},
     * {@link #EXTRA_RINGTONE_DEFAULT_URI}, {@link #EXTRA_RINGTONE_TITLE},
     * <p>
     * Output: {@link #EXTRA_RINGTONE_PICKED_URI}.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_RINGTONE_PICKER = "android.intent.action.RINGTONE_PICKER";

    /**
     * Given to the ringtone picker as a string that represents the category of ringtone picker that
     * should be used. This value should also be returned once a ringtone is selected.
     * <p>
     * The categories are:
     * <li>{@link #CATEGORY_RINGTONE_PICKER_SOUND}
     * <li>{@link #CATEGORY_RINGTONE_PICKER_VIBRATION}
     * <li>{@link #CATEGORY_RINGTONE_PICKER_RINGTONE}
     * <li>{@link Intent#CATEGORY_DEFAULT}
     *
     * <p> If the category is {@link Intent#CATEGORY_DEFAULT} or absent, then the picker will
     * default to a sound-only ringtone picker.
     *
     * <p> If the selected category was not supported, then the returned category will be null.
     *
     * @hide
     */
    public static final String EXTRA_RINGTONE_PICKER_CATEGORY =
            "android.intent.extra.ringtone.RINGTONE_PICKER_CATEGORY";

    /**
     * A sound-only ringtone picker.
     *
     * @hide
     * @see #EXTRA_RINGTONE_PICKER_CATEGORY
     */
    public static final String CATEGORY_RINGTONE_PICKER_SOUND =
            "android.net.category.RINGTONE_PICKER_SOUND";

    /**
     * A vibration-only ringtone picker.
     *
     * @hide
     * @see #EXTRA_RINGTONE_PICKER_CATEGORY
     */
    public static final String CATEGORY_RINGTONE_PICKER_VIBRATION =
            "android.net.category.RINGTONE_PICKER_VIBRATION";

    /**
     * A combined sound and vibration ringtone picker.
     *
     * @hide
     * @see #EXTRA_RINGTONE_PICKER_CATEGORY
     */
    public static final String CATEGORY_RINGTONE_PICKER_RINGTONE =
            "android.net.category.RINGTONE_PICKER_RINGTONE";

    /**
     * Given to the ringtone picker as a boolean. Whether to show an item for
     * "Default".
     * 
     * @see #ACTION_RINGTONE_PICKER
     */
    public static final String EXTRA_RINGTONE_SHOW_DEFAULT =
            "android.intent.extra.ringtone.SHOW_DEFAULT";
    
    /**
     * Given to the ringtone picker as a boolean. Whether to show an item for
     * "Silent". If the "Silent" item is picked,
     * {@link #EXTRA_RINGTONE_PICKED_URI} will be null.
     * 
     * @see #ACTION_RINGTONE_PICKER
     */
    public static final String EXTRA_RINGTONE_SHOW_SILENT =
            "android.intent.extra.ringtone.SHOW_SILENT";

    /**
     * Given to the ringtone picker as a boolean. Whether to include DRM ringtones.
     * @deprecated DRM ringtones are no longer supported
     */
    @Deprecated
    public static final String EXTRA_RINGTONE_INCLUDE_DRM =
            "android.intent.extra.ringtone.INCLUDE_DRM";
    
    /**
     * Given to the ringtone picker as a {@link Uri}. The {@link Uri} of the
     * current ringtone, which will be used to show a checkmark next to the item
     * for this {@link Uri}. If showing an item for "Default" (@see
     * {@link #EXTRA_RINGTONE_SHOW_DEFAULT}), this can also be one of
     * {@link System#DEFAULT_RINGTONE_URI},
     * {@link System#DEFAULT_NOTIFICATION_URI}, or
     * {@link System#DEFAULT_ALARM_ALERT_URI} to have the "Default" item
     * checked.
     * 
     * @see #ACTION_RINGTONE_PICKER
     */
    public static final String EXTRA_RINGTONE_EXISTING_URI =
            "android.intent.extra.ringtone.EXISTING_URI";

    /**
     * Similar to #EXTRA_RINGTONE_EXISTING_URI but the {@link Uri} can include both sound and
     * vibration.
     * <p>This can include silent sound/vibration explicitly by setting that part of the URI to
     * null.
     *
     * @hide
     * @see #ACTION_RINGTONE_PICKER
     */
    public static final String EXTRA_RINGTONE_EXISTING_RINGTONE_URI =
            "android.intent.extra.ringtone.RINGTONE_EXISTING_RINGTONE_URI";
    
    /**
     * Given to the ringtone picker as a {@link Uri}. The {@link Uri} of the
     * ringtone to play when the user attempts to preview the "Default"
     * ringtone. This can be one of {@link System#DEFAULT_RINGTONE_URI},
     * {@link System#DEFAULT_NOTIFICATION_URI}, or
     * {@link System#DEFAULT_ALARM_ALERT_URI} to have the "Default" point to
     * the current sound for the given default sound type. If you are showing a
     * ringtone picker for some other type of sound, you are free to provide any
     * {@link Uri} here.
     */
    public static final String EXTRA_RINGTONE_DEFAULT_URI =
            "android.intent.extra.ringtone.DEFAULT_URI";
    
    /**
     * Given to the ringtone picker as an int. Specifies which ringtone type(s) should be
     * shown in the picker. One or more of {@link #TYPE_RINGTONE},
     * {@link #TYPE_NOTIFICATION}, {@link #TYPE_ALARM}, or {@link #TYPE_ALL}
     * (bitwise-ored together).
     */
    public static final String EXTRA_RINGTONE_TYPE = "android.intent.extra.ringtone.TYPE";

    /**
     * Given to the ringtone picker as a {@link CharSequence}. The title to
     * show for the ringtone picker. This has a default value that is suitable
     * in most cases.
     */
    public static final String EXTRA_RINGTONE_TITLE = "android.intent.extra.ringtone.TITLE";

    /**
     * @hide
     * Given to the ringtone picker as an int. Additional AudioAttributes flags to use
     * when playing the ringtone in the picker.
     * @see #ACTION_RINGTONE_PICKER
     */
    public static final String EXTRA_RINGTONE_AUDIO_ATTRIBUTES_FLAGS =
            "android.intent.extra.ringtone.AUDIO_ATTRIBUTES_FLAGS";

    /**
     * Returned from the ringtone picker as a {@link Uri}.
     * <p>
     * It will be one of:
     * <li> the picked ringtone,
     * <li> a {@link Uri} that equals {@link System#DEFAULT_RINGTONE_URI},
     * {@link System#DEFAULT_NOTIFICATION_URI}, or
     * {@link System#DEFAULT_ALARM_ALERT_URI} if the default was chosen,
     * <li> null if the "Silent" item was picked.
     * 
     * @see #ACTION_RINGTONE_PICKER
     */
    public static final String EXTRA_RINGTONE_PICKED_URI =
            "android.intent.extra.ringtone.PICKED_URI";

    /**
     * Declares the allowed types of media for this RingtoneManager.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "MEDIA_", value = {
            Ringtone.MEDIA_SOUND,
            Ringtone.MEDIA_VIBRATION,
    })
    public @interface MediaType {}

    // Make sure the column ordering and then ..._COLUMN_INDEX are in sync
    
    private static final String[] MEDIA_AUDIO_COLUMNS = new String[] {
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.TITLE_KEY,
    };

    private static final String[] MEDIA_VIBRATION_COLUMNS = new String[]{
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.TITLE,
    };

    /**
     * The column index (in the cursor returned by {@link #getCursor()} for the
     * row ID.
     */
    public static final int ID_COLUMN_INDEX = 0;

    /**
     * The column index (in the cursor returned by {@link #getCursor()} for the
     * title.
     */
    public static final int TITLE_COLUMN_INDEX = 1;

    /**
     * The column index (in the cursor returned by {@link #getCursor()} for the
     * media provider's URI.
     */
    public static final int URI_COLUMN_INDEX = 2;

    private final Activity mActivity;
    private final Context mContext;

    @UnsupportedAppUsage
    private Cursor mCursor;

    private int mType = TYPE_RINGTONE;
    @MediaType
    private int mMediaType = Ringtone.MEDIA_SOUND;

    /**
     * If a column (item from this list) exists in the Cursor, its value must
     * be true (value of 1) for the row to be returned.
     */
    private final List<String> mFilterColumns = new ArrayList<String>();
    
    private boolean mStopPreviousRingtone = true;
    private Ringtone mPreviousRingtone;

    private boolean mIncludeParentRingtones;

    /**
     * Constructs a RingtoneManager. This constructor is recommended as its
     * constructed instance manages cursor(s).
     * 
     * @param activity The activity used to get a managed cursor.
     */
    public RingtoneManager(Activity activity) {
        this(activity, /* includeParentRingtones */ false);
    }

    /**
     * Constructs a RingtoneManager. This constructor is recommended if there's the need to also
     * list ringtones from the user's parent.
     *
     * @param activity The activity used to get a managed cursor.
     * @param includeParentRingtones if true, this ringtone manager's cursor will also retrieve
     *            ringtones from the parent of the user specified in the given activity
     *
     * @hide
     */
    public RingtoneManager(Activity activity, boolean includeParentRingtones) {
        mActivity = activity;
        mContext = activity;
        setType(mType);
        mIncludeParentRingtones = includeParentRingtones;
    }

    /**
     * Constructs a RingtoneManager. The instance constructed by this
     * constructor will not manage the cursor(s), so the client should handle
     * this itself.
     * 
     * @param context The context to used to get a cursor.
     */
    public RingtoneManager(Context context) {
        this(context, /* includeParentRingtones */ false);
    }

    /**
     * Constructs a RingtoneManager.
     *
     * @param context The context to used to get a cursor.
     * @param includeParentRingtones if true, this ringtone manager's cursor will also retrieve
     *            ringtones from the parent of the user specified in the given context
     *
     * @hide
     */
    public RingtoneManager(Context context, boolean includeParentRingtones) {
        mActivity = null;
        mContext = context;
        setType(mType);
        mIncludeParentRingtones = includeParentRingtones;
    }

    /**
     * Sets the media type that will be listed by the RingtoneManager.
     *
     * <p>This method should be called before calling {@link RingtoneManager#getCursor()}.
     *
     * @hide
     */
    public void setMediaType(@MediaType int mediaType) {
        if (mCursor != null) {
            throw new IllegalStateException(
                    "Setting media should be done before calling getCursor().");
        }

        switch (mediaType) {
            case Ringtone.MEDIA_SOUND:
            case Ringtone.MEDIA_VIBRATION:
                mMediaType = mediaType;
                break;
            default:
                throw new IllegalArgumentException("Unsupported media type " + mediaType);
        }
    }

    /**
     * Returns the RingtoneManagers media type.
     *
     * @return the media type.
     * @see #setMediaType
     * @hide
     */
    @MediaType
    public int getMediaType() {
        return mMediaType;
    }

    /**
     * Sets which type(s) of ringtones will be listed by this.
     * 
     * @param type The type(s), one or more of {@link #TYPE_RINGTONE},
     *            {@link #TYPE_NOTIFICATION}, {@link #TYPE_ALARM},
     *            {@link #TYPE_ALL}.
     * @see #EXTRA_RINGTONE_TYPE           
     */
    public void setType(int type) {
        if (mCursor != null) {
            throw new IllegalStateException(
                    "Setting filter columns should be done before querying for ringtones.");
        }
        
        mType = type;
        setFilterColumnsList(type);
    }

    /**
     * Infers the volume stream type based on what type of ringtones this
     * manager is returning.
     * 
     * @return The stream type.
     */
    public int inferStreamType() {
        switch (mType) {
            
            case TYPE_ALARM:
                return AudioManager.STREAM_ALARM;
                
            case TYPE_NOTIFICATION:
                return AudioManager.STREAM_NOTIFICATION;
                
            default:
                return AudioManager.STREAM_RING;
        }
    }

    /** @hide */
    @NonNull
    public static AudioAttributes getDefaultAudioAttributes(int ringtoneType) {
        AudioAttributes.Builder builder = new AudioAttributes.Builder();
        switch (ringtoneType) {
            case TYPE_ALARM:
                builder.setUsage(AudioAttributes.USAGE_ALARM);
                break;
            case TYPE_NOTIFICATION:
                builder.setUsage(AudioAttributes.USAGE_NOTIFICATION);
                break;
            default:  // ringtone or all
                builder.setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE);
                break;
        }
        builder.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION);
        return builder.build();
    }

    /**
     * Whether retrieving another {@link Ringtone} will stop playing the
     * previously retrieved {@link Ringtone}.
     * <p>
     * If this is false, make sure to {@link Ringtone#stop()} any previous
     * ringtones to free resources.
     * 
     * @param stopPreviousRingtone If true, the previously retrieved
     *            {@link Ringtone} will be stopped.
     */
    public void setStopPreviousRingtone(boolean stopPreviousRingtone) {
        mStopPreviousRingtone = stopPreviousRingtone;
    }

    /**
     * @see #setStopPreviousRingtone(boolean)
     */
    public boolean getStopPreviousRingtone() {
        return mStopPreviousRingtone;
    }

    /**
     * Stops playing the last {@link Ringtone} retrieved from this.
     */
    public void stopPreviousRingtone() {
        if (mPreviousRingtone != null) {
            mPreviousRingtone.stop();
        }
    }
    
    /**
     * Returns whether DRM ringtones will be included.
     * 
     * @return Whether DRM ringtones will be included.
     * @see #setIncludeDrm(boolean)
     * Obsolete - always returns false
     * @deprecated DRM ringtones are no longer supported
     */
    @Deprecated
    public boolean getIncludeDrm() {
        return false;
    }

    /**
     * Sets whether to include DRM ringtones.
     * 
     * @param includeDrm Whether to include DRM ringtones.
     * Obsolete - no longer has any effect
     * @deprecated DRM ringtones are no longer supported
     */
    @Deprecated
    public void setIncludeDrm(boolean includeDrm) {
        if (includeDrm) {
            Log.w(TAG, "setIncludeDrm no longer supported");
        }
    }

    /**
     * Returns a {@link Cursor} of all the ringtones available. The returned
     * cursor will be the same cursor returned each time this method is called,
     * so do not {@link Cursor#close()} the cursor. The cursor can be
     * {@link Cursor#deactivate()} safely.
     * <p>
     * If {@link RingtoneManager#RingtoneManager(Activity)} was not used, the
     * caller should manage the returned cursor through its activity's life
     * cycle to prevent leaking the cursor.
     * <p>
     * Note that the list of ringtones available will differ depending on whether the caller
     * has the {@link android.Manifest.permission#READ_EXTERNAL_STORAGE} permission.
     *
     * @return A {@link Cursor} of all the ringtones available.
     * @see #ID_COLUMN_INDEX
     * @see #TITLE_COLUMN_INDEX
     * @see #URI_COLUMN_INDEX
     */
    public Cursor getCursor() {
        if (mCursor != null && mCursor.requery()) {
            return mCursor;
        }

        ArrayList<Cursor> cursors = new ArrayList<>();

        cursors.add(queryMediaStore(/* internal= */ true));
        cursors.add(queryMediaStore(/* internal= */ false));

        if (mIncludeParentRingtones) {
            Cursor parentRingtonesCursor = getParentProfileRingtones();
            if (parentRingtonesCursor != null) {
                cursors.add(parentRingtonesCursor);
            }
        }
        return mCursor = new SortCursor(cursors.toArray(new Cursor[cursors.size()]),
                getSortOrderForMedia(mMediaType));
    }

    private Cursor getParentProfileRingtones() {
        final UserManager um = UserManager.get(mContext);
        final UserInfo parentInfo = um.getProfileParent(mContext.getUserId());
        if (parentInfo != null && parentInfo.id != mContext.getUserId()) {
            final Context parentContext = createPackageContextAsUser(mContext, parentInfo.id);
            if (parentContext != null) {
                // We don't need to re-add the internal ringtones for the work profile since
                // they are the same as the personal profile. We just need the external
                // ringtones.
                return queryMediaStore(parentContext, /* internal= */ false);
            }
        }
        return null;
    }

    /**
     * Gets a {@link Ringtone} for the ringtone at the given position in the
     * {@link Cursor}.
     * 
     * @param position The position (in the {@link Cursor}) of the ringtone.
     * @return A {@link Ringtone} pointing to the ringtone.
     */
    public Ringtone getRingtone(int position) {
        if (mStopPreviousRingtone && mPreviousRingtone != null) {
            mPreviousRingtone.stop();
        }

        Ringtone ringtone;
        Uri positionUri = getRingtoneUri(position);
        if (Flags.hapticsCustomizationRingtoneV2Enabled()) {
            mPreviousRingtone = new Ringtone.Builder(
                    mContext, mMediaType, getDefaultAudioAttributes(mType))
                    .setUri(positionUri)
                    .build();
        } else {
            mPreviousRingtone = createRingtoneV1WithStreamType(mContext, positionUri,
                    inferStreamType(), /* volumeShaperConfig= */ null);
        }
        return mPreviousRingtone;
    }

    private static Ringtone createRingtoneV1WithStreamType(
            final Context context, Uri ringtoneUri, int streamType,
            @Nullable VolumeShaper.Configuration volumeShaperConfig) {
        try {
            return Ringtone.createV1WithCustomStreamType(context, streamType, ringtoneUri,
                    volumeShaperConfig);
        } catch (Exception ex) {
            Log.e(TAG, "Failed to open ringtone " + ringtoneUri + ": " + ex);
        }
        return null;
    }

    /**
     * Gets a {@link Uri} for the ringtone at the given position in the {@link Cursor}.
     * 
     * @param position The position (in the {@link Cursor}) of the ringtone.
     * @return A {@link Uri} pointing to the ringtone.
     */
    public Uri getRingtoneUri(int position) {
        // use cursor directly instead of requerying it, which could easily
        // cause position to shuffle.
        try {
            if (mCursor == null || !mCursor.moveToPosition(position)) {
                return null;
            }
        } catch (StaleDataException | IllegalStateException e) {
            Log.e(TAG, "Unexpected Exception has been catched.", e);
            return null;
        }

        return getUriFromCursor(mContext, mCursor);
    }

    /**
     * Gets the valid ringtone uri by a given uri string and ringtone type for the restore purpose.
     *
     * @param contentResolver ContentResolver to execute media query.
     * @param value a canonicalized uri which refers to the ringtone.
     * @param ringtoneType an integer representation of the kind of uri that is being restored, can
     *     be RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION, or
     *     RingtoneManager.TYPE_ALARM.
     * @hide
     */
    public static @Nullable Uri getRingtoneUriForRestore(
            @NonNull ContentResolver contentResolver, @Nullable String value, int ringtoneType)
            throws FileNotFoundException, IllegalArgumentException {
        if (value == null) {
            // Return a valid null. It means the null value is intended instead of a failure.
            return null;
        }

        Uri ringtoneUri;
        final Uri canonicalUri = Uri.parse(value);

        // Try to get the media uri via the regular uncanonicalize method first.
        ringtoneUri = contentResolver.uncanonicalize(canonicalUri);
        if (ringtoneUri != null) {
            // Canonicalize it to make the result contain the right metadata of the media asset.
            ringtoneUri = contentResolver.canonicalize(ringtoneUri);
            return ringtoneUri;
        }

        // Query the media by title and ringtone type.
        final String title = canonicalUri.getQueryParameter(AudioColumns.TITLE);
        Uri baseUri = ContentUris.removeId(canonicalUri).buildUpon().clearQuery().build();
        String ringtoneTypeSelection = "";
        switch (ringtoneType) {
            case RingtoneManager.TYPE_RINGTONE:
                ringtoneTypeSelection = MediaStore.Audio.AudioColumns.IS_RINGTONE;
                break;
            case RingtoneManager.TYPE_NOTIFICATION:
                ringtoneTypeSelection = MediaStore.Audio.AudioColumns.IS_NOTIFICATION;
                break;
            case RingtoneManager.TYPE_ALARM:
                ringtoneTypeSelection = MediaStore.Audio.AudioColumns.IS_ALARM;
                break;
            default:
                throw new IllegalArgumentException("Unknown ringtone type: " + ringtoneType);
        }

        final String selection = ringtoneTypeSelection + "=1 AND " + AudioColumns.TITLE + "=?";
        Cursor cursor = null;
        try {
            cursor =
                    contentResolver.query(
                            baseUri,
                            /* projection */ new String[] {BaseColumns._ID},
                            /* selection */ selection,
                            /* selectionArgs */ new String[] {title},
                            /* sortOrder */ null,
                            /* cancellationSignal */ null);

        } catch (IllegalArgumentException e) {
            throw new FileNotFoundException("Volume not found for " + baseUri);
        }
        if (cursor == null) {
            throw new FileNotFoundException("Missing cursor for " + baseUri);
        } else if (cursor.getCount() == 0) {
            FileUtils.closeQuietly(cursor);
            throw new FileNotFoundException("No item found for " + baseUri);
        } else if (cursor.getCount() > 1) {
            // Find more than 1 result.
            // We are not sure which one is the right ringtone file so just abandon this case.
            FileUtils.closeQuietly(cursor);
            throw new FileNotFoundException(
                    "Find multiple ringtone candidates by title+ringtone_type query: count: "
                            + cursor.getCount());
        }
        if (cursor.moveToFirst()) {
            ringtoneUri = ContentUris.withAppendedId(baseUri, cursor.getLong(0));
            FileUtils.closeQuietly(cursor);
        } else {
            FileUtils.closeQuietly(cursor);
            throw new FileNotFoundException("Failed to read row from the result.");
        }

        // Canonicalize it to make the result contain the right metadata of the media asset.
        ringtoneUri = contentResolver.canonicalize(ringtoneUri);
        Log.v(TAG, "Find a valid result: " + ringtoneUri);
        return ringtoneUri;
    }

    private static Uri getUriFromCursor(Context context, Cursor cursor) {
        final Uri uri = ContentUris.withAppendedId(Uri.parse(cursor.getString(URI_COLUMN_INDEX)),
                cursor.getLong(ID_COLUMN_INDEX));
        return context.getContentResolver().canonicalizeOrElse(uri);
    }

    /**
     * Gets the position of a {@link Uri} within this {@link RingtoneManager}.
     * 
     * @param ringtoneUri The {@link Uri} to retreive the position of.
     * @return The position of the {@link Uri}, or -1 if it cannot be found.
     */
    public int getRingtonePosition(Uri ringtoneUri) {
        try {
            if (ringtoneUri == null) return -1;

            final Cursor cursor = getCursor();
            cursor.moveToPosition(-1);
            while (cursor.moveToNext()) {
                Uri uriFromCursor = getUriFromCursor(mContext, cursor);
                if (ringtoneUri.equals(uriFromCursor)) {
                    return cursor.getPosition();
                }
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "NumberFormatException while getting ringtone position, returning -1", e);
        }
        return -1;
    }

    /**
     * Returns a valid ringtone URI. No guarantees on which it returns. If it
     * cannot find one, returns null. If it can only find one on external storage and the caller
     * doesn't have the {@link android.Manifest.permission#READ_EXTERNAL_STORAGE} permission,
     * returns null.
     *
     * @param context The context to use for querying.
     * @return A ringtone URI, or null if one cannot be found.
     */
    public static Uri getValidRingtoneUri(Context context) {
        final RingtoneManager rm = new RingtoneManager(context);

        Uri uri = getValidRingtoneUriFromCursorAndClose(context,
                rm.queryMediaStore(/* internal= */ true));

        if (uri == null) {
            uri = getValidRingtoneUriFromCursorAndClose(context,
                    rm.queryMediaStore(/* internal= */ false));
        }
        
        return uri;
    }
    
    private static Uri getValidRingtoneUriFromCursorAndClose(Context context, Cursor cursor) {
        if (cursor != null) {
            Uri uri = null;
            
            if (cursor.moveToFirst()) {
                uri = getUriFromCursor(context, cursor);
            }
            cursor.close();
            
            return uri;
        } else {
            return null;
        }
    }

    private Cursor queryMediaStore(boolean internal) {
        return queryMediaStore(mContext, internal);
    }

    private Cursor queryMediaStore(Context context, boolean internal) {
        Uri contentUri = getContentUriForMedia(mMediaType, internal);
        String[] columns =
                mMediaType == Ringtone.MEDIA_VIBRATION ? MEDIA_VIBRATION_COLUMNS
                        : MEDIA_AUDIO_COLUMNS;
        String whereClause = getWhereClauseForMedia(mMediaType, mFilterColumns);
        String sortOrder = getSortOrderForMedia(mMediaType);

        Cursor cursor = query(contentUri, columns, whereClause, /* selectionArgs= */ null,
                sortOrder, context);

        if (context.getUserId() != mContext.getUserId()) {
            contentUri = ContentProvider.maybeAddUserId(contentUri, context.getUserId());
        }

        return new ExternalRingtonesCursorWrapper(cursor, contentUri);
    }

    private void setFilterColumnsList(int type) {
        List<String> columns = mFilterColumns;
        columns.clear();
        
        if ((type & TYPE_RINGTONE) != 0) {
            columns.add(MediaStore.Audio.AudioColumns.IS_RINGTONE);
        }
        
        if ((type & TYPE_NOTIFICATION) != 0) {
            columns.add(MediaStore.Audio.AudioColumns.IS_NOTIFICATION);
        }
        
        if ((type & TYPE_ALARM) != 0) {
            columns.add(MediaStore.Audio.AudioColumns.IS_ALARM);
        }
    }

    /**
     * Returns the sort order for the specified media.
     *
     * @param media The RingtoneManager media type.
     * @return The sort order column.
     */
    private static String getSortOrderForMedia(@MediaType int media) {
        return media == Ringtone.MEDIA_VIBRATION ? MediaStore.Files.FileColumns.TITLE
                : MediaStore.Audio.Media.DEFAULT_SORT_ORDER;
    }

    /**
     * Returns the content URI based on the specified media and whether it's internal or external
     * storage.
     *
     * @param media    The RingtoneManager media type.
     * @param internal Whether it's for internal or external storage.
     * @return The media content URI.
     */
    private static Uri getContentUriForMedia(@MediaType int media, boolean internal) {
        switch (media) {
            case Ringtone.MEDIA_VIBRATION:
                return MediaStore.Files.getContentUri(
                        internal ? MediaStore.VOLUME_INTERNAL : MediaStore.VOLUME_EXTERNAL);
            case Ringtone.MEDIA_SOUND:
                return internal ? MediaStore.Audio.Media.INTERNAL_CONTENT_URI
                        : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            default:
                throw new IllegalArgumentException("Unsupported media type " + media);
        }
    }

    /**
     * Constructs a where clause based on the media type. This will be used to find all matching
     * sound or vibration files.
     *
     * @param media   The RingtoneManager media type.
     * @param columns The columns that must be true, when media type is {@link Ringtone#MEDIA_SOUND}
     * @return The where clause.
     */
    private static String getWhereClauseForMedia(@MediaType int media, List<String> columns) {
        // TODO(b/296213309): Filtering by ringtone-type isn't supported yet for vibrations.
        if (media == Ringtone.MEDIA_VIBRATION) {
            return TextUtils.formatSimple("(%s='%s')", MediaStore.Files.FileColumns.MIME_TYPE,
                    VibrationXmlParser.APPLICATION_VIBRATION_XML_MIME_TYPE);
        }

        return constructBooleanTrueWhereClause(columns);
    }
    
    /**
     * Constructs a where clause that consists of at least one column being 1
     * (true). This is used to find all matching sounds for the given sound
     * types (ringtone, notifications, etc.)
     * 
     * @param columns The columns that must be true.
     * @return The where clause.
     */
    private static String constructBooleanTrueWhereClause(List<String> columns) {
        
        if (columns == null) return null;
        
        StringBuilder sb = new StringBuilder();
        sb.append("(");

        for (int i = columns.size() - 1; i >= 0; i--) {
            sb.append(columns.get(i)).append("=1 or ");
        }
        
        if (columns.size() > 0) {
            // Remove last ' or '
            sb.setLength(sb.length() - 4);
        }

        sb.append(")");

        return sb.toString();
    }

    private Cursor query(Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder,
            Context context) {
        if (mActivity != null) {
            return mActivity.managedQuery(uri, projection, selection, selectionArgs, sortOrder);
        } else {
            return context.getContentResolver().query(uri, projection, selection, selectionArgs,
                    sortOrder);
        }
    }
    
    /**
     * Returns a {@link Ringtone} for a given sound URI.
     * <p>
     * If the given URI cannot be opened for any reason, this method will
     * attempt to fallback on another sound. If it cannot find any, it will
     * return null.
     * 
     * @param context A context used to query.
     * @param ringtoneUri The {@link Uri} of a sound or ringtone.
     * @return A {@link Ringtone} for the given URI, or null.
     */
    public static Ringtone getRingtone(final Context context, Uri ringtoneUri) {
        if (Flags.hapticsCustomizationRingtoneV2Enabled()) {
            return new Ringtone.Builder(
                    context, Ringtone.MEDIA_SOUND, getDefaultAudioAttributes(-1))
                    .setUri(ringtoneUri)
                    .build();
        } else {
            return createRingtoneV1WithStreamType(context, ringtoneUri, -1, null);
        }
    }

    /**
     * @hide
     */
    public static Ringtone getRingtone(final Context context, Uri ringtoneUri,
            @Nullable VolumeShaper.Configuration volumeShaperConfig,
            AudioAttributes audioAttributes) {
        // TODO: move caller(s) away from this method: inline the builder call.
        if (Flags.hapticsCustomizationRingtoneV2Enabled()) {
            return new Ringtone.Builder(context, Ringtone.MEDIA_SOUND, audioAttributes)
                    .setUri(ringtoneUri)
                    .setVolumeShaperConfig(volumeShaperConfig)
                    .setUseExactAudioAttributes(true)  // May be using audio-coupled via attrs
                    .build();
        } else {
            try {
                return Ringtone.createV1WithCustomAudioAttributes(context, audioAttributes,
                        ringtoneUri, volumeShaperConfig, /* allowRemote= */ true);
            } catch (Exception ex) {
                // Match broad catching of createRingtoneV1.
                Log.e(TAG, "Failed to open ringtone " + ringtoneUri + ": " + ex);
                return null;
            }
        }
    }

    /**
     * Gets the current default sound's {@link Uri}. This will give the actual
     * sound {@link Uri}, instead of using this, most clients can use
     * {@link System#DEFAULT_RINGTONE_URI}.
     * 
     * @param context A context used for querying.
     * @param type The type whose default sound should be returned. One of
     *            {@link #TYPE_RINGTONE}, {@link #TYPE_NOTIFICATION}, or
     *            {@link #TYPE_ALARM}.
     * @return A {@link Uri} pointing to the default sound for the sound type.
     * @see #setActualDefaultRingtoneUri(Context, int, Uri)
     */
    public static Uri getActualDefaultRingtoneUri(Context context, int type) {
        String setting = getSettingForType(type);
        if (setting == null) return null;
        final String uriString = Settings.System.getStringForUser(context.getContentResolver(),
                setting, context.getUserId());
        Uri ringtoneUri = uriString != null ? Uri.parse(uriString) : null;

        // If this doesn't verify, the user id must be kept in the uri to ensure it resolves in the
        // correct user storage
        if (ringtoneUri != null
                && ContentProvider.getUserIdFromUri(ringtoneUri) == context.getUserId()) {
            ringtoneUri = ContentProvider.getUriWithoutUserId(ringtoneUri);
        }

        return ringtoneUri;
    }
    
    /**
     * Sets the {@link Uri} of the default sound for a given sound type.
     * 
     * @param context A context used for querying.
     * @param type The type whose default sound should be set. One of
     *            {@link #TYPE_RINGTONE}, {@link #TYPE_NOTIFICATION}, or
     *            {@link #TYPE_ALARM}.
     * @param ringtoneUri A {@link Uri} pointing to the default sound to set.
     * @see #getActualDefaultRingtoneUri(Context, int)
     */
    public static void setActualDefaultRingtoneUri(Context context, int type, Uri ringtoneUri) {
        String setting = getSettingForType(type);
        if (setting == null) return;

        final ContentResolver resolver = context.getContentResolver();
        if(!isInternalRingtoneUri(ringtoneUri)) {
            ringtoneUri = ContentProvider.maybeAddUserId(ringtoneUri, context.getUserId());
        }

        if (ringtoneUri != null) {
            final String mimeType = resolver.getType(ringtoneUri);
            if (mimeType == null) {
                Log.e(TAG, "setActualDefaultRingtoneUri for URI:" + ringtoneUri
                        + " ignored: failure to find mimeType (no access from this context?)");
                return;
            }
            if (!(mimeType.startsWith("audio/") || mimeType.equals("application/ogg"))) {
                Log.e(TAG, "setActualDefaultRingtoneUri for URI:" + ringtoneUri
                        + " ignored: associated mimeType:" + mimeType + " is not an audio type");
                return;
            }
        }

        Settings.System.putStringForUser(resolver, setting,
                ringtoneUri != null ? ringtoneUri.toString() : null, context.getUserId());
    }

    private static boolean isInternalRingtoneUri(Uri uri) {
        return isRingtoneUriInStorage(uri, MediaStore.Audio.Media.INTERNAL_CONTENT_URI);
    }

    private static boolean isExternalRingtoneUri(Uri uri) {
        return isRingtoneUriInStorage(uri, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI);
    }

    private static boolean isRingtoneUriInStorage(Uri ringtone, Uri storage) {
        Uri uriWithoutUserId = ContentProvider.getUriWithoutUserId(ringtone);
        return uriWithoutUserId == null ? false
                : uriWithoutUserId.toString().startsWith(storage.toString());
    }

    /**
     * Adds an audio file to the list of ringtones.
     *
     * After making sure the given file is an audio file, copies the file to the ringtone storage,
     * and asks the system to scan that file. This call will block until
     * the scan is completed.
     *
     * The directory where the copied file is stored is the directory that matches the ringtone's
     * type, which is one of: {@link android.is.Environment#DIRECTORY_RINGTONES};
     * {@link android.is.Environment#DIRECTORY_NOTIFICATIONS};
     * {@link android.is.Environment#DIRECTORY_ALARMS}.
     *
     * This does not allow modifying the type of an existing ringtone file. To change type, use the
     * APIs in {@link android.content.ContentResolver} to update the corresponding columns.
     *
     * @param fileUri Uri of the file to be added as ringtone. Must be a media file.
     * @param type The type of the ringtone to be added. Must be one of {@link #TYPE_RINGTONE},
     *            {@link #TYPE_NOTIFICATION}, or {@link #TYPE_ALARM}.
     *
     * @return The Uri of the installed ringtone, which may be the Uri of {@param fileUri} if it is
     *         already in ringtone storage.
     *
     * @throws FileNotFoundexception if an appropriate unique filename to save the new ringtone file
     *         as cannot be found, for example if the unique name is too long.
     * @throws IllegalArgumentException if {@param fileUri} does not point to an existing audio
     *         file, or if the {@param type} is not one of the accepted ringtone types.
     * @throws IOException if the audio file failed to copy to ringtone storage; for example, if
     *         external storage was not available, or if the file was copied but the media scanner
     *         did not recognize it as a ringtone.
     *
     * @hide
     */
    @WorkerThread
    public Uri addCustomExternalRingtone(@NonNull final Uri fileUri, final int type)
            throws FileNotFoundException, IllegalArgumentException, IOException {
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            throw new IOException("External storage is not mounted. Unable to install ringtones.");
        }

        // Consistency-check: are we actually being asked to install an audio file?
        final String mimeType = mContext.getContentResolver().getType(fileUri);
        if(mimeType == null ||
                !(mimeType.startsWith("audio/") || mimeType.equals("application/ogg"))) {
            throw new IllegalArgumentException("Ringtone file must have MIME type \"audio/*\"."
                    + " Given file has MIME type \"" + mimeType + "\"");
        }

        // Choose a directory to save the ringtone. Only one type of installation at a time is
        // allowed. Throws IllegalArgumentException if anything else is given.
        final String subdirectory = getExternalDirectoryForType(type);

        // Find a filename. Throws FileNotFoundException if none can be found.
        final File outFile = Utils.getUniqueExternalFile(mContext, subdirectory,
                FileUtils.buildValidFatFilename(Utils.getFileDisplayNameFromUri(mContext, fileUri)),
                        mimeType);

        // Copy contents to external ringtone storage. Throws IOException if the copy fails.
        try (final InputStream input = mContext.getContentResolver().openInputStream(fileUri);
                final OutputStream output = new FileOutputStream(outFile)) {
            FileUtils.copy(input, output);
        }

        // Tell MediaScanner about the new file. Wait for it to assign a {@link Uri}.
        return MediaStore.scanFile(mContext.getContentResolver(), outFile);
    }

    private static final String getExternalDirectoryForType(final int type) {
        switch (type) {
            case TYPE_RINGTONE:
                return Environment.DIRECTORY_RINGTONES;
            case TYPE_NOTIFICATION:
                return Environment.DIRECTORY_NOTIFICATIONS;
            case TYPE_ALARM:
                return Environment.DIRECTORY_ALARMS;
            default:
                throw new IllegalArgumentException("Unsupported ringtone type: " + type);
        }
    }

    private static String getSettingForType(int type) {
        if ((type & TYPE_RINGTONE) != 0) {
            return Settings.System.RINGTONE;
        } else if ((type & TYPE_NOTIFICATION) != 0) {
            return Settings.System.NOTIFICATION_SOUND;
        } else if ((type & TYPE_ALARM) != 0) {
            return Settings.System.ALARM_ALERT;
        } else {
            return null;
        }
    }

    /** {@hide} */
    public static Uri getCacheForType(int type) {
        return getCacheForType(type, UserHandle.getCallingUserId());
    }

    /** {@hide} */
    public static Uri getCacheForType(int type, int userId) {
        if ((type & TYPE_RINGTONE) != 0) {
            return ContentProvider.maybeAddUserId(Settings.System.RINGTONE_CACHE_URI, userId);
        } else if ((type & TYPE_NOTIFICATION) != 0) {
            return ContentProvider.maybeAddUserId(Settings.System.NOTIFICATION_SOUND_CACHE_URI,
                    userId);
        } else if ((type & TYPE_ALARM) != 0) {
            return ContentProvider.maybeAddUserId(Settings.System.ALARM_ALERT_CACHE_URI, userId);
        }
        return null;
    }

    /**
     * Returns whether the given {@link Uri} is one of the default ringtones.
     * 
     * @param ringtoneUri The ringtone {@link Uri} to be checked.
     * @return Whether the {@link Uri} is a default.
     */
    public static boolean isDefault(Uri ringtoneUri) {
        return getDefaultType(ringtoneUri) != -1;
    }
    
    /**
     * Returns the type of a default {@link Uri}.
     * 
     * @param defaultRingtoneUri The default {@link Uri}. For example,
     *            {@link System#DEFAULT_RINGTONE_URI},
     *            {@link System#DEFAULT_NOTIFICATION_URI}, or
     *            {@link System#DEFAULT_ALARM_ALERT_URI}.
     * @return The type of the defaultRingtoneUri, or -1.
     */
    public static int getDefaultType(Uri defaultRingtoneUri) {
        defaultRingtoneUri = ContentProvider.getUriWithoutUserId(defaultRingtoneUri);
        if (defaultRingtoneUri == null) {
            return -1;
        } else if (defaultRingtoneUri.equals(Settings.System.DEFAULT_RINGTONE_URI)) {
            return TYPE_RINGTONE;
        } else if (defaultRingtoneUri.equals(Settings.System.DEFAULT_NOTIFICATION_URI)) {
            return TYPE_NOTIFICATION;
        } else if (defaultRingtoneUri.equals(Settings.System.DEFAULT_ALARM_ALERT_URI)) {
            return TYPE_ALARM;
        } else {
            return -1;
        }
    }
 
    /**
     * Returns the {@link Uri} for the default ringtone of a particular type.
     * Rather than returning the actual ringtone's sound {@link Uri}, this will
     * return the symbolic {@link Uri} which will resolved to the actual sound
     * when played.
     * 
     * @param type The ringtone type whose default should be returned.
     * @return The {@link Uri} of the default ringtone for the given type.
     */
    public static Uri getDefaultUri(int type) {
        if ((type & TYPE_RINGTONE) != 0) {
            return Settings.System.DEFAULT_RINGTONE_URI;
        } else if ((type & TYPE_NOTIFICATION) != 0) {
            return Settings.System.DEFAULT_NOTIFICATION_URI;
        } else if ((type & TYPE_ALARM) != 0) {
            return Settings.System.DEFAULT_ALARM_ALERT_URI;
        } else {
            return null;
        }
    }

    /**
     * Opens a raw file descriptor to read the data under the given default URI.
     *
     * @param context the Context to use when resolving the Uri.
     * @param uri The desired default URI to open.
     * @return a new AssetFileDescriptor pointing to the file. You own this descriptor
     * and are responsible for closing it when done. This value may be {@code null}.
     * @throws FileNotFoundException if the provided URI could not be opened.
     * @see #getDefaultUri
     */
    public static @Nullable AssetFileDescriptor openDefaultRingtoneUri(
            @NonNull Context context, @NonNull Uri uri) throws FileNotFoundException {
        // Try cached ringtone first since the actual provider may not be
        // encryption aware, or it may be stored on CE media storage
        final int type = getDefaultType(uri);
        final Uri cacheUri = getCacheForType(type, context.getUserId());
        final Uri actualUri = getActualDefaultRingtoneUri(context, type);
        final ContentResolver resolver = context.getContentResolver();

        AssetFileDescriptor afd = null;
        if (cacheUri != null) {
            afd = resolver.openAssetFileDescriptor(cacheUri, "r");
            if (afd != null) {
                return afd;
            }
        }
        if (actualUri != null) {
            afd = resolver.openAssetFileDescriptor(actualUri, "r");
        }
        return afd;
    }

    /**
     * Returns if the {@link Ringtone} at the given position in the
     * {@link Cursor} contains haptic channels.
     *
     * @param position The position (in the {@link Cursor}) of the ringtone.
     * @return true if the ringtone contains haptic channels.
     */
    public boolean hasHapticChannels(int position) {
        return AudioManager.hasHapticChannels(mContext, getRingtoneUri(position));
    }

    /**
     * Returns if the {@link Ringtone} from a given sound URI contains
     * haptic channels or not. As this function doesn't has a context
     * to resolve the uri, the result may be wrong if the uri cannot be
     * resolved correctly.
     * Use {@link #hasHapticChannels(int)} or {@link #hasHapticChannels(Context, Uri)}
     * instead when possible.
     *
     * @param ringtoneUri The {@link Uri} of a sound or ringtone.
     * @return true if the ringtone contains haptic channels.
     */
    public static boolean hasHapticChannels(@NonNull Uri ringtoneUri) {
        return AudioManager.hasHapticChannels(null, ringtoneUri);
    }

    /**
     * Returns if the {@link Ringtone} from a given sound URI contains haptics channels or not.
     *
     * @param context the {@link android.content.Context} to use when resolving the Uri.
     * @param ringtoneUri the {@link Uri} of a sound or ringtone.
     * @return true if the ringtone contains haptic channels.
     */
    public static boolean hasHapticChannels(@NonNull Context context, @NonNull Uri ringtoneUri) {
        return AudioManager.hasHapticChannels(context, ringtoneUri);
    }

    /**
     * Attempts to create a context for the given user.
     *
     * @return created context, or null if package does not exist
     * @hide
     */
    private static Context createPackageContextAsUser(Context context, int userId) {
        try {
            return context.createPackageContextAsUser(context.getPackageName(), 0 /* flags */,
                    UserHandle.of(userId));
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Unable to create package context", e);
            return null;
        }
    }

    /**
     * Ensure that ringtones have been set at least once on this device. This
     * should be called after the device has finished scanned all media on
     * {@link MediaStore#VOLUME_INTERNAL}, so that default ringtones can be
     * configured.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WRITE_SETTINGS)
    public static void ensureDefaultRingtones(@NonNull Context context) {
        for (int type : new int[] {
                TYPE_RINGTONE,
                TYPE_NOTIFICATION,
                TYPE_ALARM,
        }) {
            // Skip if we've already defined it at least once, so we don't
            // overwrite the user changing to null
            final String setting = getDefaultRingtoneSetting(type);
            if (Settings.System.getInt(context.getContentResolver(), setting, 0) != 0) {
                continue;
            }

            // Try finding the scanned ringtone
            Uri ringtoneUri = computeDefaultRingtoneUri(context, type);
            if (ringtoneUri != null) {
                RingtoneManager.setActualDefaultRingtoneUri(context, type, ringtoneUri);
                Settings.System.putInt(context.getContentResolver(), setting, 1);
            }
        }
    }

    /**
     * @param type the type of ringtone (e.g {@link #TYPE_RINGTONE})
     * @return the system default URI if found, null otherwise.
     */
    private static Uri computeDefaultRingtoneUri(@NonNull Context context, int type) {
        // Try finding the scanned ringtone
        final String filename = getDefaultRingtoneFilename(type);
        final String whichAudio = getQueryStringForType(type);
        final String where = MediaColumns.DISPLAY_NAME + "=? AND " + whichAudio + "=?";
        final Uri baseUri = MediaStore.Audio.Media.INTERNAL_CONTENT_URI;
        try (Cursor cursor = context.getContentResolver().query(baseUri,
                new String[] { MediaColumns._ID },
                where,
                new String[] { filename, "1" }, null)) {
            if (cursor.moveToFirst()) {
                final Uri ringtoneUri = context.getContentResolver().canonicalizeOrElse(
                        ContentUris.withAppendedId(baseUri, cursor.getLong(0)));
                return ringtoneUri;
            }
        }

        return null;
    }

    private static String getDefaultRingtoneSetting(int type) {
        switch (type) {
            case TYPE_RINGTONE: return "ringtone_set";
            case TYPE_NOTIFICATION: return "notification_sound_set";
            case TYPE_ALARM: return "alarm_alert_set";
            default: throw new IllegalArgumentException();
        }
    }

    private static String getDefaultRingtoneFilename(int type) {
        switch (type) {
            case TYPE_RINGTONE: return SystemProperties.get("ro.config.ringtone");
            case TYPE_NOTIFICATION: return SystemProperties.get("ro.config.notification_sound");
            case TYPE_ALARM: return SystemProperties.get("ro.config.alarm_alert");
            default: throw new IllegalArgumentException();
        }
    }

    private static String getQueryStringForType(int type) {
        switch (type) {
            case TYPE_RINGTONE: return MediaStore.Audio.AudioColumns.IS_RINGTONE;
            case TYPE_NOTIFICATION: return MediaStore.Audio.AudioColumns.IS_NOTIFICATION;
            case TYPE_ALARM: return MediaStore.Audio.AudioColumns.IS_ALARM;
            default: throw new IllegalArgumentException();
        }
    }
}

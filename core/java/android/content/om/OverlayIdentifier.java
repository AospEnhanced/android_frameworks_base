/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.content.om;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.DataClass;

import java.util.Objects;

/**
 * A key used to uniquely identify a Runtime Resource Overlay (RRO).
 * <!-- For applications -->
 *
 * <p>An overlay always belongs to a package and have a mandatory name associated with it. The name
 * helps uniquely identify a particular overlay within a package.
 *
 * <!-- For OverlayManagerService, it isn't public part and hidden by HTML comment. -->
 * <!--
 * <p>An overlay always belongs to a package and may optionally have a name associated with it. The
 * name helps uniquely identify a particular overlay within a package.
 * -->
 *
 * @see OverlayInfo#getOverlayIdentifier()
 */
@DataClass(genConstructor = false, genBuilder = false, genHiddenBuilder = false,
        genEqualsHashCode = true, genToString = false)
public final class OverlayIdentifier implements Parcelable {
    /**
     * The package name containing or owning the overlay.
     *
     * @hide
     */
    @Nullable private final String mPackageName;

    /**
     * The unique name within the package of the overlay.
     *
     * @hide
     */
    @Nullable private final String mOverlayName;

    /**
     * Creates an identifier from a package and unique name within the package.
     *
     * @param packageName the package containing or owning the overlay
     * @param overlayName the unique name of the overlay within the package
     * @hide
     */
    public OverlayIdentifier(@NonNull String packageName, @Nullable String overlayName) {
        mPackageName = packageName;
        mOverlayName = overlayName;
    }

    /**
     * Creates an identifier for an overlay without a name.
     *
     * @param packageName the package containing or owning the overlay
     * @hide
     */
    public OverlayIdentifier(@NonNull String packageName) {
        mPackageName = packageName;
        mOverlayName = null;
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @Override
    public String toString() {
        return mOverlayName == null ? mPackageName : mPackageName + ":" + mOverlayName;
    }

    /** @hide */
    @NonNull
    public static OverlayIdentifier fromString(@NonNull String text) {
        final String[] parts = text.split(":", 2);
        if (parts.length == 2) {
            return new OverlayIdentifier(parts[0], parts[1]);
        } else {
            return new OverlayIdentifier(parts[0]);
        }
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/content/om/OverlayIdentifier.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /**
     * Retrieves the package name containing or owning the overlay.
     *
     * @hide
     */
    @DataClass.Generated.Member
    public @Nullable String getPackageName() {
        return mPackageName;
    }

    /**
     * Retrieves the unique name within the package of the overlay.
     *
     * @hide
     */
    @DataClass.Generated.Member
    public @Nullable String getOverlayName() {
        return mOverlayName;
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @Override
    @DataClass.Generated.Member
    public boolean equals(@Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(OverlayIdentifier other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        OverlayIdentifier that = (OverlayIdentifier) o;
        //noinspection PointlessBooleanExpression
        return true
                && Objects.equals(mPackageName, that.mPackageName)
                && Objects.equals(mOverlayName, that.mOverlayName);
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + Objects.hashCode(mPackageName);
        _hash = 31 * _hash + Objects.hashCode(mOverlayName);
        return _hash;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mPackageName != null) flg |= 0x1;
        if (mOverlayName != null) flg |= 0x2;
        dest.writeByte(flg);
        if (mPackageName != null) dest.writeString(mPackageName);
        if (mOverlayName != null) dest.writeString(mOverlayName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ OverlayIdentifier(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        String packageName = (flg & 0x1) == 0 ? null : in.readString();
        String overlayName = (flg & 0x2) == 0 ? null : in.readString();

        this.mPackageName = packageName;
        this.mOverlayName = overlayName;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<OverlayIdentifier> CREATOR
            = new Parcelable.Creator<OverlayIdentifier>() {
        @Override
        public OverlayIdentifier[] newArray(int size) {
            return new OverlayIdentifier[size];
        }

        @Override
        public OverlayIdentifier createFromParcel(@NonNull Parcel in) {
            return new OverlayIdentifier(in);
        }
    };

    @DataClass.Generated(
            time = 1670404485646L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/content/om/OverlayIdentifier.java",
            inputSignatures = "private final @android.annotation.Nullable java.lang.String mPackageName\nprivate final @android.annotation.Nullable java.lang.String mOverlayName\npublic @java.lang.Override java.lang.String toString()\npublic static @android.annotation.NonNull android.content.om.OverlayIdentifier fromString(java.lang.String)\nclass OverlayIdentifier extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genConstructor=false, genBuilder=false, genHiddenBuilder=false, genEqualsHashCode=true, genToString=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}

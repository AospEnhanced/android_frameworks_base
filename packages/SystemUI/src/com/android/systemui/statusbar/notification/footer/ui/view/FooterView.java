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

package com.android.systemui.statusbar.notification.footer.ui.view;

import static android.graphics.PorterDuff.Mode.SRC_ATOP;

import android.annotation.ColorInt;
import android.annotation.DrawableRes;
import android.annotation.StringRes;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.ColorFilter;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.IndentingPrintWriter;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.settingslib.Utils;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor;
import com.android.systemui.statusbar.notification.row.FooterViewButton;
import com.android.systemui.statusbar.notification.row.StackScrollerDecorView;
import com.android.systemui.statusbar.notification.stack.ExpandableViewState;
import com.android.systemui.statusbar.notification.stack.ViewState;
import com.android.systemui.util.DumpUtilsKt;

import java.io.PrintWriter;

public class FooterView extends StackScrollerDecorView {
    private static final String TAG = "FooterView";

    private FooterViewButton mClearAllButton;
    private FooterViewButton mManageButton;
    private boolean mShowHistory;
    // String cache, for performance reasons.
    // Reading them from a Resources object can be quite slow sometimes.
    private String mManageNotificationText;
    private String mManageNotificationHistoryText;

    // Footer label
    private TextView mSeenNotifsFooterTextView;
    private String mSeenNotifsFilteredText;
    private Drawable mSeenNotifsFilteredIcon;

    private @StringRes int mMessageStringId;
    private @DrawableRes int mMessageIconId;

    public FooterView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected View findContentView() {
        return findViewById(R.id.content);
    }

    protected View findSecondaryView() {
        return findViewById(R.id.dismiss_text);
    }

    /** Whether the "Clear all" button is currently visible. */
    public boolean isClearAllButtonVisible() {
        return isSecondaryVisible();
    }

    /**
     * Set the visibility of the "Clear all" button to {@code visible}. Animate the change if
     * {@code animate} is true.
     */
    public void setClearAllButtonVisible(boolean visible, boolean animate) {
        setSecondaryVisible(visible, animate);
    }

    @Override
    public void dump(PrintWriter pwOriginal, String[] args) {
        IndentingPrintWriter pw = DumpUtilsKt.asIndenting(pwOriginal);
        super.dump(pw, args);
        DumpUtilsKt.withIncreasedIndent(pw, () -> {
            pw.println("visibility: " + DumpUtilsKt.visibilityString(getVisibility()));
            pw.println("manageButton showHistory: " + mShowHistory);
            pw.println("manageButton visibility: "
                    + DumpUtilsKt.visibilityString(mClearAllButton.getVisibility()));
            pw.println("dismissButton visibility: "
                    + DumpUtilsKt.visibilityString(mClearAllButton.getVisibility()));
        });
    }

    /** Set the string for a message to be shown instead of the buttons. */
    public void setMessageString(@StringRes int messageId) {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) return;
        if (mMessageStringId == messageId) {
            return; // nothing changed
        }
        mMessageStringId = messageId;
        updateMessageString();
    }

    private void updateMessageString() {
        if (mMessageStringId == 0) {
            return; // not initialized yet
        }
        String messageString = getContext().getString(mMessageStringId);
        mSeenNotifsFooterTextView.setText(messageString);
    }


    /** Set the icon to be shown before the message (see {@link #setMessageString(int)}). */
    public void setMessageIcon(@DrawableRes int iconId) {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) return;
        if (mMessageIconId == iconId) {
            return; // nothing changed
        }
        mMessageIconId = iconId;
        updateMessageIcon();
    }

    private void updateMessageIcon() {
        if (mMessageIconId == 0) {
            return; // not initialized yet
        }
        int unlockIconSize = getResources()
                .getDimensionPixelSize(R.dimen.notifications_unseen_footer_icon_size);
        @SuppressLint("UseCompatLoadingForDrawables")
        Drawable messageIcon = getContext().getDrawable(mMessageIconId);
        if (messageIcon != null) {
            messageIcon.setBounds(0, 0, unlockIconSize, unlockIconSize);
            mSeenNotifsFooterTextView
                    .setCompoundDrawablesRelative(messageIcon, null, null, null);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mClearAllButton = (FooterViewButton) findSecondaryView();
        mManageButton = findViewById(R.id.manage_text);
        mSeenNotifsFooterTextView = findViewById(R.id.unlock_prompt_footer);
        updateResources();
        updateContent();
        updateColors();
    }

    /** Show a message instead of the footer buttons. */
    public void setFooterLabelVisible(boolean isVisible) {
        if (isVisible) {
            mManageButton.setVisibility(View.GONE);
            mClearAllButton.setVisibility(View.GONE);
            mSeenNotifsFooterTextView.setVisibility(View.VISIBLE);
        } else {
            mManageButton.setVisibility(View.VISIBLE);
            mClearAllButton.setVisibility(View.VISIBLE);
            mSeenNotifsFooterTextView.setVisibility(View.GONE);
        }
    }

    /** Set onClickListener for the manage/history button. */
    public void setManageButtonClickListener(OnClickListener listener) {
        mManageButton.setOnClickListener(listener);
    }

    /** Set onClickListener for the clear all (end) button. */
    public void setClearAllButtonClickListener(OnClickListener listener) {
        mClearAllButton.setOnClickListener(listener);
    }

    /**
     * Whether the touch is outside the Clear all button.
     *
     * TODO(b/293167744): This is an artifact from the time when we could press underneath the
     * shade to dismiss it. Check if it's safe to remove.
     */
    public boolean isOnEmptySpace(float touchX, float touchY) {
        return touchX < mContent.getX()
                || touchX > mContent.getX() + mContent.getWidth()
                || touchY < mContent.getY()
                || touchY > mContent.getY() + mContent.getHeight();
    }

    /** Show "History" instead of "Manage" on the start button. */
    public void showHistory(boolean showHistory) {
        if (mShowHistory == showHistory) {
            return;
        }
        mShowHistory = showHistory;
        updateContent();
    }

    private void updateContent() {
        if (mShowHistory) {
            mManageButton.setText(mManageNotificationHistoryText);
            mManageButton.setContentDescription(mManageNotificationHistoryText);
        } else {
            mManageButton.setText(mManageNotificationText);
            mManageButton.setContentDescription(mManageNotificationText);
        }
        if (!FooterViewRefactor.isEnabled()) {
            mSeenNotifsFooterTextView.setText(mSeenNotifsFilteredText);
            mSeenNotifsFooterTextView
                    .setCompoundDrawablesRelative(mSeenNotifsFilteredIcon, null, null, null);
        }
    }

    /** Whether the start button shows "History" (true) or "Manage" (false). */
    public boolean isHistoryShown() {
        return mShowHistory;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateColors();
        mClearAllButton.setText(R.string.clear_all_notifications_text);
        mClearAllButton.setContentDescription(
                mContext.getString(R.string.accessibility_clear_all));
        updateResources();
        updateContent();

        if (FooterViewRefactor.isEnabled()) {
            updateMessageString();
            updateMessageIcon();
        }
    }

    /**
     * Update the text and background colors for the current color palette and night mode setting.
     */
    public void updateColors() {
        Resources.Theme theme = mContext.getTheme();
        final @ColorInt int onSurface = Utils.getColorAttrDefaultColor(mContext,
                com.android.internal.R.attr.materialColorOnSurface);
        final @ColorInt int scHigh = Utils.getColorAttrDefaultColor(mContext,
                com.android.internal.R.attr.materialColorSurfaceContainerHigh);
        final Drawable clearAllBg = theme.getDrawable(R.drawable.notif_footer_btn_background);
        final Drawable manageBg = theme.getDrawable(R.drawable.notif_footer_btn_background);
        // TODO(b/282173943): Remove redundant tinting once Resources are thread-safe
        final ColorFilter bgColorFilter = new PorterDuffColorFilter(scHigh, SRC_ATOP);
        if (scHigh != 0) {
            clearAllBg.setColorFilter(bgColorFilter);
            manageBg.setColorFilter(bgColorFilter);
        }
        mClearAllButton.setBackground(clearAllBg);
        mClearAllButton.setTextColor(onSurface);
        mManageButton.setBackground(manageBg);
        mManageButton.setTextColor(onSurface);
        mSeenNotifsFooterTextView.setTextColor(onSurface);
        mSeenNotifsFooterTextView.setCompoundDrawableTintList(ColorStateList.valueOf(onSurface));
    }

    private void updateResources() {
        mManageNotificationText = getContext().getString(R.string.manage_notifications_text);
        mManageNotificationHistoryText = getContext()
                .getString(R.string.manage_notifications_history_text);
        if (!FooterViewRefactor.isEnabled()) {
            int unlockIconSize = getResources()
                    .getDimensionPixelSize(R.dimen.notifications_unseen_footer_icon_size);
            mSeenNotifsFilteredText = getContext().getString(R.string.unlock_to_see_notif_text);
            mSeenNotifsFilteredIcon = getContext().getDrawable(R.drawable.ic_friction_lock_closed);
            mSeenNotifsFilteredIcon.setBounds(0, 0, unlockIconSize, unlockIconSize);
        }
    }

    @Override
    @NonNull
    public ExpandableViewState createExpandableViewState() {
        return new FooterViewState();
    }

    public class FooterViewState extends ExpandableViewState {
        /**
         * used to hide the content of the footer to animate.
         * #hide is applied without animation, but #hideContent has animation.
         */
        public boolean hideContent;

        @Override
        public void copyFrom(ViewState viewState) {
            super.copyFrom(viewState);
            if (viewState instanceof FooterViewState) {
                hideContent = ((FooterViewState) viewState).hideContent;
            }
        }

        @Override
        public void applyToView(View view) {
            super.applyToView(view);
            if (view instanceof FooterView) {
                FooterView footerView = (FooterView) view;
                footerView.setContentVisibleAnimated(!hideContent);
            }
        }
    }
}

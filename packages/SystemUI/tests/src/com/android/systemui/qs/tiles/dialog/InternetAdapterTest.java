package com.android.systemui.qs.tiles.dialog;

import static com.android.systemui.qs.tiles.dialog.InternetDialogController.MAX_WIFI_ENTRY_COUNT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.drawable.Drawable;
import android.testing.AndroidTestingRunner;
import android.testing.TestableResources;
import android.view.View;
import android.widget.LinearLayout;

import androidx.test.filters.SmallTest;

import com.android.settingslib.wifi.WifiUtils;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.wifitrackerlib.WifiEntry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Arrays;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class InternetAdapterTest extends SysuiTestCase {

    private static final String WIFI_TITLE = "Wi-Fi Title";
    private static final String WIFI_SUMMARY = "Wi-Fi Summary";
    private static final int GEAR_ICON_RES_ID = R.drawable.ic_settings_24dp;
    private static final int LOCK_ICON_RES_ID = R.drawable.ic_friction_lock_closed;

    @Rule
    public MockitoRule mRule = MockitoJUnit.rule();

    @Mock
    private WifiEntry mInternetWifiEntry;
    @Mock
    private List<WifiEntry> mWifiEntries;
    @Mock
    private WifiEntry mWifiEntry;
    @Mock
    private InternetDialogController mInternetDialogController;
    @Mock
    private WifiUtils.InternetIconInjector mWifiIconInjector;
    @Mock
    private Drawable mGearIcon;
    @Mock
    private Drawable mLockIcon;

    private TestableResources mTestableResources;
    private InternetAdapter mInternetAdapter;
    private InternetAdapter.InternetViewHolder mViewHolder;

    @Before
    public void setUp() {
        mTestableResources = mContext.getOrCreateTestableResources();
        when(mInternetWifiEntry.getTitle()).thenReturn(WIFI_TITLE);
        when(mInternetWifiEntry.getSummary(false)).thenReturn(WIFI_SUMMARY);
        when(mInternetWifiEntry.isDefaultNetwork()).thenReturn(true);
        when(mInternetWifiEntry.hasInternetAccess()).thenReturn(true);
        when(mWifiEntry.getTitle()).thenReturn(WIFI_TITLE);
        when(mWifiEntry.getSummary(false)).thenReturn(WIFI_SUMMARY);

        mInternetAdapter = new InternetAdapter(mInternetDialogController);
        mViewHolder = mInternetAdapter.onCreateViewHolder(new LinearLayout(mContext), 0);
        mInternetAdapter.setWifiEntries(Arrays.asList(mWifiEntry), 1 /* wifiEntriesCount */);
        mViewHolder.mWifiIconInjector = mWifiIconInjector;
    }

    @Test
    public void getItemCount_returnWifiEntriesCount() {
        for (int i = 0; i < MAX_WIFI_ENTRY_COUNT; i++) {
            mInternetAdapter.setWifiEntries(mWifiEntries, i /* wifiEntriesCount */);

            assertThat(mInternetAdapter.getItemCount()).isEqualTo(i);
        }
    }

    @Test
    public void onBindViewHolder_bindWithOpenWifiNetwork_verifyView() {
        when(mWifiEntry.getSecurity()).thenReturn(WifiEntry.SECURITY_NONE);
        mInternetAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mWifiTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mWifiSummaryText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mWifiIcon.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mWifiEndIcon.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void onBindViewHolder_bindWithSecurityWifiNetwork_verifyView() {
        when(mWifiEntry.getSecurity()).thenReturn(WifiEntry.SECURITY_PSK);
        mInternetAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mWifiTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mWifiSummaryText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mWifiIcon.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mWifiEndIcon.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void onBindViewHolder_wifiLevelUnreachable_shouldNotGetWifiIcon() {
        reset(mWifiIconInjector);
        when(mWifiEntry.getLevel()).thenReturn(WifiEntry.WIFI_LEVEL_UNREACHABLE);

        mInternetAdapter.onBindViewHolder(mViewHolder, 0);

        verify(mWifiIconInjector, never()).getIcon(anyBoolean(), anyInt());
    }

    @Test
    public void onBindViewHolder_shouldNotShowXLevelIcon_getIconWithInternet() {
        when(mWifiEntry.shouldShowXLevelIcon()).thenReturn(false);

        mInternetAdapter.onBindViewHolder(mViewHolder, 0);

        verify(mWifiIconInjector).getIcon(eq(false) /* noInternet */, anyInt());
    }

    @Test
    public void onBindViewHolder_shouldShowXLevelIcon_getIconWithNoInternet() {
        when(mWifiEntry.shouldShowXLevelIcon()).thenReturn(true);

        mInternetAdapter.onBindViewHolder(mViewHolder, 0);

        verify(mWifiIconInjector).getIcon(eq(true) /* noInternet */, anyInt());
    }

    @Test
    public void setWifiEntries_wifiCountLessThenMaxCount_setWifiCount() {
        final int wifiCount = MAX_WIFI_ENTRY_COUNT - 1;
        mInternetAdapter.mMaxEntriesCount = MAX_WIFI_ENTRY_COUNT;

        mInternetAdapter.setWifiEntries(mWifiEntries, wifiCount);

        assertThat(mInternetAdapter.mWifiEntriesCount).isEqualTo(wifiCount);
    }

    @Test
    public void setWifiEntries_wifiCountGreaterThenMaxCount_setMaxCount() {
        final int wifiCount = MAX_WIFI_ENTRY_COUNT;
        mInternetAdapter.mMaxEntriesCount = MAX_WIFI_ENTRY_COUNT - 1;

        mInternetAdapter.setWifiEntries(mWifiEntries, wifiCount);

        assertThat(mInternetAdapter.mWifiEntriesCount).isEqualTo(mInternetAdapter.mMaxEntriesCount);
    }

    @Test
    public void setMaxEntriesCount_maxCountLessThenZero_doNothing() {
        mInternetAdapter.mMaxEntriesCount = MAX_WIFI_ENTRY_COUNT;
        final int maxCount = -1;

        mInternetAdapter.setMaxEntriesCount(maxCount);

        assertThat(mInternetAdapter.mMaxEntriesCount).isEqualTo(MAX_WIFI_ENTRY_COUNT);
    }

    @Test
    public void setMaxEntriesCount_maxCountGreaterThenWifiCount_updateMaxCount() {
        mInternetAdapter.mWifiEntriesCount = MAX_WIFI_ENTRY_COUNT - 2;
        mInternetAdapter.mMaxEntriesCount = MAX_WIFI_ENTRY_COUNT;
        final int maxCount = MAX_WIFI_ENTRY_COUNT - 1;

        mInternetAdapter.setMaxEntriesCount(maxCount);

        assertThat(mInternetAdapter.mWifiEntriesCount).isEqualTo(MAX_WIFI_ENTRY_COUNT - 2);
        assertThat(mInternetAdapter.mMaxEntriesCount).isEqualTo(maxCount);
    }

    @Test
    public void setMaxEntriesCount_maxCountLessThenWifiCount_updateBothCount() {
        mInternetAdapter.mWifiEntriesCount = MAX_WIFI_ENTRY_COUNT;
        mInternetAdapter.mMaxEntriesCount = MAX_WIFI_ENTRY_COUNT;
        final int maxCount = MAX_WIFI_ENTRY_COUNT - 1;

        mInternetAdapter.setMaxEntriesCount(maxCount);

        assertThat(mInternetAdapter.mWifiEntriesCount).isEqualTo(maxCount);
        assertThat(mInternetAdapter.mMaxEntriesCount).isEqualTo(maxCount);
    }

    @Test
    public void viewHolderUpdateEndIcon_wifiConnected_updateGearIcon() {
        mTestableResources.addOverride(GEAR_ICON_RES_ID, mGearIcon);

        mViewHolder.updateEndIcon(WifiEntry.CONNECTED_STATE_CONNECTED, WifiEntry.SECURITY_PSK);

        assertThat(mViewHolder.mWifiEndIcon.getDrawable()).isEqualTo(mGearIcon);
    }

    @Test
    public void viewHolderUpdateEndIcon_wifiDisconnectedAndSecurityPsk_updateLockIcon() {
        mTestableResources.addOverride(LOCK_ICON_RES_ID, mLockIcon);

        mViewHolder.updateEndIcon(WifiEntry.CONNECTED_STATE_DISCONNECTED, WifiEntry.SECURITY_PSK);

        assertThat(mViewHolder.mWifiEndIcon.getDrawable()).isEqualTo(mLockIcon);
    }

    @Test
    public void viewHolderUpdateEndIcon_wifiDisconnectedAndSecurityNone_hideIcon() {
        mViewHolder.updateEndIcon(WifiEntry.CONNECTED_STATE_DISCONNECTED, WifiEntry.SECURITY_NONE);

        assertThat(mViewHolder.mWifiEndIcon.getVisibility()).isEqualTo(View.GONE);
    }
}

package com.android.systemui.qs.tiles.dialog;

import static com.android.systemui.qs.tiles.dialog.InternetDialogController.MAX_WIFI_ENTRY_COUNT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.wifi.WifiManager;
import android.os.Handler;
import android.telephony.TelephonyManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.wifitrackerlib.WifiEntry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class InternetDialogTest extends SysuiTestCase {

    private static final String MOBILE_NETWORK_TITLE = "Mobile Title";
    private static final String MOBILE_NETWORK_SUMMARY = "Mobile Summary";
    private static final String WIFI_TITLE = "Connected Wi-Fi Title";
    private static final String WIFI_SUMMARY = "Connected Wi-Fi Summary";

    @Mock
    private Handler mHandler;
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private WifiManager mWifiManager;
    @Mock
    private WifiEntry mInternetWifiEntry;
    @Mock
    private List<WifiEntry> mWifiEntries;
    @Mock
    private InternetAdapter mInternetAdapter;
    @Mock
    private InternetDialogController mInternetDialogController;

    private FakeExecutor mBgExecutor = new FakeExecutor(new FakeSystemClock());
    private InternetDialog mInternetDialog;
    private View mDialogView;
    private View mSubTitle;
    private LinearLayout mEthernet;
    private LinearLayout mMobileDataToggle;
    private LinearLayout mWifiToggle;
    private LinearLayout mConnectedWifi;
    private RecyclerView mWifiList;
    private LinearLayout mSeeAll;
    private LinearLayout mWifiScanNotify;
    private TextView mAirplaneModeSummaryText;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        doReturn(mTelephonyManager).when(mTelephonyManager).createForSubscriptionId(anyInt());
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        when(mInternetWifiEntry.getTitle()).thenReturn(WIFI_TITLE);
        when(mInternetWifiEntry.getSummary(false)).thenReturn(WIFI_SUMMARY);
        when(mInternetWifiEntry.isDefaultNetwork()).thenReturn(true);
        when(mInternetWifiEntry.hasInternetAccess()).thenReturn(true);
        when(mWifiEntries.size()).thenReturn(1);

        when(mInternetDialogController.getMobileNetworkTitle()).thenReturn(MOBILE_NETWORK_TITLE);
        when(mInternetDialogController.getMobileNetworkSummary())
                .thenReturn(MOBILE_NETWORK_SUMMARY);
        when(mInternetDialogController.getWifiManager()).thenReturn(mWifiManager);

        mInternetDialog = new InternetDialog(mContext, mock(InternetDialogFactory.class),
                mInternetDialogController, true, true, true, mock(UiEventLogger.class), mHandler,
                mBgExecutor);
        mInternetDialog.mAdapter = mInternetAdapter;
        mInternetDialog.mConnectedWifiEntry = mInternetWifiEntry;
        mInternetDialog.mWifiEntriesCount = mWifiEntries.size();
        mInternetDialog.show();

        mDialogView = mInternetDialog.mDialogView;
        mSubTitle = mDialogView.requireViewById(R.id.internet_dialog_subtitle);
        mEthernet = mDialogView.requireViewById(R.id.ethernet_layout);
        mMobileDataToggle = mDialogView.requireViewById(R.id.mobile_network_layout);
        mWifiToggle = mDialogView.requireViewById(R.id.turn_on_wifi_layout);
        mConnectedWifi = mDialogView.requireViewById(R.id.wifi_connected_layout);
        mWifiList = mDialogView.requireViewById(R.id.wifi_list_layout);
        mSeeAll = mDialogView.requireViewById(R.id.see_all_layout);
        mWifiScanNotify = mDialogView.requireViewById(R.id.wifi_scan_notify_layout);
        mAirplaneModeSummaryText = mDialogView.requireViewById(R.id.airplane_mode_summary);
    }

    @After
    public void tearDown() {
        mInternetDialog.dismissDialog();
    }

    @Test
    public void hideWifiViews_WifiViewsGone() {
        mInternetDialog.hideWifiViews();

        assertThat(mInternetDialog.mIsProgressBarVisible).isFalse();
        assertThat(mWifiToggle.getVisibility()).isEqualTo(View.GONE);
        assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
        assertThat(mWifiList.getVisibility()).isEqualTo(View.GONE);
        assertThat(mSeeAll.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_withApmOn_internetDialogSubTitleGone() {
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);

        mInternetDialog.updateDialog(true);

        assertThat(mSubTitle.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void updateDialog_withApmOff_internetDialogSubTitleVisible() {
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(false);

        mInternetDialog.updateDialog(true);

        assertThat(mSubTitle.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void updateDialog_apmOffAndHasEthernet_showEthernet() {
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(false);
        when(mInternetDialogController.hasEthernet()).thenReturn(true);

        mInternetDialog.updateDialog(true);

        assertThat(mEthernet.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void updateDialog_apmOffAndNoEthernet_hideEthernet() {
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(false);
        when(mInternetDialogController.hasEthernet()).thenReturn(false);

        mInternetDialog.updateDialog(true);

        assertThat(mEthernet.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_apmOnAndHasEthernet_showEthernet() {
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);
        when(mInternetDialogController.hasEthernet()).thenReturn(true);

        mInternetDialog.updateDialog(true);

        assertThat(mEthernet.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void updateDialog_apmOnAndNoEthernet_hideEthernet() {
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);
        when(mInternetDialogController.hasEthernet()).thenReturn(false);

        mInternetDialog.updateDialog(true);

        assertThat(mEthernet.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_apmOffAndNotCarrierNetwork_mobileDataLayoutGone() {
        // Mobile network should be gone if the list of active subscriptionId is null.
        when(mInternetDialogController.isCarrierNetworkActive()).thenReturn(false);
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(false);
        when(mInternetDialogController.hasActiveSubId()).thenReturn(false);

        mInternetDialog.updateDialog(true);

        assertThat(mMobileDataToggle.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_apmOnWithCarrierNetworkAndWifiStatus_mobileDataLayout() {
        // Carrier network should be gone if airplane mode ON and Wi-Fi is off.
        when(mInternetDialogController.isCarrierNetworkActive()).thenReturn(true);
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);
        when(mWifiManager.isWifiEnabled()).thenReturn(false);

        mInternetDialog.updateDialog(true);

        assertThat(mMobileDataToggle.getVisibility()).isEqualTo(View.GONE);

        // Carrier network should be visible if airplane mode ON and Wi-Fi is ON.
        when(mInternetDialogController.isCarrierNetworkActive()).thenReturn(true);
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);

        mInternetDialog.updateDialog(true);

        assertThat(mMobileDataToggle.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void updateDialog_apmOnAndNoCarrierNetwork_mobileDataLayoutGone() {
        when(mInternetDialogController.isCarrierNetworkActive()).thenReturn(false);
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);

        mInternetDialog.updateDialog(true);

        assertThat(mMobileDataToggle.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_apmOnAndWifiOnHasCarrierNetwork_showAirplaneSummary() {
        when(mInternetDialogController.isCarrierNetworkActive()).thenReturn(true);
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);
        mInternetDialog.mConnectedWifiEntry = null;
        doReturn(false).when(mInternetDialogController).activeNetworkIsCellular();

        mInternetDialog.updateDialog(true);

        assertThat(mMobileDataToggle.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mAirplaneModeSummaryText.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void updateDialog_apmOffAndWifiOnHasCarrierNetwork_notShowApmSummary() {
        when(mInternetDialogController.isCarrierNetworkActive()).thenReturn(true);
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(false);
        mInternetDialog.mConnectedWifiEntry = null;
        doReturn(false).when(mInternetDialogController).activeNetworkIsCellular();

        mInternetDialog.updateDialog(true);

        assertThat(mAirplaneModeSummaryText.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_apmOffAndHasCarrierNetwork_notShowApmSummary() {
        when(mInternetDialogController.isCarrierNetworkActive()).thenReturn(true);
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(false);

        mInternetDialog.updateDialog(true);

        assertThat(mAirplaneModeSummaryText.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_apmOnAndNoCarrierNetwork_notShowApmSummary() {
        when(mInternetDialogController.isCarrierNetworkActive()).thenReturn(false);
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);

        mInternetDialog.updateDialog(true);

        assertThat(mAirplaneModeSummaryText.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_wifiOnAndHasInternetWifi_showConnectedWifi() {
        // The preconditions WiFi ON and Internet WiFi are already in setUp()
        doReturn(false).when(mInternetDialogController).activeNetworkIsCellular();

        mInternetDialog.updateDialog(false);

        assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void updateDialog_wifiOnAndNoConnectedWifi_hideConnectedWifi() {
        // The precondition WiFi ON is already in setUp()
        mInternetDialog.mConnectedWifiEntry = null;
        doReturn(false).when(mInternetDialogController).activeNetworkIsCellular();

        mInternetDialog.updateDialog(false);

        assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_wifiOnAndNoWifiEntry_showWifiListAndSeeAllArea() {
        // The precondition WiFi ON is already in setUp()
        mInternetDialog.mConnectedWifiEntry = null;
        mInternetDialog.mWifiEntriesCount = 0;

        mInternetDialog.updateDialog(false);

        assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
        // Show a blank block to fix the dialog height even if there is no WiFi list
        assertThat(mWifiList.getVisibility()).isEqualTo(View.VISIBLE);
        verify(mInternetAdapter).setMaxEntriesCount(3);
        assertThat(mSeeAll.getVisibility()).isEqualTo(View.INVISIBLE);
    }

    @Test
    public void updateDialog_wifiOnAndOneWifiEntry_showWifiListAndSeeAllArea() {
        // The precondition WiFi ON is already in setUp()
        mInternetDialog.mConnectedWifiEntry = null;
        mInternetDialog.mWifiEntriesCount = 1;

        mInternetDialog.updateDialog(false);

        assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
        // Show a blank block to fix the dialog height even if there is no WiFi list
        assertThat(mWifiList.getVisibility()).isEqualTo(View.VISIBLE);
        verify(mInternetAdapter).setMaxEntriesCount(3);
        assertThat(mSeeAll.getVisibility()).isEqualTo(View.INVISIBLE);
    }

    @Test
    public void updateDialog_wifiOnAndHasConnectedWifi_showAllWifiAndSeeAllArea() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        mInternetDialog.mWifiEntriesCount = 0;

        mInternetDialog.updateDialog(false);

        assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.VISIBLE);
        // Show a blank block to fix the dialog height even if there is no WiFi list
        assertThat(mWifiList.getVisibility()).isEqualTo(View.VISIBLE);
        verify(mInternetAdapter).setMaxEntriesCount(2);
        assertThat(mSeeAll.getVisibility()).isEqualTo(View.INVISIBLE);
    }

    @Test
    public void updateDialog_wifiOnAndHasMaxWifiList_showWifiListAndSeeAll() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        mInternetDialog.mConnectedWifiEntry = null;
        mInternetDialog.mWifiEntriesCount = MAX_WIFI_ENTRY_COUNT;
        mInternetDialog.mHasMoreWifiEntries = true;

        mInternetDialog.updateDialog(false);

        assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
        assertThat(mWifiList.getVisibility()).isEqualTo(View.VISIBLE);
        verify(mInternetAdapter).setMaxEntriesCount(3);
        assertThat(mSeeAll.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void updateDialog_wifiOnAndHasBothWifiEntry_showBothWifiEntryAndSeeAll() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        mInternetDialog.mWifiEntriesCount = MAX_WIFI_ENTRY_COUNT - 1;
        mInternetDialog.mHasMoreWifiEntries = true;

        mInternetDialog.updateDialog(false);

        assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mWifiList.getVisibility()).isEqualTo(View.VISIBLE);
        verify(mInternetAdapter).setMaxEntriesCount(2);
        assertThat(mSeeAll.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void updateDialog_deviceLockedAndNoConnectedWifi_showWifiToggle() {
        // The preconditions WiFi entries are already in setUp()
        when(mInternetDialogController.isDeviceLocked()).thenReturn(true);
        mInternetDialog.mConnectedWifiEntry = null;

        mInternetDialog.updateDialog(false);

        // Show WiFi Toggle without background
        assertThat(mWifiToggle.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mWifiToggle.getBackground()).isNull();
        // Hide Wi-Fi networks and See all
        assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
        assertThat(mWifiList.getVisibility()).isEqualTo(View.GONE);
        assertThat(mSeeAll.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_deviceLockedAndHasConnectedWifi_showWifiToggleWithBackground() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        when(mInternetDialogController.isDeviceLocked()).thenReturn(true);

        mInternetDialog.updateDialog(false);

        // Show WiFi Toggle with highlight background
        assertThat(mWifiToggle.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mWifiToggle.getBackground()).isNotNull();
        // Hide Wi-Fi networks and See all
        assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
        assertThat(mWifiList.getVisibility()).isEqualTo(View.GONE);
        assertThat(mSeeAll.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_wifiOn_hideWifiScanNotify() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()

        mInternetDialog.updateDialog(false);

        assertThat(mWifiScanNotify.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_wifiOffAndWifiScanOff_hideWifiScanNotify() {
        when(mWifiManager.isWifiEnabled()).thenReturn(false);
        when(mInternetDialogController.isWifiScanEnabled()).thenReturn(false);

        mInternetDialog.updateDialog(false);

        assertThat(mWifiScanNotify.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_wifiOffAndWifiScanOnAndDeviceLocked_hideWifiScanNotify() {
        when(mWifiManager.isWifiEnabled()).thenReturn(false);
        when(mInternetDialogController.isWifiScanEnabled()).thenReturn(true);
        when(mInternetDialogController.isDeviceLocked()).thenReturn(true);

        mInternetDialog.updateDialog(false);

        assertThat(mWifiScanNotify.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_wifiOffAndWifiScanOnAndDeviceUnlocked_showWifiScanNotify() {
        when(mWifiManager.isWifiEnabled()).thenReturn(false);
        when(mInternetDialogController.isWifiScanEnabled()).thenReturn(true);
        when(mInternetDialogController.isDeviceLocked()).thenReturn(false);

        mInternetDialog.updateDialog(false);

        assertThat(mWifiScanNotify.getVisibility()).isEqualTo(View.VISIBLE);
        TextView wifiScanNotifyText = mDialogView.requireViewById(R.id.wifi_scan_notify_text);
        assertThat(wifiScanNotifyText.getText().length()).isNotEqualTo(0);
        assertThat(wifiScanNotifyText.getMovementMethod()).isNotNull();
    }

    @Test
    public void onClickSeeMoreButton_clickSeeAll_verifyLaunchNetworkSetting() {
        mSeeAll.performClick();

        verify(mInternetDialogController).launchNetworkSetting();
    }

    @Test
    public void showProgressBar_wifiDisabled_hideProgressBar() {
        Mockito.reset(mHandler);
        when(mWifiManager.isWifiEnabled()).thenReturn(false);

        mInternetDialog.showProgressBar();

        assertThat(mInternetDialog.mIsProgressBarVisible).isFalse();
        verify(mHandler, never()).postDelayed(any(Runnable.class), anyLong());
    }

    @Test
    public void showProgressBar_deviceLocked_hideProgressBar() {
        Mockito.reset(mHandler);
        when(mInternetDialogController.isDeviceLocked()).thenReturn(true);

        mInternetDialog.showProgressBar();

        assertThat(mInternetDialog.mIsProgressBarVisible).isFalse();
        verify(mHandler, never()).postDelayed(any(Runnable.class), anyLong());
    }

    @Test
    public void showProgressBar_wifiEnabledWithWifiEntry_showProgressBarThenHide() {
        Mockito.reset(mHandler);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);

        mInternetDialog.showProgressBar();

        // Show progress bar
        assertThat(mInternetDialog.mIsProgressBarVisible).isTrue();

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mHandler).postDelayed(runnableCaptor.capture(),
                eq(InternetDialog.PROGRESS_DELAY_MS));
        runnableCaptor.getValue().run();

        // Then hide progress bar
        assertThat(mInternetDialog.mIsProgressBarVisible).isFalse();
    }

    @Test
    public void showProgressBar_wifiEnabledWithoutWifiEntries_showProgressBarThenHideSearch() {
        Mockito.reset(mHandler);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        mInternetDialog.mConnectedWifiEntry = null;
        mInternetDialog.mWifiEntriesCount = 0;

        mInternetDialog.showProgressBar();

        // Show progress bar
        assertThat(mInternetDialog.mIsProgressBarVisible).isTrue();

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mHandler).postDelayed(runnableCaptor.capture(),
                eq(InternetDialog.PROGRESS_DELAY_MS));
        runnableCaptor.getValue().run();

        // Then hide searching sub-title only
        assertThat(mInternetDialog.mIsProgressBarVisible).isTrue();
        assertThat(mInternetDialog.mIsSearchingHidden).isTrue();
    }

    @Test
    public void getWifiListMaxCount_returnCountCorrectly() {
        // Both of the Ethernet, MobileData is hidden.
        // Then the maximum count is equal to MAX_WIFI_ENTRY_COUNT.
        setNetworkVisible(false, false, false);

        assertThat(mInternetDialog.getWifiListMaxCount()).isEqualTo(MAX_WIFI_ENTRY_COUNT);

        // If the Connected Wi-Fi is displayed then reduce one of the Wi-Fi list max count.
        setNetworkVisible(false, false, true);

        assertThat(mInternetDialog.getWifiListMaxCount()).isEqualTo(MAX_WIFI_ENTRY_COUNT - 1);

        // Only one of Ethernet, MobileData is displayed.
        // Then the maximum count is equal to MAX_WIFI_ENTRY_COUNT.
        setNetworkVisible(true, false, false);

        assertThat(mInternetDialog.getWifiListMaxCount()).isEqualTo(MAX_WIFI_ENTRY_COUNT);

        setNetworkVisible(false, true, false);

        assertThat(mInternetDialog.getWifiListMaxCount()).isEqualTo(MAX_WIFI_ENTRY_COUNT);

        // If the Connected Wi-Fi is displayed then reduce one of the Wi-Fi list max count.
        setNetworkVisible(true, false, true);

        assertThat(mInternetDialog.getWifiListMaxCount()).isEqualTo(MAX_WIFI_ENTRY_COUNT - 1);

        setNetworkVisible(false, true, true);

        assertThat(mInternetDialog.getWifiListMaxCount()).isEqualTo(MAX_WIFI_ENTRY_COUNT - 1);

        // Both of Ethernet, MobileData, ConnectedWiFi is displayed.
        // Then the maximum count is equal to MAX_WIFI_ENTRY_COUNT - 1.
        setNetworkVisible(true, true, false);

        assertThat(mInternetDialog.getWifiListMaxCount()).isEqualTo(MAX_WIFI_ENTRY_COUNT - 1);

        // If the Connected Wi-Fi is displayed then reduce one of the Wi-Fi list max count.
        setNetworkVisible(true, true, true);

        assertThat(mInternetDialog.getWifiListMaxCount()).isEqualTo(MAX_WIFI_ENTRY_COUNT - 2);
    }

    private void setNetworkVisible(boolean ethernetVisible, boolean mobileDataVisible,
            boolean connectedWifiVisible) {
        mEthernet.setVisibility(ethernetVisible ? View.VISIBLE : View.GONE);
        mMobileDataToggle.setVisibility(mobileDataVisible ? View.VISIBLE : View.GONE);
        mConnectedWifi.setVisibility(connectedWifiVisible ? View.VISIBLE : View.GONE);
    }
}

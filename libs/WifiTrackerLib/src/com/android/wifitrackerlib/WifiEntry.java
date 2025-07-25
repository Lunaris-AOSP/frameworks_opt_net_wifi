/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.wifitrackerlib;

import static android.net.wifi.WifiInfo.INVALID_RSSI;

import static androidx.core.util.Preconditions.checkNotNull;

import static com.android.wifitrackerlib.Utils.getNetworkPart;
import static com.android.wifitrackerlib.Utils.getSingleSecurityTypeFromMultipleSecurityTypes;

import android.content.Context;
import android.net.ConnectivityDiagnosticsManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.RouteInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.IntDef;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.os.BuildCompat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * Base class for an entry representing a Wi-Fi network in a Wi-Fi picker/settings.
 * Subclasses should override the default methods for their own needs.
 *
 * Clients implementing a Wi-Fi picker/settings should receive WifiEntry objects from classes
 * implementing BaseWifiTracker, and rely on the given API for all user-displayable information and
 * actions on the represented network.
 */
public class WifiEntry {
    public static final String TAG = "WifiEntry";

    private static final int MAX_UNDERLYING_NETWORK_DEPTH = 5;

    /**
     * Security type based on WifiConfiguration.KeyMgmt
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            SECURITY_NONE,
            SECURITY_OWE,
            SECURITY_WEP,
            SECURITY_PSK,
            SECURITY_SAE,
            SECURITY_EAP,
            SECURITY_EAP_SUITE_B,
            SECURITY_EAP_WPA3_ENTERPRISE,
    })

    public @interface Security {}

    public static final int SECURITY_NONE = 0;
    public static final int SECURITY_WEP = 1;
    public static final int SECURITY_PSK = 2;
    public static final int SECURITY_EAP = 3;
    public static final int SECURITY_OWE = 4;
    public static final int SECURITY_SAE = 5;
    public static final int SECURITY_EAP_SUITE_B = 6;
    public static final int SECURITY_EAP_WPA3_ENTERPRISE = 7;

    public static final int NUM_SECURITY_TYPES = 8;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            CONNECTED_STATE_DISCONNECTED,
            CONNECTED_STATE_CONNECTED,
            CONNECTED_STATE_CONNECTING
    })

    public @interface ConnectedState {}

    public static final int CONNECTED_STATE_DISCONNECTED = 0;
    public static final int CONNECTED_STATE_CONNECTING = 1;
    public static final int CONNECTED_STATE_CONNECTED = 2;

    // Wi-Fi signal levels for displaying signal strength.
    public static final int WIFI_LEVEL_MIN = 0;
    public static final int WIFI_LEVEL_MAX = 4;
    public static final int WIFI_LEVEL_UNREACHABLE = -1;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            METERED_CHOICE_AUTO,
            METERED_CHOICE_METERED,
            METERED_CHOICE_UNMETERED,
    })

    public @interface MeteredChoice {}

    // User's choice whether to treat a network as metered.
    public static final int METERED_CHOICE_AUTO = 0;
    public static final int METERED_CHOICE_METERED = 1;
    public static final int METERED_CHOICE_UNMETERED = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            PRIVACY_DEVICE_MAC,
            PRIVACY_RANDOMIZED_MAC,
            PRIVACY_UNKNOWN
    })

    public @interface Privacy {}

    public static final int PRIVACY_DEVICE_MAC = 0;
    public static final int PRIVACY_RANDOMIZED_MAC = 1;
    public static final int PRIVACY_UNKNOWN = 2;
    public static final int PRIVACY_RANDOMIZATION_ALWAYS = 100;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            FREQUENCY_2_4_GHZ,
            FREQUENCY_5_GHZ,
            FREQUENCY_6_GHZ,
            FREQUENCY_60_GHZ,
            FREQUENCY_UNKNOWN
    })

    public @interface Frequency {}

    public static final int FREQUENCY_2_4_GHZ = 2_400;
    public static final int FREQUENCY_5_GHZ = 5_000;
    public static final int FREQUENCY_6_GHZ = 6_000;
    public static final int FREQUENCY_60_GHZ = 60_000;
    public static final int FREQUENCY_UNKNOWN = -1;

    /**
     * Min bound on the 2.4 GHz (802.11b/g/n) WLAN channels.
     */
    public static final int MIN_FREQ_24GHZ = 2400;

    /**
     * Max bound on the 2.4 GHz (802.11b/g/n) WLAN channels.
     */
    public static final int MAX_FREQ_24GHZ = 2500;

    /**
     * Min bound on the 5.0 GHz (802.11a/h/j/n/ac) WLAN channels.
     */
    public static final int MIN_FREQ_5GHZ = 4900;

    /**
     * Max bound on the 5.0 GHz (802.11a/h/j/n/ac) WLAN channels.
     */
    public static final int MAX_FREQ_5GHZ = 5900;

    /**
     * Min bound on the 6.0 GHz (802.11ax) WLAN channels.
     */
    public static final int MIN_FREQ_6GHZ = 5925;

    /**
     * Max bound on the 6.0 GHz (802.11ax) WLAN channels.
     */
    public static final int MAX_FREQ_6GHZ = 7125;

    /**
     * Min bound on the 60 GHz (802.11ad) WLAN channels.
     */
    public static final int MIN_FREQ_60GHZ = 58320;

    /**
     * Max bound on the 60 GHz (802.11ad) WLAN channels.
     */
    public static final int MAX_FREQ_60GHZ = 70200;

    /**
     * Max ScanResult information displayed of Wi-Fi Verbose Logging.
     */
    protected static final int MAX_VERBOSE_LOG_DISPLAY_SCANRESULT_COUNT = 4;

    /**
     * Default comparator for sorting WifiEntries on a Wi-Fi picker list.
     */
    public static Comparator<WifiEntry> WIFI_PICKER_COMPARATOR =
            Comparator.comparing((WifiEntry entry) -> !entry.isPrimaryNetwork())
                    .thenComparing((WifiEntry entry) ->
                            entry.getConnectedState() != CONNECTED_STATE_CONNECTED)
                    .thenComparing((WifiEntry entry) -> !(entry instanceof KnownNetworkEntry))
                    .thenComparing((WifiEntry entry) -> !(entry instanceof HotspotNetworkEntry))
                    .thenComparing((WifiEntry entry) -> (entry instanceof HotspotNetworkEntry)
                            ? -((HotspotNetworkEntry) entry).getUpstreamConnectionStrength() : 0)
                    .thenComparing((WifiEntry entry) -> !entry.canConnect())
                    .thenComparing((WifiEntry entry) -> !entry.isSubscription())
                    .thenComparing((WifiEntry entry) -> !entry.isSaved())
                    .thenComparing((WifiEntry entry) -> !entry.isSuggestion())
                    .thenComparing((WifiEntry entry) -> -entry.getLevel())
                    .thenComparing((WifiEntry entry) -> entry.getTitle());

    /**
     * Default comparator for sorting WifiEntries by title.
     */
    public static Comparator<WifiEntry> TITLE_COMPARATOR =
            Comparator.comparing((WifiEntry entry) -> entry.getTitle());

    protected final boolean mForSavedNetworksPage;

    @NonNull protected final WifiTrackerInjector mInjector;
    @NonNull protected final Context mContext;
    protected final WifiManager mWifiManager;

    // Callback associated with this WifiEntry. Subclasses should call its methods appropriately.
    private WifiEntryCallback mListener;
    protected final Handler mCallbackHandler;
    protected int mWifiInfoLevel = WIFI_LEVEL_UNREACHABLE;
    protected int mScanResultLevel = WIFI_LEVEL_UNREACHABLE;
    protected WifiInfo mWifiInfo;
    protected NetworkInfo mNetworkInfo;
    protected Network mNetwork;
    protected Network mLastNetwork;
    protected NetworkCapabilities mNetworkCapabilities;
    protected Network mDefaultNetwork;
    protected NetworkCapabilities mDefaultNetworkCapabilities;
    protected ConnectivityDiagnosticsManager.ConnectivityReport mConnectivityReport;
    protected ConnectedInfo mConnectedInfo;

    protected ConnectCallback mConnectCallback;
    protected DisconnectCallback mDisconnectCallback;
    protected ForgetCallback mForgetCallback;

    protected boolean mCalledConnect = false;
    protected boolean mCalledDisconnect = false;


    private Optional<ManageSubscriptionAction> mManageSubscriptionAction = Optional.empty();

    public WifiEntry(@NonNull WifiTrackerInjector injector, @NonNull Handler callbackHandler,
            @NonNull WifiManager wifiManager, boolean forSavedNetworksPage)
            throws IllegalArgumentException {
        checkNotNull(injector, "Cannot construct with null injector!");
        checkNotNull(callbackHandler, "Cannot construct with null handler!");
        checkNotNull(wifiManager, "Cannot construct with null WifiManager!");
        mInjector = injector;
        mContext = mInjector.getContext();
        mCallbackHandler = callbackHandler;
        mForSavedNetworksPage = forSavedNetworksPage;
        mWifiManager = wifiManager;
    }

    // Info available for all WifiEntries //

    /** The unique key defining a WifiEntry */
    @NonNull
    public String getKey() {
        return "";
    };

    /** Returns connection state of the network defined by the CONNECTED_STATE constants */
    @ConnectedState
    public synchronized int getConnectedState() {
        // If we have NetworkCapabilities, then we're L3 connected.
        if (mNetworkCapabilities != null) {
            return CONNECTED_STATE_CONNECTED;
        }

        // Use NetworkInfo to provide the connecting state before we're L3 connected.
        if (mNetworkInfo != null) {
            switch (mNetworkInfo.getDetailedState()) {
                case SCANNING:
                case CONNECTING:
                case AUTHENTICATING:
                case OBTAINING_IPADDR:
                case VERIFYING_POOR_LINK:
                case CAPTIVE_PORTAL_CHECK:
                case CONNECTED:
                    return CONNECTED_STATE_CONNECTING;
                default:
                    return CONNECTED_STATE_DISCONNECTED;
            }
        }

        return CONNECTED_STATE_DISCONNECTED;
    }

    /** Returns the display title. This is most commonly the SSID of a network. */
    @NonNull
    public String getTitle() {
        return "";
    }

    /** Returns the display summary, it's a concise summary. */
    @NonNull
    public String getSummary() {
        return getSummary(true /* concise */);
    }

    /** Returns the second summary, it's for additional information of the WifiEntry */
    @NonNull
    public CharSequence getSecondSummary() {
        return "";
    }

    /**
     * Returns the display summary.
     * @param concise Whether to show more information. e.g., verbose logging.
     */
    @NonNull
    public String getSummary(boolean concise) {
        return "";
    };

    /**
     * Returns the signal strength level within [WIFI_LEVEL_MIN, WIFI_LEVEL_MAX].
     * A value of WIFI_LEVEL_UNREACHABLE indicates an out of range network.
     */
    public int getLevel() {
        if (mWifiInfoLevel != WIFI_LEVEL_UNREACHABLE) {
            return mWifiInfoLevel;
        }
        return mScanResultLevel;
    };

    /**
     * Returns whether the level icon for this network should show an X or not.
     * By default, this means any connected network that has no/low-quality internet access.
     */
    public boolean shouldShowXLevelIcon() {
        return getConnectedState() != CONNECTED_STATE_DISCONNECTED
                && mConnectivityReport != null
                && (!hasInternetAccess() || isLowQuality())
                && !canSignIn()
                && isPrimaryNetwork();
    }

    /**
     * Returns whether this network has validated internet access or not.
     * Note: This does not necessarily mean the network is the default route.
     */
    public synchronized boolean hasInternetAccess() {
        return mNetworkCapabilities != null
                && mNetworkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /**
     * Returns whether this network is the default network or not (i.e. this network is the one
     * currently being used to provide internet connection).
     */
    public synchronized boolean isDefaultNetwork() {
        if (mNetwork != null && mNetwork.equals(mDefaultNetwork)) {
            return true;
        }

        if (mLastNetwork != null && mLastNetwork.equals(mDefaultNetwork)) {
            // Last network may still be default if we've roamed and haven't gotten
            // onNetworkCapabilitiesChanged for the default network yet, so consider it default for
            // now.
            return true;
        }

        // Match based on the underlying networks if there are any (e.g. VPN).
        return doesUnderlyingNetworkMatch(mDefaultNetworkCapabilities, 0);
    }

    private boolean doesUnderlyingNetworkMatch(@Nullable NetworkCapabilities caps, int depth) {
        if (depth > MAX_UNDERLYING_NETWORK_DEPTH) {
            Log.e(TAG, "Underlying network depth greater than max depth of "
                    + MAX_UNDERLYING_NETWORK_DEPTH);
            return false;
        }

        if (caps == null) {
            return false;
        }

        List<Network> underlyingNetworks = BuildCompat.isAtLeastT()
                ? caps.getUnderlyingNetworks() : null;
        if (underlyingNetworks == null) {
            return false;
        }
        if (underlyingNetworks.contains(mNetwork)) {
            return true;
        }

        // Check the underlying networks of the underlying networks.
        ConnectivityManager connectivityManager = mInjector.getConnectivityManager();
        if (connectivityManager == null) {
            Log.wtf(TAG, "ConnectivityManager is null!");
            return false;
        }
        for (Network underlying : underlyingNetworks) {
            if (doesUnderlyingNetworkMatch(
                    connectivityManager.getNetworkCapabilities(underlying), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether this network is the primary Wi-Fi network or not.
     */
    public synchronized boolean isPrimaryNetwork() {
        if (getConnectedState() == CONNECTED_STATE_DISCONNECTED) {
            // In case we have mNetworkInfo but the state is disconnected.
            return false;
        }
        return mNetworkInfo != null
                || (mWifiInfo != null && NonSdkApiWrapper.isPrimary(mWifiInfo));
    }

    /**
     * Returns whether this network is considered low quality.
     */
    public synchronized boolean isLowQuality() {
        return isPrimaryNetwork() && hasInternetAccess() && !isDefaultNetwork()
                && mNetworkCapabilities != null
                && mNetworkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && mDefaultNetworkCapabilities != null
                && mDefaultNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                && !mDefaultNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                && mDefaultNetworkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
    }

    /**
     * Returns whether this network should display its SSID separately from the title
     * (e.g. the Network Details page), for networks whose display titles differ from the SSID.
     */
    public boolean shouldShowSsid() {
        return false;
    }

    /**
     * Returns the SSID of the entry, if applicable. Null otherwise.
     */
    @Nullable
    public String getSsid() {
        return null;
    }

    /**
     * Returns the security type defined by the SECURITY constants
     * @deprecated Use getSecurityTypes() which can return multiple security types.
     */
    // TODO(b/187554920): Remove this and move all clients to getSecurityTypes()
    @Deprecated
    @Security
    public int getSecurity() {
        switch (getSingleSecurityTypeFromMultipleSecurityTypes(getSecurityTypes())) {
            case WifiInfo.SECURITY_TYPE_OPEN:
                return SECURITY_NONE;
            case WifiInfo.SECURITY_TYPE_OWE:
                return SECURITY_OWE;
            case WifiInfo.SECURITY_TYPE_WEP:
                return SECURITY_WEP;
            case WifiInfo.SECURITY_TYPE_PSK:
                return SECURITY_PSK;
            case WifiInfo.SECURITY_TYPE_SAE:
                return SECURITY_SAE;
            case WifiInfo.SECURITY_TYPE_EAP:
                return SECURITY_EAP;
            case WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE:
                return SECURITY_EAP_WPA3_ENTERPRISE;
            case WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT:
                return SECURITY_EAP_SUITE_B;
            case WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2:
            case WifiInfo.SECURITY_TYPE_PASSPOINT_R3:
                return SECURITY_EAP;
            default:
                return SECURITY_NONE;
        }
    }

    /**
     * Returns security type of the current connection, or the available types for connection
     * in the form of the SECURITY_TYPE_* values in {@link WifiInfo}
     */
    @NonNull
    public List<Integer> getSecurityTypes() {
        return Collections.emptyList();
    }

    /** Returns the MAC address of the connection */
    @Nullable
    public String getMacAddress() {
        return null;
    }

    /**
     * Indicates when a network is metered or the user marked the network as metered.
     */
    public boolean isMetered() {
        return false;
    }

    /**
     * Indicates whether or not an entry is for a saved configuration.
     */
    public boolean isSaved() {
        return false;
    }

    /**
     * Indicates whether or not an entry is for a saved configuration.
     */
    public boolean isSuggestion() {
        return false;
    }

    /**
     * Indicates whether or not an entry is for a subscription.
     */
    public boolean isSubscription() {
        return false;
    }

    /**
     * Returns whether this entry needs to be configured with a new WifiConfiguration before
     * connection.
     */
    public boolean needsWifiConfiguration() {
        return false;
    }

    /**
     * Returns the WifiConfiguration of an entry or null if unavailable. This should be used when
     * information on the WifiConfiguration needs to be modified and saved via
     * {@link WifiManager#save(WifiConfiguration, WifiManager.ActionListener)}.
     */
    @Nullable
    public WifiConfiguration getWifiConfiguration() {
        return null;
    }

    /**
     * Returns the ConnectedInfo object pertaining to an active connection.
     *
     * Returns null if getConnectedState() != CONNECTED_STATE_CONNECTED.
     */
    @Nullable
    public synchronized ConnectedInfo getConnectedInfo() {
        if (getConnectedState() != CONNECTED_STATE_CONNECTED) {
            return null;
        }

        return new ConnectedInfo(mConnectedInfo);
    }

    /**
     * Info associated with the active connection.
     */
    public static class ConnectedInfo {
        @Frequency
        public int frequencyMhz;
        public List<String> dnsServers = new ArrayList<>();
        public int linkSpeedMbps;
        public String ipAddress;
        public List<String> ipv6Addresses = new ArrayList<>();
        public String gateway;
        public String subnetMask;
        public int wifiStandard = ScanResult.WIFI_STANDARD_UNKNOWN;
        public NetworkCapabilities networkCapabilities;

        /**
         * Creates an empty ConnectedInfo
         */
        public ConnectedInfo() {
        }

        /**
         * Creates a ConnectedInfo with all fields copied from an input ConnectedInfo
         */
        public ConnectedInfo(@NonNull ConnectedInfo other) {
            frequencyMhz = other.frequencyMhz;
            dnsServers = new ArrayList<>(dnsServers);
            linkSpeedMbps = other.linkSpeedMbps;
            ipAddress = other.ipAddress;
            ipv6Addresses = new ArrayList<>(other.ipv6Addresses);
            gateway = other.gateway;
            subnetMask = other.subnetMask;
            wifiStandard = other.wifiStandard;
            networkCapabilities = other.networkCapabilities;
        }
    }

    // User actions on a network

    /** Returns whether the entry should show a connect option */
    public boolean canConnect() {
        return false;
    }

    /** Connects to the network */
    public void connect(@Nullable ConnectCallback callback) {
        // Do nothing.
    }

    /** Returns whether the entry should show a disconnect option */
    public boolean canDisconnect() {
        return false;
    }

    /** Disconnects from the network */
    public void disconnect(@Nullable DisconnectCallback callback) {
        // Do nothing.
    }

    /** Returns whether the entry should show a forget option */
    public boolean canForget() {
        return false;
    }

    /** Forgets the network */
    public void forget(@Nullable ForgetCallback callback) {
        // Do nothing.
    }

    /** Returns whether the network can be signed-in to */
    public boolean canSignIn() {
        return false;
    }

    /** Sign-in to the network. For captive portals. */
    public void signIn(@Nullable SignInCallback callback) {
        // Do nothing.
    }

    /** Returns whether the network can be shared via QR code */
    public boolean canShare() {
        return false;
    }

    /** Returns whether the user can use Easy Connect to onboard a device to the network */
    public boolean canEasyConnect() {
        return false;
    }

    // Modifiable settings

    /**
     *  Returns the user's choice whether to treat a network as metered,
     *  defined by the METERED_CHOICE constants
     */
    @MeteredChoice
    public int getMeteredChoice() {
        return METERED_CHOICE_AUTO;
    }

    /** Returns whether the entry should let the user choose the metered treatment of a network */
    public boolean canSetMeteredChoice() {
        return false;
    }

    /**
     * Sets the user's choice for treating a network as metered,
     * defined by the METERED_CHOICE constants
     */
    public void setMeteredChoice(@MeteredChoice int meteredChoice) {
        // Do nothing.
    }

    /** Returns whether the entry should let the user choose the MAC randomization setting */
    public boolean canSetPrivacy() {
        return false;
    }

    /** Returns the MAC randomization setting defined by the PRIVACY constants */
    @Privacy
    public int getPrivacy() {
        return PRIVACY_UNKNOWN;
    }

    /** Sets the user's choice for MAC randomization defined by the PRIVACY constants */
    public void setPrivacy(@Privacy int privacy) {
        // Do nothing.
    }

    /** Returns whether the network has auto-join enabled */
    public boolean isAutoJoinEnabled() {
        return false;
    }

    /** Returns whether the user can enable/disable auto-join */
    public boolean canSetAutoJoinEnabled() {
        return false;
    }

    /** Sets whether a network will be auto-joined or not */
    public void setAutoJoinEnabled(boolean enabled) {
        // Do nothing.
    }

    /** Returns the string displayed for @Security */
    public String getSecurityString(boolean concise) {
        return "";
    }

    /** Returns the string displayed for the Wi-Fi standard */
    public String getStandardString() {
        return "";
    }

    /**
     * Info associated with the certificate based enterprise connection
     */
    public static class CertificateInfo {
        /**
         * Server certificate validation method. Used to show the security certificate strings in
         * the Network Details page.
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(value = {
                CERTIFICATE_VALIDATION_METHOD_USING_NONE,
                CERTIFICATE_VALIDATION_METHOD_USING_INSTALLED_ROOTCA,
                CERTIFICATE_VALIDATION_METHOD_USING_SYSTEM_CERTIFICATE,
                CERTIFICATE_VALIDATION_METHOD_USING_CERTIFICATE_PINNING,
        })

        public @interface CertificateValidationMethod {}
        public static final int CERTIFICATE_VALIDATION_METHOD_USING_NONE = 0;
        public static final int CERTIFICATE_VALIDATION_METHOD_USING_INSTALLED_ROOTCA = 1;
        public static final int CERTIFICATE_VALIDATION_METHOD_USING_SYSTEM_CERTIFICATE = 2;
        public static final int CERTIFICATE_VALIDATION_METHOD_USING_CERTIFICATE_PINNING = 3;

        public @CertificateValidationMethod int validationMethod;

        /** Non null only for  CERTIFICATE_VALIDATION_METHOD_USING_INSTALLED_ROOTCA */
        @Nullable public String[] caCertificateAliases;

        /** Domain name / server name */
        @Nullable public String domain;
    }

    /**
     * Returns the CertificateInfo to display, or null if it is not a certificate based connection.
     */
    @Nullable
    public CertificateInfo getCertificateInfo() {
        return null;
    }

    /** Returns the string displayed for the Wi-Fi band */
    public String getBandString() {
        return "";
    }

    /**
     * Returns the string displayed for Tx link speed.
     */
    public String getTxSpeedString() {
        return Utils.getSpeedString(mContext, mWifiInfo, /* isTx */ true);
    }

    /**
     * Returns the string displayed for Rx link speed.
     */
    public String getRxSpeedString() {
        return Utils.getSpeedString(mContext, mWifiInfo, /* isTx */ false);
    }

    /** Returns whether subscription of the entry is expired */
    public boolean isExpired() {
        return false;
    }


    /** Returns whether a user can manage their subscription through this WifiEntry */
    public boolean canManageSubscription() {
        return mManageSubscriptionAction.isPresent();
    };

    /**
     * Return the URI string value of help, if it is not null, WifiPicker may show
     * help icon and route the user to help page specified by the URI string.
     * see {@link Intent#parseUri}
     */
    @Nullable
    public String getHelpUriString() {
        return null;
    }

    /** Allows the user to manage their subscription via an external flow */
    public void manageSubscription() {
        mManageSubscriptionAction.ifPresent(ManageSubscriptionAction::onExecute);
    };

    /** Set the action to be called on calling WifiEntry#manageSubscription. */
    public void setManageSubscriptionAction(
            @NonNull ManageSubscriptionAction manageSubscriptionAction) {
        // only notify update on 1st time
        boolean notify = !mManageSubscriptionAction.isPresent();

        mManageSubscriptionAction = Optional.of(manageSubscriptionAction);
        if (notify) {
            notifyOnUpdated();
        }
    }

    /** Returns the ScanResult information of a WifiEntry */
    @NonNull
    protected String getScanResultDescription() {
        return "";
    }

    /** Returns the network selection information of a WifiEntry */
    @NonNull
    String getNetworkSelectionDescription() {
        return "";
    }

    /** Returns the network capability information of a WifiEntry */
    @NonNull
    String getNetworkCapabilityDescription() {
        final StringBuilder sb = new StringBuilder();
        if (getConnectedState() == CONNECTED_STATE_CONNECTED) {
            sb.append("hasInternet:")
                    .append(hasInternetAccess())
                    .append(", isDefaultNetwork:")
                    .append(isDefaultNetwork())
                    .append(", isLowQuality:")
                    .append(isLowQuality());
        }
        return sb.toString();
    }

    /**
     * In Wi-Fi picker, when users click a saved network, it will connect to the Wi-Fi network.
     * However, for some special cases, Wi-Fi picker should show Wi-Fi editor UI for users to edit
     * security or password before connecting. Or users will always get connection fail results.
     */
    public boolean shouldEditBeforeConnect() {
        return false;
    }

    /**
     * Whether there are admin restrictions preventing connection to this network.
     */
    public boolean hasAdminRestrictions() {
        return false;
    }

    /**
     * Sets the callback listener for WifiEntryCallback methods.
     * Subsequent calls will overwrite the previous listener.
     */
    public synchronized void setListener(WifiEntryCallback listener) {
        mListener = listener;
    }

    /**
     * Listener for changes to the state of the WifiEntry.
     * This callback will be invoked on the main thread.
     */
    public interface WifiEntryCallback {
        /**
         * Indicates the state of the WifiEntry has changed and clients may retrieve updates through
         * the WifiEntry getter methods.
         */
        @MainThread
        void onUpdated();
    }

    @AnyThread
    protected void notifyOnUpdated() {
        if (mListener != null) {
            mCallbackHandler.post(() -> {
                final WifiEntryCallback listener = mListener;
                if (listener != null) {
                    listener.onUpdated();
                }
            });
        }
    }

    /**
     * Listener for changes to the state of the WifiEntry.
     * This callback will be invoked on the main thread.
     */
    public interface ConnectCallback {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(value = {
                CONNECT_STATUS_SUCCESS,
                CONNECT_STATUS_FAILURE_NO_CONFIG,
                CONNECT_STATUS_FAILURE_UNKNOWN,
                CONNECT_STATUS_FAILURE_SIM_ABSENT
        })

        public @interface ConnectStatus {}

        int CONNECT_STATUS_SUCCESS = 0;
        int CONNECT_STATUS_FAILURE_NO_CONFIG = 1;
        int CONNECT_STATUS_FAILURE_UNKNOWN = 2;
        int CONNECT_STATUS_FAILURE_SIM_ABSENT = 3;

        /**
         * Result of the connect request indicated by the CONNECT_STATUS constants.
         */
        @MainThread
        void onConnectResult(@ConnectStatus int status);
    }

    /**
     * Listener for changes to the state of the WifiEntry.
     * This callback will be invoked on the main thread.
     */
    public interface DisconnectCallback {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(value = {
                DISCONNECT_STATUS_SUCCESS,
                DISCONNECT_STATUS_FAILURE_UNKNOWN
        })

        public @interface DisconnectStatus {}

        int DISCONNECT_STATUS_SUCCESS = 0;
        int DISCONNECT_STATUS_FAILURE_UNKNOWN = 1;
        /**
         * Result of the disconnect request indicated by the DISCONNECT_STATUS constants.
         */
        @MainThread
        void onDisconnectResult(@DisconnectStatus int status);
    }

    /**
     * Listener for changes to the state of the WifiEntry.
     * This callback will be invoked on the main thread.
     */
    public interface ForgetCallback {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(value = {
                FORGET_STATUS_SUCCESS,
                FORGET_STATUS_FAILURE_UNKNOWN
        })

        public @interface ForgetStatus {}

        int FORGET_STATUS_SUCCESS = 0;
        int FORGET_STATUS_FAILURE_UNKNOWN = 1;

        /**
         * Result of the forget request indicated by the FORGET_STATUS constants.
         */
        @MainThread
        void onForgetResult(@ForgetStatus int status);
    }

    /**
     * Listener for changes to the state of the WifiEntry.
     * This callback will be invoked on the main thread.
     */
    public interface SignInCallback {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(value = {
                SIGNIN_STATUS_SUCCESS,
                SIGNIN_STATUS_FAILURE_UNKNOWN
        })

        public @interface SignInStatus {}

        int SIGNIN_STATUS_SUCCESS = 0;
        int SIGNIN_STATUS_FAILURE_UNKNOWN = 1;

        /**
         * Result of the sign-in request indicated by the SIGNIN_STATUS constants.
         */
        @MainThread
        void onSignInResult(@SignInStatus int status);
    }

    /**
     * Returns whether the supplied WifiInfo represents this WifiEntry
     */
    boolean connectionInfoMatches(@NonNull WifiInfo wifiInfo) {
        return false;
    }

    /**
     * Updates this WifiEntry with the given primary WifiInfo/NetworkInfo if they match.
     * @param primaryWifiInfo Primary WifiInfo that has changed
     * @param networkInfo NetworkInfo of the primary network if available
     */
    synchronized void onPrimaryWifiInfoChanged(
            @Nullable WifiInfo primaryWifiInfo, @Nullable NetworkInfo networkInfo) {
        if (primaryWifiInfo == null || !connectionInfoMatches(primaryWifiInfo)) {
            if (mNetworkInfo != null) {
                mNetworkInfo = null;
                notifyOnUpdated();
            }
            return;
        }
        if (networkInfo != null) {
            mNetworkInfo = networkInfo;
        }
        updateWifiInfo(primaryWifiInfo);
        notifyOnUpdated();
    }

    /**
     * Updates this WifiEntry with the given NetworkCapabilities if it matches.
     */
    @WorkerThread
    synchronized void onNetworkCapabilitiesChanged(
            @NonNull Network network,
            @NonNull NetworkCapabilities capabilities) {
        WifiInfo wifiInfo = Utils.getWifiInfo(capabilities);
        if (wifiInfo == null) {
            return;
        }

        if (!connectionInfoMatches(wifiInfo)) {
            // WifiInfo doesn't match, so this network isn't for this WifiEntry.
            onNetworkLost(network);
            return;
        }

        // Treat non-primary, non-OEM connections as disconnected.
        if (!NonSdkApiWrapper.isPrimary(wifiInfo)
                && !NonSdkApiWrapper.isOemCapabilities(capabilities)) {
            onNetworkLost(network);
            return;
        }

        // Connection info matches, so the Network/NetworkCapabilities represent this network
        // and the network is currently connecting or connected.
        mLastNetwork = mNetwork;
        mNetwork = network;
        mNetworkCapabilities = capabilities;
        updateWifiInfo(wifiInfo);
        notifyOnUpdated();
    }

    protected synchronized void updateWifiInfo(WifiInfo wifiInfo) {
        if (wifiInfo == null) {
            mWifiInfo = null;
            mConnectedInfo = null;
            mWifiInfoLevel = WIFI_LEVEL_UNREACHABLE;
            updateSecurityTypes();
            return;
        }
        mWifiInfo = wifiInfo;
        final int wifiInfoRssi = mWifiInfo.getRssi();
        if (wifiInfoRssi != INVALID_RSSI) {
            mWifiInfoLevel = mWifiManager.calculateSignalLevel(wifiInfoRssi);
        }
        if (getConnectedState() == CONNECTED_STATE_CONNECTED) {
            if (mCalledConnect) {
                mCalledConnect = false;
                mCallbackHandler.post(() -> {
                    final ConnectCallback connectCallback = mConnectCallback;
                    if (connectCallback != null) {
                        connectCallback.onConnectResult(
                                ConnectCallback.CONNECT_STATUS_SUCCESS);
                    }
                });
            }

            if (mConnectedInfo == null) {
                mConnectedInfo = new ConnectedInfo();
            }
            mConnectedInfo.frequencyMhz = mWifiInfo.getFrequency();
            mConnectedInfo.linkSpeedMbps = mWifiInfo.getLinkSpeed();
            mConnectedInfo.wifiStandard = mWifiInfo.getWifiStandard();
        }
        updateSecurityTypes();
    }

    /**
     * Updates this WifiEntry as disconnected if the network matches.
     * @param network Network that was lost
     */
    synchronized void onNetworkLost(@NonNull Network network) {
        if (network.equals(mNetwork)) {
            clearConnectionInfo(true);
        } else if (network.equals(mLastNetwork)) {
            mLastNetwork = null;
            notifyOnUpdated();
        }
    }

    /**
     * Clears any connection info from this entry.
     */
    synchronized void clearConnectionInfo(boolean notify) {
        updateWifiInfo(null);
        mNetwork = null;
        mLastNetwork = null;
        mNetworkInfo = null;
        mNetworkCapabilities = null;
        mConnectivityReport = null;
        if (mCalledDisconnect) {
            mCalledDisconnect = false;
            mCallbackHandler.post(() -> {
                final DisconnectCallback disconnectCallback = mDisconnectCallback;
                if (disconnectCallback != null) {
                    disconnectCallback.onDisconnectResult(
                            DisconnectCallback.DISCONNECT_STATUS_SUCCESS);
                }
            });
        }
        if (notify) notifyOnUpdated();
    }

    /**
     * Updates this WifiEntry as the default network if it matches.
     */
    @WorkerThread
    synchronized void onDefaultNetworkCapabilitiesChanged(
            @NonNull Network network,
            @NonNull NetworkCapabilities capabilities) {
        mDefaultNetwork = network;
        mDefaultNetworkCapabilities = capabilities;
        notifyOnUpdated();
    }

    /**
     * Notifies this WifiEntry that the default network was lost.
     */
    synchronized void onDefaultNetworkLost() {
        mDefaultNetwork = null;
        mDefaultNetworkCapabilities = null;
        notifyOnUpdated();
    }

    // Called to indicate the security types should be updated to match new information about the
    // network.
    protected void updateSecurityTypes() {
        // Do nothing;
    }

    // Updates this WifiEntry's link properties if the network matches.
    @WorkerThread
    synchronized void updateLinkProperties(
            @NonNull Network network, @NonNull LinkProperties linkProperties) {
        if (!network.equals(mNetwork)) {
            return;
        }

        if (mConnectedInfo == null) {
            mConnectedInfo = new ConnectedInfo();
        }
        // Find IPv4 and IPv6 addresses, and subnet mask
        List<String> ipv6Addresses = new ArrayList<>();
        for (LinkAddress addr : linkProperties.getLinkAddresses()) {
            if (addr.getAddress() instanceof Inet4Address) {
                mConnectedInfo.ipAddress = addr.getAddress().getHostAddress();
                try {
                    InetAddress all = InetAddress.getByAddress(
                            new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255});
                    mConnectedInfo.subnetMask = getNetworkPart(
                            all, addr.getPrefixLength()).getHostAddress();
                } catch (UnknownHostException | IllegalArgumentException e) {
                    // Leave subnet null;
                }
            } else if (addr.getAddress() instanceof Inet6Address) {
                ipv6Addresses.add(addr.getAddress().getHostAddress());
            }
        }
        mConnectedInfo.ipv6Addresses = ipv6Addresses;

        // Find IPv4 default gateway.
        for (RouteInfo routeInfo : linkProperties.getRoutes()) {
            if (routeInfo.isDefaultRoute() && routeInfo.getDestination().getAddress()
                    instanceof Inet4Address && routeInfo.hasGateway()) {
                mConnectedInfo.gateway = routeInfo.getGateway().getHostAddress();
                break;
            }
        }

        // Find DNS servers
        mConnectedInfo.dnsServers = linkProperties.getDnsServers().stream()
                .map(InetAddress::getHostAddress).collect(Collectors.toList());

        notifyOnUpdated();
    }

    // Method for WifiTracker to update a connected WifiEntry's validation status.
    @WorkerThread
    synchronized void updateConnectivityReport(
            @NonNull ConnectivityDiagnosticsManager.ConnectivityReport connectivityReport) {
        if (connectivityReport.getNetwork().equals(mNetwork)) {
            mConnectivityReport = connectivityReport;
            notifyOnUpdated();
        }
    }

    synchronized String getWifiInfoDescription() {
        final StringJoiner sj = new StringJoiner(" ");
        if (getConnectedState() == CONNECTED_STATE_CONNECTED && mWifiInfo != null) {
            sj.add("f = " + mWifiInfo.getFrequency());
            final String bssid = mWifiInfo.getBSSID();
            if (bssid != null) {
                sj.add(bssid);
            }
            sj.add("standard = " + getStandardString());
            sj.add("rssi = " + mWifiInfo.getRssi());
            sj.add("score = " + mWifiInfo.getScore());
            sj.add(String.format(" tx=%.1f,", mWifiInfo.getSuccessfulTxPacketsPerSecond()));
            sj.add(String.format("%.1f,", mWifiInfo.getRetriedTxPacketsPerSecond()));
            sj.add(String.format("%.1f ", mWifiInfo.getLostTxPacketsPerSecond()));
            sj.add(String.format("rx=%.1f", mWifiInfo.getSuccessfulRxPacketsPerSecond()));
            if (BuildCompat.isAtLeastT() && mWifiInfo.getApMldMacAddress() != null) {
                sj.add("mldMac = " + mWifiInfo.getApMldMacAddress());
                sj.add("linkId = " + mWifiInfo.getApMloLinkId());
                sj.add("affLinks = " + Arrays.toString(
                        mWifiInfo.getAffiliatedMloLinks().toArray()));
            }
        }
        return sj.toString();
    }

    protected class ConnectActionListener implements WifiManager.ActionListener {
        @Override
        public void onSuccess() {
            synchronized (WifiEntry.this) {
                // Wait for L3 connection before returning the success result.
                mCalledConnect = true;
            }
        }

        @Override
        public void onFailure(int i) {
            mCallbackHandler.post(() -> {
                final ConnectCallback connectCallback = mConnectCallback;
                if (connectCallback != null) {
                    connectCallback.onConnectResult(ConnectCallback.CONNECT_STATUS_FAILURE_UNKNOWN);
                }
            });
        }
    }

    protected class ForgetActionListener implements WifiManager.ActionListener {
        @Override
        public void onSuccess() {
            mCallbackHandler.post(() -> {
                final ForgetCallback forgetCallback = mForgetCallback;
                if (forgetCallback != null) {
                    forgetCallback.onForgetResult(ForgetCallback.FORGET_STATUS_SUCCESS);
                }
            });
        }

        @Override
        public void onFailure(int i) {
            mCallbackHandler.post(() -> {
                final ForgetCallback forgetCallback = mForgetCallback;
                if (forgetCallback != null) {
                    forgetCallback.onForgetResult(ForgetCallback.FORGET_STATUS_FAILURE_UNKNOWN);
                }
            });
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof WifiEntry)) return false;
        return getKey().equals(((WifiEntry) other).getKey());
    }

    @Override
    public int hashCode() {
        return getKey().hashCode();
    }

    @Override
    public String toString() {
        StringJoiner sj = new StringJoiner("][", "[", "]");
        sj.add(this.getClass().getSimpleName());
        sj.add(getTitle());
        sj.add(getSummary());
        sj.add("Level:" + getLevel() + (shouldShowXLevelIcon() ? "!" : ""));
        String security = getSecurityString(true);
        if (!TextUtils.isEmpty(security)) {
            sj.add(security);
        }
        int connectedState = getConnectedState();
        if (connectedState == CONNECTED_STATE_CONNECTED) {
            sj.add("Connected");
        } else if (connectedState == CONNECTED_STATE_CONNECTING) {
            sj.add("Connecting...");
        }
        if (hasInternetAccess()) {
            sj.add("Internet");
        }
        if (isDefaultNetwork()) {
            sj.add("Default");
        }
        if (isPrimaryNetwork()) {
            sj.add("Primary");
        }
        if (isLowQuality()) {
            sj.add("LowQuality");
        }
        if (isSaved()) {
            sj.add("Saved");
        }
        if (isSubscription()) {
            sj.add("Subscription");
        }
        if (isSuggestion()) {
            sj.add("Suggestion");
        }
        if (isMetered()) {
            sj.add("Metered");
        }
        if ((isSaved() || isSuggestion() || isSubscription()) && !isAutoJoinEnabled()) {
            sj.add("AutoJoinDisabled");
        }
        if (isExpired()) {
            sj.add("Expired");
        }
        if (canSignIn()) {
            sj.add("SignIn");
        }
        if (shouldEditBeforeConnect()) {
            sj.add("EditBeforeConnect");
        }
        if (hasAdminRestrictions()) {
            sj.add("AdminRestricted");
        }
        return sj.toString();
    }

    /**
     * The action used to execute the calling of WifiEntry#manageSubscription.
     */
    public interface ManageSubscriptionAction {
        /**
         * Execute the action of managing subscription.
         */
        void onExecute();
    }

    /**
     * Whether this WifiEntry is using a verbose summary.
     */
    public boolean isVerboseSummaryEnabled() {
        return mInjector.isVerboseSummaryEnabled();
    }
}

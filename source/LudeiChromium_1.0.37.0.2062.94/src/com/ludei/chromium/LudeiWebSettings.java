// Copyright (c) 2012 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.ludei.chromium;

import android.content.pm.PackageManager;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.provider.Settings;
import android.webkit.WebSettings.PluginState;
import android.webkit.WebSettings;


import org.chromium.base.CalledByNative;
import org.chromium.base.JNINamespace;
import org.chromium.base.ThreadUtils;


/**
 * Stores Android WebView specific settings that does not need to be synced to WebKit.
 * Use {@link org.chromium.content.browser.ContentSettings} for WebKit settings.
 *
 * Methods in this class can be called from any thread, including threads created by
 * the client of WebView.
 */
@JNINamespace("ludei")
public class LudeiWebSettings {

    // This enum corresponds to WebSettings.LayoutAlgorithm. We use our own to be
    // able to extend it.
    public enum LayoutAlgorithm {
        NORMAL,
        SINGLE_COLUMN,
        NARROW_COLUMNS,
        TEXT_AUTOSIZING,
    }

    // This class must be created on the UI thread. Afterwards, it can be
    // used from any thread. Internally, the class uses a message queue
    // to call native code on the UI thread only.

    // Values passed in on construction.
    private final boolean mHasInternetPermission;

    private ZoomSupportChangeListener mZoomChangeListener;
    private double mDIPScale = 1.0;

    // Lock to protect all settings.
    private final Object mLudeiWebSettingsLock = new Object();

    private LayoutAlgorithm mLayoutAlgorithm =  LayoutAlgorithm.NARROW_COLUMNS;
    private int mTextSizePercent = 100;
    private String mStandardFontFamily = "sans-serif";
    private String mFixedFontFamily = "monospace";
    private String mSansSerifFontFamily = "sans-serif";
    private String mSerifFontFamily = "serif";
    private String mCursiveFontFamily = "cursive";
    private String mFantasyFontFamily = "fantasy";
    // TODO(mnaganov): Should be obtained from Android. Problem: it is hidden.
    private String mDefaultTextEncoding = "Latin-1";
    private String mUserAgent;
    private int mMinimumFontSize = 8;
    private int mMinimumLogicalFontSize = 8;
    private int mDefaultFontSize = 16;
    private int mDefaultFixedFontSize = 13;
    private boolean mLoadsImagesAutomatically = true;
    private boolean mImagesEnabled = true;
    private boolean mJavaScriptEnabled = true;
    private boolean mAllowUniversalAccessFromFileURLs = true;
    private boolean mAllowFileAccessFromFileURLs = true;
    private boolean mJavaScriptCanOpenWindowsAutomatically = false;
    private boolean mSupportMultipleWindows = false;
    private PluginState mPluginState = PluginState.OFF;
    private boolean mAppCacheEnabled = false;
    private boolean mDomStorageEnabled = false;
    private boolean mDatabaseEnabled = false;
    private boolean mUseWideViewport = false;
    private boolean mLoadWithOverviewMode = false;
    private boolean mMediaPlaybackRequiresUserGesture = true;
    private String mDefaultVideoPosterURL;
    private float mInitialPageScalePercent = 0;
    private boolean mSpatialNavigationEnabled;  // Default depends on device features.

    private final boolean mSupportLegacyQuirks;

    private final boolean mPasswordEchoEnabled;

    // Not accessed by the native side.
    private boolean mBlockNetworkLoads;  // Default depends on permission of embedding APK.
    private boolean mAllowContentUrlAccess = true;
    private boolean mAllowFileUrlAccess = true;
    private int mCacheMode = WebSettings.LOAD_DEFAULT;
    private boolean mShouldFocusFirstNode = true;
    private boolean mGeolocationEnabled = true;
    private boolean mAutoCompleteEnabled = true;
    private boolean mSupportZoom = true;
    private boolean mBuiltInZoomControls = false;
    private boolean mDisplayZoomControls = true;

    static class LazyDefaultUserAgent{
        // Lazy Holder pattern
        private static final String sInstance = nativeGetDefaultUserAgent();
    }

    // Protects access to settings global fields.
    private static final Object sGlobalContentSettingsLock = new Object();
    // For compatibility with the legacy WebView, we can only enable AppCache when the path is
    // provided. However, we don't use the path, so we just check if we have received it from the
    // client.
    private static boolean sAppCachePathIsSet = false;

    // The native side of this object. It's lifetime is bounded by the WebContent it is attached to.
    private long mNativeLudeiWebSettings = 0;
    private long mNativeWebContents = 0;

    // A flag to avoid sending superfluous synchronization messages.
    private boolean mIsUpdateWebkitPrefsMessagePending = false;
    // Custom handler that queues messages to call native code on the UI thread.
    private final EventHandler mEventHandler;

    private static final int MINIMUM_FONT_SIZE = 1;
    private static final int MAXIMUM_FONT_SIZE = 72;

    // Class to handle messages to be processed on the UI thread.
    private class EventHandler {
        // Message id for updating Webkit preferences
        private static final int UPDATE_WEBKIT_PREFERENCES = 0;
        // Actual UI thread handler
        private Handler mHandler;

        EventHandler() {
        }

        void bindUiThread() {
            if (mHandler != null) return;
            mHandler = new Handler(ThreadUtils.getUiThreadLooper()) {
                
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case UPDATE_WEBKIT_PREFERENCES:
                            synchronized (mLudeiWebSettingsLock) {
                                updateWebkitPreferencesOnUiThreadLocked();
                                mIsUpdateWebkitPrefsMessagePending = false;
                                mLudeiWebSettingsLock.notifyAll();
                            }
                            break;
                    }
                }
            };
        }

        void maybeRunOnUiThreadBlocking(Runnable r) {
            if (mHandler != null) {
                ThreadUtils.runOnUiThreadBlocking(r);
            }
        }

        void maybePostOnUiThread(Runnable r) {
            if (mHandler != null) {
                mHandler.post(r);
            }
        }

        private void updateWebkitPreferencesLocked() {
            assert Thread.holdsLock(mLudeiWebSettingsLock);
            if (mNativeLudeiWebSettings == 0) return;
            if (mHandler == null) return;
            if (ThreadUtils.runningOnUiThread()) {
                updateWebkitPreferencesOnUiThreadLocked();
            } else {
                // We're being called on a background thread, so post a message.
                if (mIsUpdateWebkitPrefsMessagePending) {
                    return;
                }
                mIsUpdateWebkitPrefsMessagePending = true;
                mHandler.sendMessage(Message.obtain(null, UPDATE_WEBKIT_PREFERENCES));
                // We must block until the settings have been sync'd to native to
                // ensure that they have taken effect.
                try {
                    while (mIsUpdateWebkitPrefsMessagePending) {
                        mLudeiWebSettingsLock.wait();
                    }
                } catch (InterruptedException e) {}
            }
        }
    }

    interface ZoomSupportChangeListener {
        public void onGestureZoomSupportChanged(boolean supportsGestureZoom);
    }

    public LudeiWebSettings(Context context,
                      boolean isAccessFromFileURLsGrantedByDefault,
                      boolean supportsLegacyQuirks) {
        boolean hasInternetPermission = context.checkPermission(
                android.Manifest.permission.INTERNET,
                Process.myPid(),
                Process.myUid()) == PackageManager.PERMISSION_GRANTED;
        synchronized (mLudeiWebSettingsLock) {
            mHasInternetPermission = hasInternetPermission;
            mBlockNetworkLoads = !hasInternetPermission;
            mEventHandler = new EventHandler();
            if (isAccessFromFileURLsGrantedByDefault) {
                mAllowUniversalAccessFromFileURLs = true;
                mAllowFileAccessFromFileURLs = true;
            }

            mUserAgent = LazyDefaultUserAgent.sInstance;

            // Best-guess a sensible initial value based on the features supported on the device.
            mSpatialNavigationEnabled = !context.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_TOUCHSCREEN);

            // Respect the system setting for password echoing.
            mPasswordEchoEnabled = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.TEXT_SHOW_PASSWORD, 1) == 1;

            mSupportLegacyQuirks = supportsLegacyQuirks;
        }
        // Defer initializing the native side until a native WebContents instance is set.
    }

    @CalledByNative
    private void nativeLudeiWebSettingsGone(long nativeLudeiWebSettings) {
        assert mNativeLudeiWebSettings != 0 && mNativeLudeiWebSettings == nativeLudeiWebSettings;
        mNativeLudeiWebSettings = 0;
    }

    //@CalledByNative
    private double getDIPScaleLocked() {
        return mDIPScale;
    }

    void setDIPScale(double dipScale) {
        synchronized (mLudeiWebSettingsLock) {
            mDIPScale = dipScale;
            // TODO(joth): This should also be synced over to native side, but right now
            // the setDIPScale call is always followed by a setWebContents() which covers this.
        }
    }

    void setZoomListener(ZoomSupportChangeListener zoomChangeListener) {
        synchronized (mLudeiWebSettingsLock) {
            mZoomChangeListener = zoomChangeListener;
        }
    }

    void setWebContents(long nativeWebContents) {
        synchronized (mLudeiWebSettingsLock) {
            mNativeWebContents = nativeWebContents;
            if (mNativeLudeiWebSettings != 0) {
                nativeDestroy(mNativeLudeiWebSettings);
                assert mNativeLudeiWebSettings == 0;  // nativeLudeiWebSettingsGone should have been called.
            }
            if (nativeWebContents != 0) {
                mEventHandler.bindUiThread();
                mNativeLudeiWebSettings = nativeInit(nativeWebContents);
                nativeUpdateEverythingLocked(mNativeLudeiWebSettings);
                onGestureZoomSupportChanged(supportsGestureZoomLocked());
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setBlockNetworkLoads}.
     */
    
    public void setBlockNetworkLoads(boolean flag) {
        synchronized (mLudeiWebSettingsLock) {
            if (!flag && !mHasInternetPermission) {
                throw new SecurityException("Permission denied - " +
                        "application missing INTERNET permission");
            }
            mBlockNetworkLoads = flag;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getBlockNetworkLoads}.
     */
    
    public boolean getBlockNetworkLoads() {
        synchronized (mLudeiWebSettingsLock) {
            return mBlockNetworkLoads;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setAllowFileAccess}.
     */
    
    public void setAllowFileAccess(boolean allow) {
        synchronized (mLudeiWebSettingsLock) {
            if (mAllowFileUrlAccess != allow) {
                mAllowFileUrlAccess = allow;
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getAllowFileAccess}.
     */
    
    public boolean getAllowFileAccess() {
        synchronized (mLudeiWebSettingsLock) {
            return mAllowFileUrlAccess;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setAllowContentAccess}.
     */
    
    public void setAllowContentAccess(boolean allow) {
        synchronized (mLudeiWebSettingsLock) {
            if (mAllowContentUrlAccess != allow) {
                mAllowContentUrlAccess = allow;
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getAllowContentAccess}.
     */
    
    public boolean getAllowContentAccess() {
        synchronized (mLudeiWebSettingsLock) {
            return mAllowContentUrlAccess;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setCacheMode}.
     */
    
    public void setCacheMode(int mode) {
        synchronized (mLudeiWebSettingsLock) {
            if (mCacheMode != mode) {
                mCacheMode = mode;
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getCacheMode}.
     */
    
    public int getCacheMode() {
        synchronized (mLudeiWebSettingsLock) {
            return mCacheMode;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setNeedInitialFocus}.
     */
    public void setShouldFocusFirstNode(boolean flag) {
        synchronized (mLudeiWebSettingsLock) {
            mShouldFocusFirstNode = flag;
        }
    }

    /**
     * See {@link android.webkit.WebView#setInitialScale}.
     */
    //
    public void setInitialPageScale(final float scaleInPercent) {
        synchronized (mLudeiWebSettingsLock) {
            if (mInitialPageScalePercent != scaleInPercent) {
                mInitialPageScalePercent = scaleInPercent;
                mEventHandler.maybeRunOnUiThreadBlocking(new Runnable() {
                    
                    public void run() {
                        if (mNativeLudeiWebSettings != 0) {
                            nativeUpdateInitialPageScaleLocked(mNativeLudeiWebSettings);
                        }
                    }
                });
            }
        }
    }

    //@CalledByNative
    private float getInitialPageScalePercentLocked() {
        return mInitialPageScalePercent;
    }

    void setSpatialNavigationEnabled(boolean enable) {
        synchronized (mLudeiWebSettingsLock) {
            if (mSpatialNavigationEnabled != enable) {
                mSpatialNavigationEnabled = enable;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    @CalledByNative
    private boolean getSpatialNavigationLocked() {
        return mSpatialNavigationEnabled;
    }

    /**
     * See {@link android.webkit.WebSettings#setNeedInitialFocus}.
     */
    //
    public boolean shouldFocusFirstNode() {
        synchronized(mLudeiWebSettingsLock) {
            return mShouldFocusFirstNode;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setGeolocationEnabled}.
     */
    
    public void setGeolocationEnabled(boolean flag) {
        synchronized (mLudeiWebSettingsLock) {
            if (mGeolocationEnabled != flag) {
                mGeolocationEnabled = flag;
            }
        }
    }

    /**
     * @return Returns if geolocation is currently enabled.
     */
    boolean getGeolocationEnabled() {
        synchronized (mLudeiWebSettingsLock) {
            return mGeolocationEnabled;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setSaveFormData}.
     */
    
    public void setSaveFormData(final boolean enable) {
        synchronized (mLudeiWebSettingsLock) {
            if (mAutoCompleteEnabled != enable) {
                mAutoCompleteEnabled = enable;
                mEventHandler.maybeRunOnUiThreadBlocking(new Runnable() {
                    
                    public void run() {
                        if (mNativeLudeiWebSettings != 0) {
                            nativeUpdateFormDataPreferencesLocked(mNativeLudeiWebSettings);
                        }
                    }
                });
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getSaveFormData}.
     */
    
    public boolean getSaveFormData() {
        synchronized (mLudeiWebSettingsLock) {
            return getSaveFormDataLocked();
        }
    }

    //@CalledByNative
    private boolean getSaveFormDataLocked() {
        return mAutoCompleteEnabled;
    }

    /**
     * @returns the default User-Agent used by each ContentViewCore instance, i.e. unless
     * overridden by {@link #setUserAgentString()}
     */
    public static String getDefaultUserAgent() {
        return LazyDefaultUserAgent.sInstance;
    }

    /**
     * See {@link android.webkit.WebSettings#setUserAgentString}.
     */
    
    public void setUserAgentString(String ua) {
        synchronized (mLudeiWebSettingsLock) {
            final String oldUserAgent = mUserAgent;
            if (ua == null || ua.length() == 0) {
                mUserAgent = LazyDefaultUserAgent.sInstance;
            } else {
                mUserAgent = ua;
            }
            if (!oldUserAgent.equals(mUserAgent)) {
                mEventHandler.maybeRunOnUiThreadBlocking(new Runnable() {
                    
                    public void run() {
                        if (mNativeLudeiWebSettings != 0) {
                            nativeUpdateUserAgentLocked(mNativeLudeiWebSettings);
                        }
                    }
                });
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getUserAgentString}.
     */
    
    public String getUserAgentString() {
        synchronized (mLudeiWebSettingsLock) {
            return mUserAgent;
        }
    }

    @CalledByNative
    private String getUserAgentLocked() {
        return mUserAgent;
    }

    /**
     * See {@link android.webkit.WebSettings#setLoadWithOverviewMode}.
     */
    
    public void setLoadWithOverviewMode(boolean overview) {
        synchronized (mLudeiWebSettingsLock) {
            if (mLoadWithOverviewMode != overview) {
                mLoadWithOverviewMode = overview;
                mEventHandler.updateWebkitPreferencesLocked();
                mEventHandler.maybeRunOnUiThreadBlocking(new Runnable() {
                    
                    public void run() {
                        if (mNativeLudeiWebSettings != 0) {
                            nativeResetScrollAndScaleState(mNativeLudeiWebSettings);
                        }
                    }
                });
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getLoadWithOverviewMode}.
     */
    
    public boolean getLoadWithOverviewMode() {
        synchronized (mLudeiWebSettingsLock) {
            return mLoadWithOverviewMode;
        }
    }

    @CalledByNative
    private boolean getLoadWithOverviewModeLocked() {
        return mLoadWithOverviewMode;
    }

    /**
     * See {@link android.webkit.WebSettings#setTextZoom}.
     */
    
    public void setTextZoom(final int textZoom) {
        synchronized (mLudeiWebSettingsLock) {
            if (mTextSizePercent != textZoom) {
                mTextSizePercent = textZoom;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getTextZoom}.
     */
    
    public int getTextZoom() {
        synchronized (mLudeiWebSettingsLock) {
            return mTextSizePercent;
        }
    }

    @CalledByNative
    private int getTextSizePercentLocked() {
        return mTextSizePercent;
    }

    /**
     * See {@link android.webkit.WebSettings#setStandardFontFamily}.
     */
    
    public void setStandardFontFamily(String font) {
        synchronized (mLudeiWebSettingsLock) {
            if (font != null && !mStandardFontFamily.equals(font)) {
                mStandardFontFamily = font;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getStandardFontFamily}.
     */
    
    public String getStandardFontFamily() {
        synchronized (mLudeiWebSettingsLock) {
            return mStandardFontFamily;
        }
    }

    @CalledByNative
    private String getStandardFontFamilyLocked() {
        return mStandardFontFamily;
    }

    /**
     * See {@link android.webkit.WebSettings#setFixedFontFamily}.
     */
    
    public void setFixedFontFamily(String font) {
        synchronized (mLudeiWebSettingsLock) {
            if (font != null && !mFixedFontFamily.equals(font)) {
                mFixedFontFamily = font;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getFixedFontFamily}.
     */
    
    public String getFixedFontFamily() {
        synchronized (mLudeiWebSettingsLock) {
            return mFixedFontFamily;
        }
    }

    @CalledByNative
    private String getFixedFontFamilyLocked() {
        return mFixedFontFamily;
    }

    /**
     * See {@link android.webkit.WebSettings#setSansSerifFontFamily}.
     */
    
    public void setSansSerifFontFamily(String font) {
        synchronized (mLudeiWebSettingsLock) {
            if (font != null && !mSansSerifFontFamily.equals(font)) {
                mSansSerifFontFamily = font;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getSansSerifFontFamily}.
     */
    
    public String getSansSerifFontFamily() {
        synchronized (mLudeiWebSettingsLock) {
            return mSansSerifFontFamily;
        }
    }

    @CalledByNative
    private String getSansSerifFontFamilyLocked() {
        return mSansSerifFontFamily;
    }

    /**
     * See {@link android.webkit.WebSettings#setSerifFontFamily}.
     */
    
    public void setSerifFontFamily(String font) {
        synchronized (mLudeiWebSettingsLock) {
            if (font != null && !mSerifFontFamily.equals(font)) {
                mSerifFontFamily = font;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getSerifFontFamily}.
     */
    
    public String getSerifFontFamily() {
        synchronized (mLudeiWebSettingsLock) {
            return mSerifFontFamily;
        }
    }

    @CalledByNative
    private String getSerifFontFamilyLocked() {
        return mSerifFontFamily;
    }

    /**
     * See {@link android.webkit.WebSettings#setCursiveFontFamily}.
     */
    
    public void setCursiveFontFamily(String font) {
        synchronized (mLudeiWebSettingsLock) {
            if (font != null && !mCursiveFontFamily.equals(font)) {
                mCursiveFontFamily = font;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getCursiveFontFamily}.
     */
    
    public String getCursiveFontFamily() {
        synchronized (mLudeiWebSettingsLock) {
            return mCursiveFontFamily;
        }
    }

    @CalledByNative
    private String getCursiveFontFamilyLocked() {
        return mCursiveFontFamily;
    }

    /**
     * See {@link android.webkit.WebSettings#setFantasyFontFamily}.
     */
    
    public void setFantasyFontFamily(String font) {
        synchronized (mLudeiWebSettingsLock) {
            if (font != null && !mFantasyFontFamily.equals(font)) {
                mFantasyFontFamily = font;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getFantasyFontFamily}.
     */
    
    public String getFantasyFontFamily() {
        synchronized (mLudeiWebSettingsLock) {
            return mFantasyFontFamily;
        }
    }

    @CalledByNative
    private String getFantasyFontFamilyLocked() {
        return mFantasyFontFamily;
    }

    /**
     * See {@link android.webkit.WebSettings#setMinimumFontSize}.
     */
    
    public void setMinimumFontSize(int size) {
        synchronized (mLudeiWebSettingsLock) {
            size = clipFontSize(size);
            if (mMinimumFontSize != size) {
                mMinimumFontSize = size;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getMinimumFontSize}.
     */
    
    public int getMinimumFontSize() {
        synchronized (mLudeiWebSettingsLock) {
            return mMinimumFontSize;
        }
    }

    @CalledByNative
    private int getMinimumFontSizeLocked() {
        return mMinimumFontSize;
    }

    /**
     * See {@link android.webkit.WebSettings#setMinimumLogicalFontSize}.
     */
    
    public void setMinimumLogicalFontSize(int size) {
        synchronized (mLudeiWebSettingsLock) {
            size = clipFontSize(size);
            if (mMinimumLogicalFontSize != size) {
                mMinimumLogicalFontSize = size;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getMinimumLogicalFontSize}.
     */
    
    public int getMinimumLogicalFontSize() {
        synchronized (mLudeiWebSettingsLock) {
            return mMinimumLogicalFontSize;
        }
    }

    @CalledByNative
    private int getMinimumLogicalFontSizeLocked() {
        return mMinimumLogicalFontSize;
    }

    /**
     * See {@link android.webkit.WebSettings#setDefaultFontSize}.
     */
    
    public void setDefaultFontSize(int size) {
        synchronized (mLudeiWebSettingsLock) {
            size = clipFontSize(size);
            if (mDefaultFontSize != size) {
                mDefaultFontSize = size;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getDefaultFontSize}.
     */
    
    public int getDefaultFontSize() {
        synchronized (mLudeiWebSettingsLock) {
            return mDefaultFontSize;
        }
    }

    @CalledByNative
    private int getDefaultFontSizeLocked() {
        return mDefaultFontSize;
    }

    /**
     * See {@link android.webkit.WebSettings#setDefaultFixedFontSize}.
     */
    
    public void setDefaultFixedFontSize(int size) {
        synchronized (mLudeiWebSettingsLock) {
            size = clipFontSize(size);
            if (mDefaultFixedFontSize != size) {
                mDefaultFixedFontSize = size;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getDefaultFixedFontSize}.
     */
    
    public int getDefaultFixedFontSize() {
        synchronized (mLudeiWebSettingsLock) {
            return mDefaultFixedFontSize;
        }
    }

    @CalledByNative
    private int getDefaultFixedFontSizeLocked() {
        return mDefaultFixedFontSize;
    }

    /**
     * See {@link android.webkit.WebSettings#setJavaScriptEnabled}.
     */
    
    public void setJavaScriptEnabled(boolean flag) {
        synchronized (mLudeiWebSettingsLock) {
            if (mJavaScriptEnabled != flag) {
                mJavaScriptEnabled = flag;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setAllowUniversalAccessFromFileURLs}.
     */
    
    public void setAllowUniversalAccessFromFileURLs(boolean flag) {
        synchronized (mLudeiWebSettingsLock) {
            if (mAllowUniversalAccessFromFileURLs != flag) {
                mAllowUniversalAccessFromFileURLs = flag;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setAllowFileAccessFromFileURLs}.
     */
    
    public void setAllowFileAccessFromFileURLs(boolean flag) {
        synchronized (mLudeiWebSettingsLock) {
            if (mAllowFileAccessFromFileURLs != flag) {
                mAllowFileAccessFromFileURLs = flag;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setLoadsImagesAutomatically}.
     */
    
    public void setLoadsImagesAutomatically(boolean flag) {
        synchronized (mLudeiWebSettingsLock) {
            if (mLoadsImagesAutomatically != flag) {
                mLoadsImagesAutomatically = flag;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getLoadsImagesAutomatically}.
     */
    
    public boolean getLoadsImagesAutomatically() {
        synchronized (mLudeiWebSettingsLock) {
            return mLoadsImagesAutomatically;
        }
    }

    @CalledByNative
    private boolean getLoadsImagesAutomaticallyLocked() {
        return mLoadsImagesAutomatically;
    }

    /**
     * See {@link android.webkit.WebSettings#setImagesEnabled}.
     */
    //
    public void setImagesEnabled(boolean flag) {
        synchronized (mLudeiWebSettingsLock) {
            if (mImagesEnabled != flag) {
                mImagesEnabled = flag;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getImagesEnabled}.
     */
    //
    public boolean getImagesEnabled() {
        synchronized (mLudeiWebSettingsLock) {
            return mImagesEnabled;
        }
    }

    @CalledByNative
    private boolean getImagesEnabledLocked() {
        return mImagesEnabled;
    }

    /**
     * See {@link android.webkit.WebSettings#getJavaScriptEnabled}.
     */
    
    public boolean getJavaScriptEnabled() {
        synchronized (mLudeiWebSettingsLock) {
            return mJavaScriptEnabled;
        }
    }

    @CalledByNative
    private boolean getJavaScriptEnabledLocked() {
        return mJavaScriptEnabled;
    }

    /**
     * See {@link android.webkit.WebSettings#getAllowUniversalAccessFromFileURLs}.
     */
    
    public boolean getAllowUniversalAccessFromFileURLs() {
        synchronized (mLudeiWebSettingsLock) {
            return mAllowUniversalAccessFromFileURLs;
        }
    }

    @CalledByNative
    private boolean getAllowUniversalAccessFromFileURLsLocked() {
        return mAllowUniversalAccessFromFileURLs;
    }

    /**
     * See {@link android.webkit.WebSettings#getAllowFileAccessFromFileURLs}.
     */
    
    public boolean getAllowFileAccessFromFileURLs() {
        synchronized (mLudeiWebSettingsLock) {
            return mAllowFileAccessFromFileURLs;
        }
    }

    @CalledByNative
    private boolean getAllowFileAccessFromFileURLsLocked() {
        return mAllowFileAccessFromFileURLs;
    }

    /**
     * See {@link android.webkit.WebSettings#setPluginsEnabled}.
     */
    //
    public void setPluginsEnabled(boolean flag) {
        setPluginState(flag ? PluginState.ON : PluginState.OFF);
    }

    /**
     * See {@link android.webkit.WebSettings#setPluginState}.
     */
    //
    public void setPluginState(PluginState state) {
        synchronized (mLudeiWebSettingsLock) {
            if (mPluginState != state) {
                mPluginState = state;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getPluginsEnabled}.
     */
    //
    public boolean getPluginsEnabled() {
        synchronized (mLudeiWebSettingsLock) {
            return mPluginState == PluginState.ON;
        }
    }

    /**
     * Return true if plugins are disabled.
     * @return True if plugins are disabled.
     * @hide
     */
    @CalledByNative
    private boolean getPluginsDisabledLocked() {
        return mPluginState == PluginState.OFF;
    }

    /**
     * See {@link android.webkit.WebSettings#getPluginState}.
     */
    
    public PluginState getPluginState() {
        synchronized (mLudeiWebSettingsLock) {
            return mPluginState;
        }
    }


    /**
     * See {@link android.webkit.WebSettings#setJavaScriptCanOpenWindowsAutomatically}.
     */
    
    public void setJavaScriptCanOpenWindowsAutomatically(boolean flag) {
        synchronized (mLudeiWebSettingsLock) {
            if (mJavaScriptCanOpenWindowsAutomatically != flag) {
                mJavaScriptCanOpenWindowsAutomatically = flag;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getJavaScriptCanOpenWindowsAutomatically}.
     */
    public boolean getJavaScriptCanOpenWindowsAutomatically() {
        synchronized (mLudeiWebSettingsLock) {
            return mJavaScriptCanOpenWindowsAutomatically;
        }
    }

    @CalledByNative
    private boolean getJavaScriptCanOpenWindowsAutomaticallyLocked() {
        return mJavaScriptCanOpenWindowsAutomatically;
    }

    /**
     * See {@link android.webkit.WebSettings#setLayoutAlgorithm}.
     */
    
    public void setLayoutAlgorithm(LayoutAlgorithm l) {
        synchronized (mLudeiWebSettingsLock) {
            if (mLayoutAlgorithm != l) {
                mLayoutAlgorithm = l;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getLayoutAlgorithm}.
     */
    
    public LayoutAlgorithm getLayoutAlgorithm() {
        synchronized (mLudeiWebSettingsLock) {
            return mLayoutAlgorithm;
        }
    }

    /**
     * Gets whether Text Auto-sizing layout algorithm is enabled.
     *
     * @return true if Text Auto-sizing layout algorithm is enabled
     * @hide
     */
    @CalledByNative
    private boolean getTextAutosizingEnabledLocked() {
        return mLayoutAlgorithm == LayoutAlgorithm.TEXT_AUTOSIZING;
    }

    /**
     * See {@link android.webkit.WebSettings#setSupportMultipleWindows}.
     */
    
    public void setSupportMultipleWindows(boolean support) {
        synchronized (mLudeiWebSettingsLock) {
            if (mSupportMultipleWindows != support) {
                mSupportMultipleWindows = support;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#supportMultipleWindows}.
     */
    
    public boolean supportMultipleWindows() {
        synchronized (mLudeiWebSettingsLock) {
            return mSupportMultipleWindows;
        }
    }

    @CalledByNative
    private boolean getSupportMultipleWindowsLocked() {
        return mSupportMultipleWindows;
    }

    @CalledByNative
    private boolean getSupportLegacyQuirksLocked() {
        return mSupportLegacyQuirks;
    }

    /**
     * See {@link android.webkit.WebSettings#setUseWideViewPort}.
     */
    
    public void setUseWideViewPort(boolean use) {
        synchronized (mLudeiWebSettingsLock) {
            if (mUseWideViewport != use) {
                mUseWideViewport = use;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getUseWideViewPort}.
     */
    
    public boolean getUseWideViewPort() {
        synchronized (mLudeiWebSettingsLock) {
            return mUseWideViewport;
        }
    }

    @CalledByNative
    private boolean getUseWideViewportLocked() {
        return mUseWideViewport;
    }

    @CalledByNative
    private boolean getPasswordEchoEnabledLocked() {
        return mPasswordEchoEnabled;
    }

    /**
     * See {@link android.webkit.WebSettings#setAppCacheEnabled}.
     */
    
    public void setAppCacheEnabled(boolean flag) {
        synchronized (mLudeiWebSettingsLock) {
            if (mAppCacheEnabled != flag) {
                mAppCacheEnabled = flag;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setAppCachePath}.
     */
    
    public void setAppCachePath(String path) {
        boolean needToSync = false;
        synchronized (sGlobalContentSettingsLock) {
            // AppCachePath can only be set once.
            if (!sAppCachePathIsSet && path != null && !path.isEmpty()) {
                sAppCachePathIsSet = true;
                needToSync = true;
            }
        }
        // The obvious problem here is that other WebViews will not be updated,
        // until they execute synchronization from Java to the native side.
        // But this is the same behaviour as it was in the legacy WebView.
        if (needToSync) {
            synchronized (mLudeiWebSettingsLock) {
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * Gets whether Application Cache is enabled.
     *
     * @return true if Application Cache is enabled
     * @hide
     */
    @CalledByNative
    private boolean getAppCacheEnabledLocked() {

        if (true) {
            return false;
        }
        if (!mAppCacheEnabled) {
            return false;
        }
        synchronized (sGlobalContentSettingsLock) {
            return sAppCachePathIsSet;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setDomStorageEnabled}.
     */
    
    public void setDomStorageEnabled(boolean flag) {
        synchronized (mLudeiWebSettingsLock) {
            if (mDomStorageEnabled != flag) {
                mDomStorageEnabled = flag;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getDomStorageEnabled}.
     */
    
    public boolean getDomStorageEnabled() {
        synchronized (mLudeiWebSettingsLock) {
            return mDomStorageEnabled;
        }
    }

    @CalledByNative
    private boolean getDomStorageEnabledLocked() {
        return mDomStorageEnabled;
    }

    /**
     * See {@link android.webkit.WebSettings#setDatabaseEnabled}.
     */
    
    public void setDatabaseEnabled(boolean flag) {
        synchronized (mLudeiWebSettingsLock) {
            if (mDatabaseEnabled != flag) {
                mDatabaseEnabled = flag;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getDatabaseEnabled}.
     */
    
    public boolean getDatabaseEnabled() {
        synchronized (mLudeiWebSettingsLock) {
            return mDatabaseEnabled;
        }
    }

    @CalledByNative
    private boolean getDatabaseEnabledLocked() {
        return mDatabaseEnabled;
    }

    /**
     * See {@link android.webkit.WebSettings#setDefaultTextEncodingName}.
     */
    
    public void setDefaultTextEncodingName(String encoding) {
        synchronized (mLudeiWebSettingsLock) {
            if (encoding != null && !mDefaultTextEncoding.equals(encoding)) {
                mDefaultTextEncoding = encoding;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getDefaultTextEncodingName}.
     */
    
    public String getDefaultTextEncodingName() {
        synchronized (mLudeiWebSettingsLock) {
            return mDefaultTextEncoding;
        }
    }

    @CalledByNative
    private String getDefaultTextEncodingLocked() {
        return mDefaultTextEncoding;
    }

    /**
     * See {@link android.webkit.WebSettings#setMediaPlaybackRequiresUserGesture}.
     */
    
    public void setMediaPlaybackRequiresUserGesture(boolean require) {
        synchronized (mLudeiWebSettingsLock) {
            if (mMediaPlaybackRequiresUserGesture != require) {
                mMediaPlaybackRequiresUserGesture = require;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getMediaPlaybackRequiresUserGesture}.
     */
    
    public boolean getMediaPlaybackRequiresUserGesture() {
        synchronized (mLudeiWebSettingsLock) {
            return mMediaPlaybackRequiresUserGesture;
        }
    }

    @CalledByNative
    private boolean getMediaPlaybackRequiresUserGestureLocked() {
        return mMediaPlaybackRequiresUserGesture;
    }

    /**
     * See {@link android.webkit.WebSettings#setDefaultVideoPosterURL}.
     */
    //
    public void setDefaultVideoPosterURL(String url) {
        synchronized (mLudeiWebSettingsLock) {
            if (mDefaultVideoPosterURL != null && !mDefaultVideoPosterURL.equals(url) ||
                    mDefaultVideoPosterURL == null && url != null) {
                mDefaultVideoPosterURL = url;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getDefaultVideoPosterURL}.
     */
    //
    public String getDefaultVideoPosterURL() {
        synchronized (mLudeiWebSettingsLock) {
            return mDefaultVideoPosterURL;
        }
    }

    @CalledByNative
    private String getDefaultVideoPosterURLLocked() {
        return mDefaultVideoPosterURL;
    }

    private void onGestureZoomSupportChanged(final boolean supportsGestureZoom) {
        // Always post asynchronously here, to avoid doubling back onto the caller.
        mEventHandler.maybePostOnUiThread(new Runnable() {
            
            public void run() {
                synchronized (mLudeiWebSettingsLock) {
                    if (mZoomChangeListener != null) {
                        mZoomChangeListener.onGestureZoomSupportChanged(supportsGestureZoom);
                    }
                }
            }
        });
    }

    /**
     * See {@link android.webkit.WebSettings#setSupportZoom}.
     */
    
    public void setSupportZoom(boolean support) {
        synchronized (mLudeiWebSettingsLock) {
            if (mSupportZoom != support) {
                mSupportZoom = support;
                onGestureZoomSupportChanged(supportsGestureZoomLocked());
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#supportZoom}.
     */
    
    public boolean supportZoom() {
        synchronized (mLudeiWebSettingsLock) {
            return mSupportZoom;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setBuiltInZoomControls}.
     */
    
    public void setBuiltInZoomControls(boolean enabled) {
        synchronized (mLudeiWebSettingsLock) {
            if (mBuiltInZoomControls != enabled) {
                mBuiltInZoomControls = enabled;
                onGestureZoomSupportChanged(supportsGestureZoomLocked());
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getBuiltInZoomControls}.
     */
    
    public boolean getBuiltInZoomControls() {
        synchronized (mLudeiWebSettingsLock) {
            return mBuiltInZoomControls;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setDisplayZoomControls}.
     */
    
    public void setDisplayZoomControls(boolean enabled) {
        synchronized (mLudeiWebSettingsLock) {
            mDisplayZoomControls = enabled;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getDisplayZoomControls}.
     */
    
    public boolean getDisplayZoomControls() {
        synchronized (mLudeiWebSettingsLock) {
            return mDisplayZoomControls;
        }
    }

    private boolean supportsGestureZoomLocked() {
        assert Thread.holdsLock(mLudeiWebSettingsLock);
        return mSupportZoom && mBuiltInZoomControls;
    }

    boolean supportsGestureZoom() {
        synchronized (mLudeiWebSettingsLock) {
            return supportsGestureZoomLocked();
        }
    }

    boolean shouldDisplayZoomControls() {
        synchronized (mLudeiWebSettingsLock) {
            return supportsGestureZoomLocked() && mDisplayZoomControls;
        }
    }

    private int clipFontSize(int size) {
        if (size < MINIMUM_FONT_SIZE) {
            return MINIMUM_FONT_SIZE;
        } else if (size > MAXIMUM_FONT_SIZE) {
            return MAXIMUM_FONT_SIZE;
        }
        return size;
    }

    @CalledByNative
    private void updateEverything() {
        synchronized (mLudeiWebSettingsLock) {
            nativeUpdateEverythingLocked(mNativeLudeiWebSettings);
        }
    }

    private void updateWebkitPreferencesOnUiThreadLocked() {
        if (mNativeLudeiWebSettings != 0) {
            assert mEventHandler.mHandler != null;
            ThreadUtils.assertOnUiThread();
            nativeUpdateWebkitPreferencesLocked(mNativeLudeiWebSettings);
        }
    }

    public void setNetworkAvailable(boolean networkUp) {
        if (mNativeWebContents == 0) {
            return;
        }
        nativeSetJsOnlineProperty(mNativeWebContents, networkUp);
    }

    private native long nativeInit(long webContentsPtr);

    private native void nativeDestroy(long nativeLudeiWebSettings);

    private native void nativeResetScrollAndScaleState(long nativeLudeiWebSettings);

    private native void nativeUpdateEverythingLocked(long nativeLudeiWebSettings);

    private native void nativeUpdateInitialPageScaleLocked(long nativeLudeiWebSettings);

    private native void nativeUpdateUserAgentLocked(long nativeLudeiWebSettings);

    private native void nativeUpdateWebkitPreferencesLocked(long nativeLudeiWebSettings);

    private static native String nativeGetDefaultUserAgent();
    
    public static native Object nativeCreateLudeiWebSettingsProxy(Context ctx);

    private native void nativeUpdateFormDataPreferencesLocked(long nativeLudeiWebSettings);

    private native void nativeSetJsOnlineProperty(long webContentsPtr, boolean networkUp);
}

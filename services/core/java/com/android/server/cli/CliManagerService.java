/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.cli;

import android.app.ActivityManager;
import android.app.ISnippetCliObserver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.devicestate.DeviceState;
import android.hardware.devicestate.DeviceStateManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IRemoteCallback;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.util.ArrayUtils;
import com.android.server.SystemService;

import com.motorola.cli.CliManagerCore;
import com.motorola.cli.ICliManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stand-in for Motorola's CLI (cover display) manager, so Motorola's prebuilt apps
 * (Moto Camera through moto-core_services.jar) see the lid and posture state.
 *
 * It maps device states to Motorola's DEVICE_* values, keeps MotorolaSettings
 * Global "lid_state" up to date and sends the lid / device state broadcasts.
 * Only started when ro.vendor.mot.hw.lid is 1.
 */
public final class CliManagerService extends SystemService {
    private static final String TAG = "CliManagerService";

    public static final String PROP_LID = "ro.vendor.mot.hw.lid";

    // motorola.core_services.cli.CLIManager constants
    private static final int DEVICE_UNKNOWN = -1;
    private static final int DEVICE_CLOSED = 1;
    private static final int DEVICE_LAPTOP = 4;
    private static final int DEVICE_OPEN = 5;

    private static final int LID_ABSENT = -1;
    private static final int LID_CLOSED = 0;
    private static final int LID_OPEN = 1;

    private static final int CLITRANSITION_NEVER = 0;

    private static final String ACTION_LID_STATE_CHANGE =
            "com.motorola.hardware.action.ACTION_LID_STATE_CHANGE";
    private static final String ACTION_DEVICE_STATE_CHANGE =
            "com.motorola.hardware.action.ACTION_DEVICE_STATE_CHANGE";
    private static final String EXTRA_LID_STATE = "com.motorola.hardware.extra.LID_STATE";
    private static final String EXTRA_DEVICE_STATE = "com.motorola.hardware.extra.DEVICE_STATE";

    // MotorolaSettings provider, same protocol as android.provider.Settings
    private static final Uri MOTO_SETTINGS_GLOBAL =
            Uri.parse("content://com.motorola.android.providers.settings/global");
    private static final String MOTO_SETTINGS_PUT_GLOBAL = "PUT_global";
    private static final String MOTO_LID_STATE = "lid_state";

    private final Object mLock = new Object();
    private final Handler mHandler;

    private final int[] mDeviceStateMap;
    private final int[] mOpenStates;
    private final int[] mHalfFoldedStates;
    private final int[] mFoldedStates;
    private final int mFeatures;

    // No snippet (small cover window) mode changes are reported yet; kept so callers can register.
    private final RemoteCallbackList<ISnippetCliObserver> mSnippetObservers =
            new RemoteCallbackList<>();

    // Per package CLI transition choice; Phase 1 keeps it in memory only.
    private final Map<String, Integer> mTransitions = new ArrayMap<>();

    private int mDeviceState = DEVICE_UNKNOWN;
    private int mLidState = LID_ABSENT;
    private boolean mBootCompleted;

    public CliManagerService(Context context) {
        super(context);
        HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        mHandler = new Handler(thread.getLooper());

        mDeviceStateMap = context.getResources().getIntArray(R.array.config_motoCliDeviceStates);
        mOpenStates = context.getResources().getIntArray(R.array.config_openDeviceStates);
        mHalfFoldedStates =
                context.getResources().getIntArray(R.array.config_halfFoldedDeviceStates);
        mFoldedStates = context.getResources().getIntArray(R.array.config_foldedDeviceStates);
        mFeatures = context.getResources().getInteger(R.integer.config_motoCliFeatures);
    }

    public static boolean isSupported() {
        return SystemProperties.getInt(PROP_LID, 0) == 1;
    }

    @Override
    public void onStart() {
        publishBinderService(CliManagerCore.SERVICE, new BinderService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            DeviceStateManager dsm = getContext().getSystemService(DeviceStateManager.class);
            if (dsm != null) {
                dsm.registerCallback(mHandler::post, new DeviceStateManager.DeviceStateCallback() {
                    @Override
                    public void onDeviceStateChanged(DeviceState state) {
                        handleDeviceState(state.getIdentifier());
                    }
                });
            } else {
                Slog.w(TAG, "No DeviceStateManager");
            }
        } else if (phase == PHASE_BOOT_COMPLETED) {
            mHandler.post(() -> {
                int lid;
                synchronized (mLock) {
                    mBootCompleted = true;
                    lid = mLidState;
                }
                if (lid != LID_ABSENT) {
                    writeLidState(lid);
                }
            });
        }
    }

    private int toMotoState(int state) {
        if (state >= 0 && state < mDeviceStateMap.length) {
            return mDeviceStateMap[state];
        }
        if (ArrayUtils.contains(mOpenStates, state)) return DEVICE_OPEN;
        if (ArrayUtils.contains(mHalfFoldedStates, state)) return DEVICE_LAPTOP;
        if (ArrayUtils.contains(mFoldedStates, state)) return DEVICE_CLOSED;
        return DEVICE_UNKNOWN;
    }

    /**
     * Motorola's LID_CLOSED tells its apps to move to the CLI display (Display.CLI_DISPLAY).
     * On Motorola's framework the cover is always that secondary display. Here the panels swap
     * when folded: the cover becomes the default display, and display CLI_DISPLAY only exists
     * while the phone is open. Reporting LID_CLOSED would make Moto Camera launch onto a display
     * that does not exist (crash) or relaunch itself forever, so report the lid as open. Apps then
     * stay on the default display when folded, like every other app; the posture is still
     * reported through getDeviceState() and ACTION_DEVICE_STATE_CHANGE.
     */
    private int toLidState(int state) {
        return LID_OPEN;
    }

    private void handleDeviceState(int state) {
        final int motoState = toMotoState(state);
        final int lid = toLidState(state);
        final boolean deviceChanged;
        final boolean lidChanged;
        final boolean bootCompleted;
        synchronized (mLock) {
            deviceChanged = motoState != mDeviceState;
            lidChanged = lid != mLidState;
            mDeviceState = motoState;
            mLidState = lid;
            bootCompleted = mBootCompleted;
        }
        Slog.i(TAG, "device state " + state + " -> moto " + motoState + ", lid " + lid);

        if (lidChanged) {
            if (bootCompleted) {
                writeLidState(lid);
            }
            sendBroadcast(new Intent(ACTION_LID_STATE_CHANGE).putExtra(EXTRA_LID_STATE, lid));
        }
        if (deviceChanged) {
            sendBroadcast(new Intent(ACTION_DEVICE_STATE_CHANGE)
                    .putExtra(EXTRA_DEVICE_STATE, motoState));
        }
    }

    private void writeLidState(int lid) {
        final long token = Binder.clearCallingIdentity();
        try {
            Bundle args = new Bundle();
            args.putString("value", Integer.toString(lid));
            args.putInt("_user", UserHandle.USER_SYSTEM);
            ContentResolver cr = getContext().getContentResolver();
            cr.call(MOTO_SETTINGS_GLOBAL, MOTO_SETTINGS_PUT_GLOBAL, MOTO_LID_STATE, args);
        } catch (Exception e) {
            // Provider missing or not up yet: the next state change retries.
            Slog.w(TAG, "Unable to write MotorolaSettings lid_state", e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void sendBroadcast(Intent intent) {
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_FOREGROUND);
        final long token = Binder.clearCallingIdentity();
        try {
            getContext().sendBroadcastAsUser(intent, UserHandle.ALL);
        } catch (Exception e) {
            Slog.w(TAG, "Unable to send " + intent.getAction(), e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void enforceSystem() {
        final int uid = Binder.getCallingUid();
        if (UserHandle.getAppId(uid) != Process.SYSTEM_UID) {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.MANAGE_ACTIVITY_TASKS, TAG);
        }
    }

    private final class BinderService extends ICliManager.Stub {
        @Override
        public int getDeviceState() {
            synchronized (mLock) {
                return mDeviceState;
            }
        }

        @Override
        public int getFeatures() {
            return mFeatures;
        }

        @Override
        public Rect getCliDisplayCutout() {
            return new Rect();
        }

        @Override
        public int queryCliTransitionAsUser(String packageName, int userId) {
            synchronized (mLock) {
                Integer t = mTransitions.get(userId + ":" + packageName);
                return t != null ? t : CLITRANSITION_NEVER;
            }
        }

        @Override
        public void setCliTransitionAsUser(String packageName, int transition, int userId) {
            enforceSystem();
            synchronized (mLock) {
                mTransitions.put(userId + ":" + packageName, transition);
            }
        }

        @Override
        public void setGlobalAppTransition(boolean enabled) {
            enforceSystem();
        }

        @Override
        public boolean queryCliAccessAsUser(String packageName, int userId) {
            return true;
        }

        @Override
        public boolean queryActivityCliAccessAsUser(String packageName, String className,
                int userId) {
            return true;
        }

        @Override
        public void setCliAccessAsUser(String packageName, boolean allowed, int userId) {
            enforceSystem();
        }

        @Override
        public List<String> getCliAccessPackagesAsUser(int match, int userId) {
            return new ArrayList<>();
        }

        @Override
        public boolean isAllowAllAppsOnCliForUser(int userId) {
            return true;
        }

        @Override
        public void setAllowAllAppsOnCliAsUser(boolean allow, int userId) {
            enforceSystem();
        }

        @Override
        public void applyChangesToRazrLabsDefaultCliPermissionsAsUser(int category,
                boolean allowed, List<String> packages, int userId) {
            enforceSystem();
        }

        @Override
        public void enableRazrLabsAsUser(int userId) {
            enforceSystem();
        }

        @Override
        public void setCliScaleGestureCallback(IRemoteCallback callback) {
        }

        @Override
        public void registerSnippetCliObserver(ISnippetCliObserver observer) {
            if (observer != null) {
                mSnippetObservers.register(observer);
            }
        }

        @Override
        public void unregisterSnippetCliObserver(ISnippetCliObserver observer) {
            if (observer != null) {
                mSnippetObservers.unregister(observer);
            }
        }

        @Override
        public boolean cancelAutoLockContinuityAtWill() {
            return false;
        }

        @Override
        public boolean executeAppcontinuityTransaction() {
            return false;
        }

        @Override
        public ActivityManager.RunningTaskInfo getAppContinuityCandidateTaskInfo() {
            return null;
        }

        @Override
        public boolean isUnlockTransitionOnAppContinuity() {
            return false;
        }

        @Override
        public boolean toggleAppcontinuityTimer(boolean enable) {
            return false;
        }

        @Override
        public void toggleUnlockTransitionOnAppContinuity(boolean enable, String packageName) {
            enforceSystem();
        }
    }
}

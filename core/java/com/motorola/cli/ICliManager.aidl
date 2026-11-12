/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.motorola.cli;

import android.app.ActivityManager;
import android.app.ISnippetCliObserver;
import android.graphics.Rect;
import android.os.IRemoteCallback;

/**
 * Compatibility binder for Motorola's prebuilt moto-core_services.jar
 * (motorola.core_services.cli.CLIManager). The method names and signatures
 * must match what that jar calls; transaction codes are ours.
 * CLIManager's constructor always creates an ISnippetCliObserver.Stub, so
 * android.app.ISnippetCliObserver must exist too.
 *
 * {@hide}
 */
interface ICliManager {
    int getDeviceState();
    int getFeatures();
    Rect getCliDisplayCutout();

    int queryCliTransitionAsUser(String packageName, int userId);
    void setCliTransitionAsUser(String packageName, int transition, int userId);
    void setGlobalAppTransition(boolean enabled);

    boolean queryCliAccessAsUser(String packageName, int userId);
    boolean queryActivityCliAccessAsUser(String packageName, String className, int userId);
    void setCliAccessAsUser(String packageName, boolean allowed, int userId);
    List<String> getCliAccessPackagesAsUser(int match, int userId);
    boolean isAllowAllAppsOnCliForUser(int userId);
    void setAllowAllAppsOnCliAsUser(boolean allow, int userId);
    void applyChangesToRazrLabsDefaultCliPermissionsAsUser(int category, boolean allowed,
            in List<String> packages, int userId);
    void enableRazrLabsAsUser(int userId);

    void setCliScaleGestureCallback(IRemoteCallback callback);

    void registerSnippetCliObserver(ISnippetCliObserver observer);
    void unregisterSnippetCliObserver(ISnippetCliObserver observer);

    boolean cancelAutoLockContinuityAtWill();
    boolean executeAppcontinuityTransaction();
    ActivityManager.RunningTaskInfo getAppContinuityCandidateTaskInfo();
    boolean isUnlockTransitionOnAppContinuity();
    boolean toggleAppcontinuityTimer(boolean enable);
    void toggleUnlockTransitionOnAppContinuity(boolean enable, String packageName);
}

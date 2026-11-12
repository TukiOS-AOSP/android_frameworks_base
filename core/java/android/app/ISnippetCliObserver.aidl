/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.app;

import android.content.ComponentName;

// Compatibility interface for Motorola's prebuilt moto-core_services.jar, whose
// CLIManager$SnippetCliObserverStub extends ISnippetCliObserver.Stub.

/** @hide */
oneway interface ISnippetCliObserver {
    void onCliActivityDisplayModeChange(in ComponentName component, int displayMode);
}

/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.motorola.cli;

import android.annotation.Nullable;

/**
 * What {@code Context.getSystemService("climanager")} returns.
 * motorola.core_services.cli.CLIManager casts to this class and calls {@link #getService()}.
 *
 * {@hide}
 */
public final class CliManagerCore {
    /** Service name, as used by moto-core_services.jar. */
    public static final String SERVICE = "climanager";

    private final ICliManager mService;

    public CliManagerCore(@Nullable ICliManager service) {
        mService = service;
    }

    @Nullable
    public ICliManager getService() {
        return mService;
    }
}

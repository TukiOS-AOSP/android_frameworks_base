/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.display.layout;

import android.annotation.NonNull;
import android.view.DisplayAddress;

/**
 * Interface for producing logical display ids.
 */
public interface DisplayIdProducer {

    /**
     * Generates a new display ID
     * @param isDefault if requested display is the default display.
     * @return the next unique logical display Id.
     */
    int getId(boolean isDefault);

    /**
     * Generates a display ID for the display at the given address. By default the same as
     * {@link #getId(boolean)}; producers may return a stable ID per address instead.
     * @param isDefault if requested display is the default display.
     * @param address the physical address of the display.
     * @return the logical display Id.
     */
    default int getId(boolean isDefault, @NonNull DisplayAddress address) {
        return getId(isDefault);
    }
}

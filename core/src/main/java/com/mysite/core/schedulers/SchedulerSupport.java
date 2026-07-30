/*
 *  Copyright 2024 Adobe Systems Incorporated
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.mysite.core.schedulers;

/**
 * Small helpers shared by the Workfront schedulers.
 */
final class SchedulerSupport {

    private SchedulerSupport() {
        // static utility
    }

    /**
     * Sleeps for the given number of seconds as a CPU cool-down between units of
     * work. A value of {@code 0} or less returns immediately.
     *
     * @param seconds the cool-down in seconds
     * @return {@code true} if the pause completed normally; {@code false} if the
     *         thread was interrupted (the interrupt flag is restored so the caller
     *         can stop the run cleanly)
     */
    static boolean pause(final int seconds) {
        if (seconds <= 0) {
            return true;
        }
        try {
            Thread.sleep(seconds * 1000L);
            return true;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

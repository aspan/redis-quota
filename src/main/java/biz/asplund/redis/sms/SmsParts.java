/*
 * Copyright 2026 Andreas Asplund
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package biz.asplund.redis.sms;

/// How many SMS parts a text takes, which is what a gateway charges for.
///
/// A text of only GSM 7-bit characters fits 160 in one part and 153 in each part of a longer one, where the rest of
/// the part holds the header that joins them; the extension characters, such as `€` and `{`, take two. A text with
/// any other character is sent as UCS-2, 70 UTF-16 code units in one part and 67 in each part of a longer one.
public final class SmsParts {

    private static final String GSM_BASIC = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    private static final String GSM_EXTENSION = "\f^{}\\[~]|€";

    private SmsParts() {
    }

    /// Returns how many parts the text takes, `0` for an empty text.
    public static int count(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        var septets = gsmSeptets(text);
        if (septets >= 0) {
            return septets <= 160 ? 1 : ceilDiv(septets, 153);
        }
        return text.length() <= 70 ? 1 : ceilDiv(text.length(), 67);
    }

    /// Returns whether the text is sent in the GSM 7-bit alphabet rather than UCS-2.
    public static boolean isGsm(String text) {
        return gsmSeptets(text) >= 0;
    }

    /// Returns the length in the GSM 7-bit alphabet, or `-1` if the text has a character outside it.
    private static int gsmSeptets(String text) {
        var septets = 0;
        for (var i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            if (GSM_BASIC.indexOf(c) >= 0) {
                septets++;
            } else if (GSM_EXTENSION.indexOf(c) >= 0) {
                septets += 2;
            } else {
                return -1;
            }
        }
        return septets;
    }

    private static int ceilDiv(int dividend, int divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}

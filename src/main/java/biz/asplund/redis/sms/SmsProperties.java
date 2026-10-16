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

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Locale;

/**
 * Sending SMS.
 *
 * @param defaultRegion the region, as an ISO 3166 country code such as SE, that a recipient's number is read in when
 *                      it has no country code, such as 070-123 45 67
 */
@ConfigurationProperties("quota.sms")
public record SmsProperties(@DefaultValue("SE") String defaultRegion) {

    public SmsProperties {
        defaultRegion = defaultRegion.strip().toUpperCase(Locale.ROOT);
        if (!PhoneNumberUtil.getInstance().getSupportedRegions().contains(defaultRegion)) {
            throw new IllegalArgumentException("quota.sms.default-region is not a supported region: "
                    + defaultRegion);
        }
    }
}

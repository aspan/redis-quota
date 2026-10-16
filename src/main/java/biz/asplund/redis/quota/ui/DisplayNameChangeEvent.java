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

package biz.asplund.redis.quota.ui;

import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;

/// Fired on each UI of the session when the logged-in user's name changes, so [MainLayout] shows the new one.
class DisplayNameChangeEvent extends ComponentEvent<UI> {

    /// Creates the event for `ui`, as fired from the server.
    DisplayNameChangeEvent(UI ui) {
        super(ui, false);
    }

    /// Fires the event on every UI of the current session.
    static void fire() {
        VaadinSession.getCurrent().getUIs().forEach(ui -> ComponentUtil.fireEvent(ui, new DisplayNameChangeEvent(ui)));
    }
}

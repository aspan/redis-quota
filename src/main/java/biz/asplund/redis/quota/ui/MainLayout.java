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

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.avatar.Avatar;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import com.vaadin.flow.router.Layout;
import com.vaadin.flow.server.menu.MenuConfiguration;
import com.vaadin.flow.server.menu.MenuEntry;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.PermitAll;

// Shell around every view except the login view; the menu lists the @Menu views the user may open
@Layout
@PermitAll
public class MainLayout extends AppLayout implements AfterNavigationObserver {

    private final H1 viewTitle = new H1();

    public MainLayout(AuthenticationContext authenticationContext) {
        setPrimarySection(Section.DRAWER);

        viewTitle.getStyle().set("font-size", "1.125rem").set("margin", "0");
        addToNavbar(new DrawerToggle(), viewTitle);

        var appName = new Span("Redis Quota");
        appName.getStyle().set("font-weight", "600").set("font-size", "1.125rem");
        var header = new HorizontalLayout(VaadinIcon.DASHBOARD.create(), appName);
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setPadding(true);

        var nav = new Scroller(createSideNav());
        var drawer = new VerticalLayout(header, nav, createUserFooter(authenticationContext));
        drawer.setSizeFull();
        drawer.setPadding(false);
        drawer.setFlexGrow(1, nav);
        addToDrawer(drawer);
    }

    private SideNav createSideNav() {
        var nav = new SideNav();
        nav.setWidthFull();
        MenuConfiguration.getMenuEntries().forEach(entry -> nav.addItem(createSideNavItem(entry)));
        return nav;
    }

    private SideNavItem createSideNavItem(MenuEntry entry) {
        var item = new SideNavItem(entry.title(), entry.path());
        item.setMatchNested(true);
        if (entry.icon() != null) {
            item.setPrefixComponent(new Icon(entry.icon()));
        }
        return item;
    }

    private HorizontalLayout createUserFooter(AuthenticationContext authenticationContext) {
        var name = authenticationContext.getPrincipalName().orElse("");
        var avatar = new Avatar(name);
        var userName = new Span(name);

        var logout = new Button(VaadinIcon.SIGN_OUT.create(), _ -> authenticationContext.logout());
        logout.setTooltipText("Log out");
        logout.setAriaLabel("Log out");
        logout.addThemeVariants(ButtonVariant.TERTIARY);

        var footer = new HorizontalLayout(avatar, userName, logout);
        footer.setWidthFull();
        footer.setPadding(true);
        footer.setAlignItems(FlexComponent.Alignment.CENTER);
        footer.expand(userName);
        return footer;
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        viewTitle.setText(MenuConfiguration.getPageHeader(getContent()).orElse(""));
    }
}

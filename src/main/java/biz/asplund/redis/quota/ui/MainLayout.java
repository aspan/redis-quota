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

import biz.asplund.redis.security.UserAccountService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.DetachEvent;
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
import com.vaadin.flow.i18n.LocaleChangeEvent;
import com.vaadin.flow.i18n.LocaleChangeObserver;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import com.vaadin.flow.router.Layout;
import com.vaadin.flow.server.menu.MenuConfiguration;
import com.vaadin.flow.server.menu.MenuEntry;
import com.vaadin.flow.spring.security.AuthenticationContext;
import com.vaadin.flow.shared.Registration;
import jakarta.annotation.security.PermitAll;
import org.springframework.dao.DataAccessException;

/// Shell around every view except the login view; the menu lists the `@Menu` views the user may open, whose titles
/// are translation keys.
///
/// Texts are set in [#localeChange(LocaleChangeEvent)], so they follow the language picker.
@Layout
@PermitAll
public class MainLayout extends AppLayout implements AfterNavigationObserver, LocaleChangeObserver {

    private final H1 viewTitle = new H1();
    private final Span appName = new Span();
    private final SideNav nav = new SideNav();
    /// The size of the icons at the top right: the appearance toggle's, log out's and the height of the language
    /// flag.
    static final String TOOLBAR_ICON_SIZE = "1.5em";

    private final Button logout = new Button(toolbarIcon(VaadinIcon.SIGN_OUT));
    private final Avatar avatar = new Avatar();
    private final Span userName = new Span();
    private final UserAccountService userAccountService;
    private final String username;
    private Registration nameChangeRegistration;

    /// Creates the navbar, the drawer with the menu and the logged-in user, and the preference buttons.
    public MainLayout(AuthenticationContext authenticationContext, LanguagePreference languagePreference,
            ColorSchemePreference colorSchemePreference, UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
        this.username = authenticationContext.getPrincipalName().orElse("");
        setPrimarySection(Section.DRAWER);
        // Wider than the default, so longer translated menu labels fit
        getStyle().set("--vaadin-app-layout-drawer-width", "20rem");

        viewTitle.getStyle().set("font-size", "1.125rem").set("margin", "0");
        // Language menu, appearance toggle and log out sit at the right end of the navbar
        logout.addClickListener(_ -> authenticationContext.logout());
        logout.addThemeVariants(ButtonVariant.TERTIARY);
        var preferences = new HorizontalLayout(new LanguageMenuButton(languagePreference),
                new ColorSchemeToggleButton(colorSchemePreference), logout);
        // Vaadin's extra-small gap; the LUMO_SPACING_XS variant does not apply to the Aura theme
        preferences.setSpacing("var(--vaadin-gap-xs)");
        preferences.getStyle().set("margin-inline-start", "auto").set("padding-inline-end", "0.5rem");
        addToNavbar(new DrawerToggle(), viewTitle, preferences);

        appName.getStyle().set("font-weight", "600").set("font-size", "1.125rem");
        var header = new HorizontalLayout(VaadinIcon.DASHBOARD.create(), appName);
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setPadding(true);

        // The drawer layout aligns its children to the start, so the menu is stretched to fill the drawer
        nav.setWidthFull();
        nav.getStyle().set("padding-inline", "var(--vaadin-gap-s)").set("box-sizing", "border-box");
        var scroller = new Scroller(nav);
        scroller.setWidthFull();

        var drawer = new VerticalLayout(header, scroller, createUserFooter(authenticationContext));
        drawer.setSizeFull();
        drawer.setPadding(false);
        drawer.setSpacing(false);
        drawer.setFlexGrow(1, scroller);
        addToDrawer(drawer);
    }

    /// Returns the icon at the size of the icons at the top right, [#TOOLBAR_ICON_SIZE].
    static Icon toolbarIcon(VaadinIcon vaadinIcon) {
        var icon = vaadinIcon.create();
        icon.setSize(TOOLBAR_ICON_SIZE);
        return icon;
    }

    @Override
    public void localeChange(LocaleChangeEvent event) {
        appName.setText(getTranslation("app.name"));
        logout.setTooltipText(getTranslation("nav.logout"));
        logout.setAriaLabel(getTranslation("nav.logout"));

        nav.removeAll();
        MenuConfiguration.getMenuEntries().forEach(entry -> nav.addItem(createSideNavItem(entry)));

        if (getContent() != null) {
            updateViewTitle();
            // The router sets the browser tab title only on navigation
            event.getUI().getPage().setTitle(viewTitle.getText());
        }
    }

    private SideNavItem createSideNavItem(MenuEntry entry) {
        var item = new SideNavItem(getTranslation(entry.title()), entry.path());
        item.setMatchNested(true);
        if (entry.icon() != null) {
            item.setPrefixComponent(new Icon(entry.icon()));
        }
        return item;
    }

    /// The logged-in user, by name if set.
    private HorizontalLayout createUserFooter(AuthenticationContext authenticationContext) {
        showDisplayName();
        var footer = new HorizontalLayout(avatar, userName);
        footer.setWidthFull();
        footer.setPadding(true);
        footer.setAlignItems(FlexComponent.Alignment.CENTER);
        footer.expand(userName);
        return footer;
    }

    private void showDisplayName() {
        String name;
        try {
            name = userAccountService.findDisplayName(username);
        } catch (DataAccessException e) {
            name = username;
        }
        avatar.setName(name);
        userName.setText(name);
    }

    /// Shows a new name at once, when the user changes it on the profile page or in the user editor.
    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        nameChangeRegistration = ComponentUtil.addListener(attachEvent.getUI(), DisplayNameChangeEvent.class,
                _ -> showDisplayName());
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        nameChangeRegistration.remove();
        super.onDetach(detachEvent);
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        updateViewTitle();
    }

    private void updateViewTitle() {
        // Already translated by the PageTitleGenerator in I18nConfiguration
        viewTitle.setText(MenuConfiguration.getPageHeader(getContent()).orElse(""));
    }
}

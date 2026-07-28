# IDE Layout Profiles

Save and switch between complete IntelliJ IDEA layouts without rebuilding your workspace by hand.

<!-- Plugin description -->
IDE Layout Profiles saves the parts of your IntelliJ IDEA workspace that normally take several clicks to restore.
Create as many application-wide profiles as you need. The first ten are assigned to stable keybinding actions;
every profile can be applied by name from the Window menu or settings page.

Each profile stores:

- tool-window positions, sizes, split groups, and shown/hidden state
- main toolbar and main menu visibility
- navigation bar visibility and position
- tool-window bar visibility
- status bar visibility
- editor tab placement and widescreen tool-window layout

Profiles are shared across projects. Applying a profile restores the available tool windows in the focused project
and the application-wide toolbar and status-bar settings in every open project window.

### Getting started

1. Arrange IntelliJ IDEA the way you want.
2. Open **Window | Layout Profiles | Save Current as New**.
3. Assign a profile-specific shortcut under **Settings | Keymap** by searching for its name or `Layout Profiles`.
4. Apply or update a profile from its shortcut, **Window | Layout Profiles**, **Find Action**, or
   **Search Everywhere | All**.

Use **Window → Layout Profiles → Apply Profile** to apply any saved profile by name. Use
**Settings → Tools → IDE Layout Profiles** to create, rename, delete, reorder, apply, or update saved profiles. The
first ten profiles are assigned to the **Apply Slot 1–10** actions.

Profile-specific shortcuts survive profile renaming and reordering. The plugin provides no default shortcuts, so it
will not conflict with your existing keymap.

Use **Apply Active to All Open Projects** to restore the active profile in every open IDE frame.
<!-- Plugin description end -->

## Compatibility

IDE Layout Profiles currently targets IntelliJ IDEA 2025.3 and newer.

## Development

```bash
./gradlew runIde
./gradlew check
./gradlew verifyPlugin
./gradlew buildPlugin
```

The IntelliJ Platform does not expose fully stable APIs for complete tool-window snapshots or current main-toolbar
visibility. Run Plugin Verifier whenever the target IDE changes.
## License

Licensed under the [Apache License 2.0](LICENSE).

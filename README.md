# IDE Layout Profiles

Save and switch between complete IntelliJ IDEA layouts without rebuilding your workspace by hand.

<!-- Plugin description -->
IDE Layout Profiles saves the parts of your IntelliJ IDEA workspace that normally take several clicks to restore.
Create up to five application-wide profiles, then apply or update them from the keyboard, **Find Action**, or
**Search Everywhere**.

Each profile stores:

- tool-window positions, sizes, split groups, and shown/hidden state
- main toolbar visibility, customized toolbar actions, and main menu visibility
- navigation bar visibility and position
- tool-window bar visibility
- status bar visibility and individual status-bar widgets

Profiles are shared across projects. Applying a profile restores the available tool windows in the focused project
and the application-wide toolbar and status-bar settings in every open project window.

### Getting started

1. Arrange IntelliJ IDEA the way you want.
2. Open **Window | Layout Profiles | Save Current as New**.
3. Assign shortcuts under **Settings | Keymap** by searching for `Layout Profiles`.
4. Apply or update a profile from its shortcut, **Window | Layout Profiles**, **Find Action**, or
   **Search Everywhere | All**.

Use **Settings → Tools → IDE Layout Profiles** to create, rename, delete, reorder, or apply saved profiles. Profile order
determines which profile is assigned to each **Apply Slot 1–5** action.

The plugin provides no default shortcuts, so it will not conflict with your existing keymap.
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

# IDE Layout Profiles

An IntelliJ Platform plugin for saving and recalling complete IDE layout profiles from the keyboard.

Each of the five application-wide slots stores:

- tool-window positions, sizes, split groups, and shown/hidden state
- main toolbar visibility, customized toolbar actions, and main menu visibility
- navigation bar visibility and position
- tool-window bar visibility
- status bar visibility and individual status-bar widgets

## Use

Open **Window → Layout Profiles** to save, apply, update, rename, replace, or clear a slot.

Assign shortcuts under **Settings → Keymap** by searching for `Layout Profile`. The plugin intentionally provides no
default shortcuts, so it does not conflict with an existing keymap.

Layouts are shared across projects. Applying a slot restores available tool windows in the focused project. Toolbar
visibility and actions, navigation, tool-window bar, status bar, and status-bar widget settings are application-wide
and can affect every open project window.

## Development

```bash
./gradlew runIde
./gradlew check
./gradlew verifyPlugin
```

The IntelliJ Platform does not expose fully stable APIs for complete tool-window snapshots or current main-toolbar
visibility. Run Plugin Verifier whenever the target IDE changes.

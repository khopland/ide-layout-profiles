<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IDE Layout Profiles Changelog

## Unreleased

### Added

- Added a best-match startup mode and opt-in, debounced automatic switching after display topology changes.

### Changed

- Declared IntelliJ Platform compatibility with an explicit 2025.3 minimum and an open upper bound.
- Expanded cross-release adapter tests to cover native layout save, apply, import, export, and rollback.
- Configured Plugin Verifier for every supported IntelliJ IDEA release line and WebStorm 2026.2.
- Moved display-topology matching out of frequent action updates and into a short-lived background cache.
- Moved layout profile XML reads and writes off the Swing event thread.
- Registered profile management as application settings, while requiring an active project only for layout capture and apply operations.
- Clarified which Settings actions take effect immediately and which wait for Apply.

### Fixed

- Layout apply failures now produce actionable notifications and IDE log entries instead of escaping from actions.
- Applying a profile to every open project now continues after individual project failures and reports partial success.
- Save and update actions now report platform adapter failures without closing the workflow with an IDE error.
- Persistent state serialization now uses isolated snapshots, and service operations are synchronized.

## 0.1.2 - 2026-07-29

### Changed

- Import and export integration tests now run against every supported IntelliJ release line.

### Added

- Added an **Apply Profile** submenu that lists every saved profile and marks the active one.
- Added **Update from Current** for the selected profile in Settings.
- Added stable profile-specific Keymap actions that survive profile renaming and reordering.
- Settings now shows shortcuts assigned to profile actions and the first ten slot aliases.
- Profiles now capture editor-tab placement and the widescreen tool-window setting.
- Added **Apply Active to All Open Projects** for multi-window workflows.
- Added versioned XML import and export for complete layout profiles.
- Added selected-profile export alongside full-profile export.
- Added **Add New**, **Update Existing**, **Import as Copies**, and explicit **Replace All** import modes.
- Added an **Update Profile** submenu for updating any saved profile from the Window menu.
- Profiles now record display count, usable bounds, and scale when saved or updated.
- Added manual **Apply Best Match**, with the chosen profile previewed in the action name.
- Added a global **Startup Profile** selector that applies the chosen profile when projects open.

### Fixed

- Import now prepares every native layout before replacing profiles and rolls back failed commits.
- Settings actions no longer commit unrelated pending edits before Apply is pressed.
- Settings actions now wrap onto additional rows instead of being clipped in narrow windows.
- Legacy profiles without a saved editor-tab placement can now be imported.
- Invalid display-topology metadata is now rejected during import.
- Startup profiles now report missing native layouts instead of failing silently.

## 0.1.1

### Changed

- Stopped capturing customized toolbar actions and individual status-bar widgets because IntelliJ exposes only internal APIs for those settings.

### Added

- 10 key bindings profiles
- unlimited profiles from settings

## 0.1.0

### Changed

- Renamed the plugin and project to **IDE Layout Profiles**.
- Converted the platform adapters to Kotlin and removed generated scaffold files.
- Added the plugin icon.
- Replaced the Manage Layout Profiles dialog with the settings page.
- Expanded the README and made it the source for the JetBrains Marketplace description.
- Removed the profile limit; the first ten profiles are assigned to shortcut slots.

### Added

- Ten fixed Keymap actions for applying the first ten profiles.
- Save-new, update-active, and direct apply workflows.
- Tool-window and IDE chrome visibility restoration across projects.
- Settings page for renaming, deleting, reordering, and applying profiles.
- Keymap action that opens the IDE Layout Profiles settings page.
- Find Action and Search Everywhere names and synonyms for every plugin command.
- Apache License 2.0 and Marketplace publishing configuration.
- Create new layout profiles directly from the settings page.

### Fixed

- Capture and restore the current IntelliJ main-toolbar visibility setting.

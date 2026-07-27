<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IDE Layout Profiles Changelog

## [Unreleased]

### Changed

- Renamed the plugin and project to **IDE Layout Profiles**.
- Converted the platform adapters to Kotlin and removed generated scaffold files.
- Added the plugin icon.
- Replaced the Manage Layout Profiles dialog with the settings page.
- Expanded the README and made it the source for the JetBrains Marketplace description.

### Added

- Five global layout-profile slots with direct Keymap actions.
- Save-new, update-active, and direct apply workflows.
- Tool-window and IDE chrome visibility restoration across projects.
- Settings page for renaming, deleting, reordering, and applying profiles.
- Keymap action that opens the IDE Layout Profiles settings page.
- Find Action and Search Everywhere names and synonyms for every plugin command.
- Apache License 2.0 and Marketplace publishing configuration.
- Create new layout profiles directly from the settings page.

### Fixed

- Capture and restore the current IntelliJ main-toolbar visibility setting.
- Capture and restore actions configured through **Customize Toolbar**.
- Capture and restore individual status-bar widgets, including Line/Column, encoding, indentation, and Git branch.

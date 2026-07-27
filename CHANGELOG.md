<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IDE Layout Profiles Changelog

## [Unreleased]

### Changed

- Renamed the plugin and project to **IDE Layout Profiles**.
- Converted the platform adapters to Kotlin and removed generated scaffold files.

### Added

- Five global layout-profile slots with direct Keymap actions.
- Save-new, update-active, apply, rename, replace, and clear workflows.
- Tool-window and IDE chrome visibility restoration across projects.

### Fixed

- Capture and restore the current IntelliJ main-toolbar visibility setting.
- Capture and restore actions configured through **Customize Toolbar**.
- Capture and restore individual status-bar widgets, including Line/Column, encoding, indentation, and Git branch.

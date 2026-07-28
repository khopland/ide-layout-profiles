<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IDE Layout Profiles Changelog

## [Unreleased]
### Changed

- Stopped capturing customized toolbar actions and individual status-bar widgets because IntelliJ exposes only internal APIs for those settings.

### Added
- 10 key bindings profiles
- unlimited profiles from settings

### Fixed


## [0.1.0]

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

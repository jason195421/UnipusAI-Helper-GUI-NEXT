pragma Singleton
import QtQuick
import UnipusAI.Platform 1.0

// One codebase, three platform-adaptive looks:
//   Windows  -> WinUI 3 / Fluent (soft layers and a system accent)
//   macOS    -> Aqua (vibrant sidebar, system blue, 10-12px corners)
//   other    -> neutral Fluent dark/light fallback
// All colours and radii are exposed here; components must never hardcode them.
QtObject {
    readonly property bool dark: PlatformInfo.dark
    readonly property bool winui3: PlatformInfo.isWindows
    readonly property bool macos: PlatformInfo.isMacOS
    readonly property string themeId: PlatformInfo.themeId
    readonly property string fontFamily: PlatformInfo.family !== "" ? PlatformInfo.family : Qt.application.font.family

    // Accent follows the OS (Windows DWM / macOS system blue); UNIPUS_ACCENT overrides.
    readonly property color accent: PlatformInfo.accent
    readonly property color accentFill: PlatformInfo.accentFill
    readonly property color accentHover: dark ? Qt.lighter(accentFill, 1.06) : Qt.darker(accentFill, 1.06)
    readonly property color accentPressed: PlatformInfo.accentPressed
    function linearChannel(value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4)
    }
    readonly property real accentLuminance: 0.2126 * linearChannel(accentFill.r)
        + 0.7152 * linearChannel(accentFill.g) + 0.0722 * linearChannel(accentFill.b)
    readonly property bool accentInkLight: accentLuminance <= (1.05 / 4.5 - 0.05)
    readonly property color accentInk: accentInkLight ? "#ffffff" : "#000000"

    // Layer colours. WinUI 3 uses cool, slightly desaturated Mica tints;
    // macOS Aqua uses warmer light greys / graphite dark greys.
    readonly property color canvas: dark
        ? (macos ? "#1e1e22" : "#181e28")
        : (macos ? "#f3f3f6" : "#f4f7fb")
    readonly property color surface: dark
        ? (macos ? "#29292f" : "#222b38") : "#ffffff"
    readonly property color sidebar: dark
        ? (macos ? "#242429" : "#1c2533")
        : (macos ? "#ededf2" : "#edf2f9")
    readonly property color raised: dark
        ? (macos ? "#323239" : "#2b3544") : "#f5f8fc"
    readonly property color field: dark ? (macos ? "#232328" : "#1b2431") : "#f8fafd"
    readonly property color hover: dark
        ? (macos ? "#3a3a43" : "#344154") : "#e8eef7"
    readonly property color line: dark
        ? (macos ? "#42424c" : "#3a475b") : "#dce4ef"
    readonly property color subtleLine: dark
        ? (macos ? "#34343c" : "#2e3949") : "#eaf0f6"

    readonly property color ink: dark ? "#f0f5fc" : "#1c2b41"
    readonly property color secondary: dark ? "#bfccdd" : "#52637b"
    readonly property color muted: dark ? "#98a9bf" : "#687a92"

    readonly property color navigation: Qt.tint(surface, Qt.rgba(accent.r, accent.g, accent.b, dark ? 0.18 : 0.10))
    readonly property color selection: Qt.tint(surface, Qt.rgba(accent.r, accent.g, accent.b, dark ? 0.12 : 0.06))
    readonly property color selectionHover: Qt.tint(surface, Qt.rgba(accent.r, accent.g, accent.b, dark ? 0.22 : 0.12))
    readonly property color selectionLine: Qt.tint(line, Qt.rgba(accent.r, accent.g, accent.b, 0.28))
    readonly property color track: dark ? "#3a4658" : "#e5ecf5"

    readonly property color success: dark ? "#b4e2c2" : (macos ? "#1a7f3c" : "#0f7b3f")
    readonly property color successSurface: dark ? "#22442d" : (macos ? "#e3f5e8" : "#e6f4ea")
    readonly property color error: dark ? "#f0aaa5" : "#c42b1c"
    readonly property color errorSurface: dark ? "#462c36" : "#fff0ee"
    readonly property color warning: dark ? "#e2bf86" : "#9d5d00"
    readonly property color warningSurface: dark ? "#3e3528" : "#fff7e8"
    readonly property color compulsory: dark ? "#ffb04d" : "#b35c00"
    readonly property color compulsorySurface: dark ? "#433526" : "#fff3df"
    readonly property color requiredInk: dark ? "#24180c" : "#ffffff"

    // Shared geometry keeps cards, inputs and navigation visually consistent.
    readonly property int radiusXs: winui3 ? 4 : 5
    readonly property int radiusSm: 8
    readonly property int radiusMd: 12
    readonly property int radiusLg: 16

    // Mica / Aqua window backdrop (used by Main.qml ApplicationWindow.background).
    readonly property color windowTop: dark
        ? (macos ? "#25252c" : "#1d2736") : "#f7faff"
    readonly property color windowBottom: canvas
    readonly property color sidebarTop: dark
        ? (macos ? "#28282f" : "#202c3d") : (macos ? "#f1f0f5" : "#f0f5fc")
    readonly property color sidebarBottom: sidebar
    readonly property real sidebarOpacity: dark ? 0.92 : (macos ? 0.82 : 0.88)
    readonly property int sidebarBorder: winui3 ? 1 : 0
}

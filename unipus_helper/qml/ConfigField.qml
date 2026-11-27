import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Shared single-line input used by the built-in Settings page.
TextField {
    id: fieldControl
    Layout.fillWidth: true
    Layout.minimumWidth: 0
    implicitHeight: 40
    hoverEnabled: true
    selectByMouse: true
    color: Theme.ink
    placeholderTextColor: Theme.muted
    selectionColor: Theme.navigation
    selectedTextColor: Theme.ink
    font.family: Theme.fontFamily
    font.pixelSize: 13
    font.hintingPreference: Font.PreferFullHinting
    renderType: Text.NativeRendering
    leftPadding: 12
    rightPadding: 12
    background: Rectangle {
        radius: Theme.radiusSm
        color: fieldControl.activeFocus ? Theme.surface : Theme.field
        border.width: fieldControl.activeFocus ? 2 : 1
        border.color: fieldControl.activeFocus ? Theme.accent : fieldControl.hovered ? Theme.muted : Theme.line
        Behavior on color { enabled: Theme.motionEnabled; ColorAnimation { duration: Theme.motionFast } }
        Behavior on border.color { enabled: Theme.motionEnabled; ColorAnimation { duration: Theme.motionFast } }
    }
}

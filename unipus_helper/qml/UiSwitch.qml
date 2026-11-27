import QtQuick
import QtQuick.Controls

Switch {
    id: control
    property string hint: ""
    property bool motionReady: false
    Component.onCompleted: motionReady = true
    implicitWidth: Math.max(46, leftPadding + rightPadding + contentItem.implicitWidth)
    implicitHeight: 32
    padding: 2
    spacing: 10
    hoverEnabled: true
    focusPolicy: Qt.StrongFocus
    opacity: enabled ? 1 : 0.45

    indicator: Rectangle {
        x: control.mirrored ? control.width - control.rightPadding - width : control.leftPadding
        y: (control.height - height) / 2
        width: 42; height: 24; radius: height / 2
        color: control.checked
            ? (control.down ? Theme.accentPressed : control.hovered ? Theme.accentHover : Theme.accentFill)
            : control.hovered ? Theme.hover : Theme.track
        border.width: 1
        border.color: control.visualFocus || control.hovered ? Theme.accent : control.checked ? Theme.accentFill : Theme.line
        Behavior on color { enabled: control.motionReady && Theme.motionEnabled; ColorAnimation { duration: Theme.motionFast } }
        Behavior on border.color { enabled: control.motionReady && Theme.motionEnabled; ColorAnimation { duration: Theme.motionFast } }

        Rectangle {
            x: 3 + control.visualPosition * (parent.width - width - 6)
            y: 3; width: 18; height: 18; radius: 9
            color: control.checked ? Theme.accentInk : Theme.secondary
            Behavior on x { enabled: control.motionReady && Theme.motionEnabled && !control.down; NumberAnimation { duration: Theme.motionFast; easing.type: Easing.OutCubic } }
            Behavior on color { enabled: control.motionReady && Theme.motionEnabled; ColorAnimation { duration: Theme.motionFast } }
        }
        Rectangle {
            anchors.fill: parent; anchors.margins: -3
            radius: height / 2; color: "transparent"
            border.width: 1; border.color: Theme.accent
            visible: control.visualFocus
        }
    }
    contentItem: UiText {
        text: control.text
        visible: text !== ""
        leftPadding: !control.mirrored && text !== "" ? control.indicator.width + control.spacing : 0
        rightPadding: control.mirrored && text !== "" ? control.indicator.width + control.spacing : 0
        color: Theme.secondary
        font.pixelSize: 13
    }
    ToolTip.visible: hovered && hint !== ""
    ToolTip.text: hint
    ToolTip.delay: 650
    HoverHandler { enabled: control.enabled; cursorShape: Qt.PointingHandCursor }
}

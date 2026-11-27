import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Button {
    id: control
    property bool selected: false
    property string iconName: "list"
    property string count: ""
    implicitHeight: 40
    leftPadding: 20; rightPadding: 12
    hoverEnabled: true
    HoverHandler { cursorShape: Qt.PointingHandCursor }
    background: Rectangle {
        radius: Theme.radiusSm
        color: control.selected ? Theme.navigation : control.hovered ? Theme.hover : "transparent"
        border.width: control.activeFocus ? 1 : 0
        border.color: control.activeFocus ? Theme.accent : Theme.line
        Behavior on color { ColorAnimation { duration: 120 } }
        Rectangle {
            anchors.verticalCenter: parent.verticalCenter
            x: 6; width: 3; height: 18; radius: 2
            color: Theme.accent; visible: control.selected
        }
    }
    contentItem: RowLayout {
        spacing: 12
        Icon { name: control.iconName; Layout.preferredWidth: 18; Layout.preferredHeight: 18 }
        UiText { text: control.text; Layout.fillWidth: true; color: control.selected ? Theme.ink : Theme.secondary; font.weight: control.selected ? Font.DemiBold : Font.Medium }
        Rectangle {
            visible: control.count !== ""
            Layout.preferredWidth: countLabel.implicitWidth + 14; Layout.preferredHeight: 22
            radius: 6; color: control.selected ? Theme.surface : Theme.raised
            UiText { id: countLabel; anchors.centerIn: parent; text: control.count; color: Theme.secondary; font.pixelSize: 12; font.weight: Font.Medium; font.features: { "tnum": 1 } }
        }
    }
}

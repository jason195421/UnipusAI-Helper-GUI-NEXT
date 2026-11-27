import QtQuick

Rectangle {
    id: indicator
    property bool motionReady: false
    Component.onCompleted: motionReady = true
    property int checkState: Qt.Unchecked
    property bool highlighted: false
    implicitWidth: 18; implicitHeight: 18
    radius: Theme.radiusXs
    color: checkState !== Qt.Unchecked ? Theme.accentFill : Theme.surface
    border.width: 1
    border.color: highlighted ? Theme.accent : checkState !== Qt.Unchecked ? Theme.accentFill : Theme.muted
    Behavior on color { enabled: indicator.motionReady && Theme.motionEnabled; ColorAnimation { duration: Theme.motionFast } }
    Behavior on border.color { enabled: indicator.motionReady && Theme.motionEnabled; ColorAnimation { duration: Theme.motionFast } }
    Icon {
        anchors.centerIn: parent; name: "check"; onAccent: true; width: 14; height: 14
        opacity: indicator.checkState === Qt.Checked ? 1 : 0
        scale: indicator.checkState === Qt.Checked ? 1 : 0.6
        Behavior on opacity { enabled: indicator.motionReady && Theme.motionEnabled; NumberAnimation { duration: Theme.motionFast } }
        Behavior on scale { enabled: indicator.motionReady && Theme.motionEnabled; NumberAnimation { duration: Theme.motionFast; easing.type: Easing.OutCubic } }
    }
    Rectangle {
        anchors.centerIn: parent; width: 8; height: 2; color: Theme.accentInk
        opacity: indicator.checkState === Qt.PartiallyChecked ? 1 : 0
        Behavior on opacity { enabled: indicator.motionReady && Theme.motionEnabled; NumberAnimation { duration: Theme.motionFast } }
    }
}

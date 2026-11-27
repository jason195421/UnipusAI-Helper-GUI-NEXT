import QtQuick

Rectangle {
    id: track
    property real value: 0
    readonly property real clampedValue: Math.max(0, Math.min(1, value))
    property real displayedValue: 0
    property bool motionReady: false
    Component.onCompleted: {
        displayedValue = clampedValue
        motionReady = true
    }
    function updateDisplayedValue() {
        if (!motionReady) {
            displayedValue = clampedValue
            return
        }
        progressMotion.stop()
        if (!Theme.motionEnabled || clampedValue === 0 || Math.abs(displayedValue - clampedValue) < 0.0001) {
            displayedValue = clampedValue
        } else {
            progressMotion.to = clampedValue
            progressMotion.start()
        }
    }
    onClampedValueChanged: updateDisplayedValue()
    NumberAnimation {
        id: progressMotion
        target: track; property: "displayedValue"
        duration: Theme.motionProgress; easing.type: Easing.OutCubic
    }
    Connections {
        target: Theme
        function onMotionEnabledChanged() { track.updateDisplayedValue() }
    }
    implicitHeight: 6
    radius: height / 2
    color: Theme.track
    Accessible.role: Accessible.ProgressBar
    Accessible.name: "完成进度 " + Math.round(value * 100) + "%"
    Rectangle {
        height: track.height
        width: track.width * track.displayedValue
        radius: track.radius
        color: Theme.accent
    }
}

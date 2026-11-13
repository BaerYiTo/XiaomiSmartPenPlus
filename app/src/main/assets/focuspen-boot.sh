#!/system/bin/sh
(
    while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done
    while true; do
        stop xiaomipen-buttons 2>/dev/null
        stop xiaomipen 2>/dev/null
        pkill -x penkeyd 2>/dev/null
        if ! pidof penstatusd >/dev/null; then
            nohup /data/adb/focuspen/penstatusd >>/data/adb/focuspen/hardware.log 2>&1 &
        fi
        sleep 5
    done
) &

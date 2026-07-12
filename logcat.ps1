adb logcat -c
rm nexa_vpn_log.txt
adb logcat -s NexaVpnService:D > nexa_vpn_log.txt
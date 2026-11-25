#!/bin/bash
# Small atomic textfile for Pi-specific temperature and firmware throttle indicators.
set -euo pipefail
directory=/mnt/nvme/labflow-monitoring/textfile
mountpoint -q /mnt/nvme
temperature=$(/usr/bin/vcgencmd measure_temp)
throttled=$(/usr/bin/vcgencmd get_throttled)
[[ $temperature =~ =([0-9]+([.][0-9]+)?) ]] || exit 1
temperature=${BASH_REMATCH[1]}
hex=${throttled#throttled=}
[[ $hex =~ ^0x[0-9a-fA-F]+$ ]] || exit 1
value=$((hex))
temporary=$(mktemp "$directory/pi.prom.XXXXXX")
trap 'rm -f "$temporary"' EXIT
{
  printf '# HELP labflow_pi_temperature_celsius Raspberry Pi SoC temperature.\n# TYPE labflow_pi_temperature_celsius gauge\nlabflow_pi_temperature_celsius %s\n' "$temperature"
  printf '# HELP labflow_pi_throttle_flags Raw vcgencmd get_throttled bitfield.\n# TYPE labflow_pi_throttle_flags gauge\nlabflow_pi_throttle_flags %s\n' "$value"
  printf 'labflow_pi_undervoltage %s\n' "$((value & 1))"
  printf 'labflow_pi_frequency_capped %s\n' "$(((value >> 1) & 1))"
  printf 'labflow_pi_throttled %s\n' "$(((value >> 2) & 1))"
  printf 'labflow_pi_soft_temp_limit %s\n' "$(((value >> 3) & 1))"
  printf 'labflow_pi_throttled_since_boot %s\n' "$(((value >> 18) & 1))"
  printf 'labflow_pi_collection_timestamp_seconds %s\n' "$(date +%s)"
} > "$temporary"
chmod 644 "$temporary"
mv -f "$temporary" "$directory/pi.prom"

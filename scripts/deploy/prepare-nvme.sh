#!/bin/bash
# One-time preparation for the explicitly authorized format of NVMe partition 1.
# Partition 2 and the SD card are not modified.
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo 'Run this script with sudo.' >&2; exit 1; }
old_uuid=A006C70506C6DB82
uuid=ce734644-d75d-45c4-8c47-9a2b59a409f2
device=/dev/nvme0n1p1
mount_path=/mnt/nvme
[[ -b "$device" ]] || { echo 'Expected NVMe partition is missing.' >&2; exit 1; }
[[ $(lsblk -ndo TYPE "$device") == part ]] || exit 1
[[ $(lsblk -bndo SIZE "$device") == 255458279424 ]] || exit 1
[[ $(lsblk -ndo PKNAME "$device") == nvme0n1 ]] || exit 1
[[ $(lsblk -ndo SERIAL /dev/nvme0n1 | xargs) == SS1J12664Z1CD26E20CG ]] || {
  echo 'NVMe serial does not match the authorized disk.' >&2; exit 1;
}
[[ $(id -u ian) == 1000 && $(id -g ian) == 1000 ]] || exit 1
mkdir -p "$mount_path"
current_uuid=$(blkid -s UUID -o value "$device")
if [[ $current_uuid == "$old_uuid" ]]; then
  [[ $(blkid -s TYPE -o value "$device") == ntfs ]] || exit 1
  if findmnt -rn -S "$device" | grep -q .; then
    echo 'Partition is mounted; refusing to format.' >&2; exit 1
  fi
  if mountpoint -q "$mount_path"; then
    echo 'Mount target is already occupied; refusing to format.' >&2; exit 1
  fi
  echo 'Formatting the explicitly authorized /dev/nvme0n1p1 (238GB).'
  mkfs.ext4 -F -L labflow-nvme -U "$uuid" "$device"
elif [[ $current_uuid != "$uuid" ]]; then
  echo 'Unexpected partition UUID; refusing all changes.' >&2; exit 1
fi
[[ $(blkid -s TYPE -o value "$device") == ext4 ]] || exit 1
if mountpoint -q "$mount_path"; then
  [[ $(findmnt -n -o UUID --target "$mount_path") == "$uuid" ]] || {
    echo 'A different filesystem is already mounted at /mnt/nvme.' >&2; exit 1;
  }
else
  mount -t ext4 -o noatime "$device" "$mount_path"
fi
if ! findmnt --fstab -rn -S "UUID=$uuid" | grep -q .; then
  if findmnt --fstab -rn -M "$mount_path" | grep -q .; then
    echo 'Conflicting /etc/fstab entry; leaving it unchanged.' >&2; exit 1
  fi
  cp -a /etc/fstab "/etc/fstab.labflow-backup-$(date +%Y%m%d%H%M%S)"
  printf '\nUUID=%s %s ext4 defaults,noatime,nofail,x-systemd.device-timeout=15s 0 2\n' "$uuid" "$mount_path" >> /etc/fstab
  systemctl daemon-reload
fi
install -d -o ian -g ian -m 700 "$mount_path/labflow"
loginctl enable-linger ian
printf '\nNVMe ready: LabFlow ext4 partition mounted.\n'
findmnt "$mount_path"
df -h "$mount_path"
